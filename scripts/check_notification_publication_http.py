"""Real publication -> durable worker -> owned inbox acceptance. No synthetic receipt insertion.
prepare requires the real server's worker OFF; restart worker ON before resume.
Only account-campus fixture assignment and a temporary DB fault use isolated SQL.
"""
from pathlib import Path
import argparse,concurrent.futures,datetime,json,secrets,subprocess,sys,time,urllib.request,urllib.error,urllib.parse
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--phase',choices=['prepare','resume'],required=True);args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'));assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone();assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute("SET time_zone='+00:00'")
probe=subprocess.run(['powershell','-NoProfile','-Command',"$noticeOwner=(Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess; (Get-CimInstance Win32_Process -Filter ('ProcessId='+$noticeOwner)).CommandLine"],capture_output=True,text=True,check=True)
assert 'campus-pi-server-0.0.1-SNAPSHOT.jar' in probe.stdout and '--campus.notifications.worker-enabled='+('false' if args.phase=='prepare' else 'true') in probe.stdout,'Start expected real worker mode first'
out=ROOT/'.local/auth-dev/notification-publication';out.mkdir(parents=True,exist_ok=True);report=out/'http-acceptance.json';complete=False;checks=[]
if args.phase=='resume':checks=json.loads(report.read_text(encoding='utf-8'))['checks']
def write(status):report.write_text(json.dumps({'status':status,'phase':args.phase,'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks,'receipts_created_by_actual_api':True},ensure_ascii=False,indent=2),encoding='utf-8')
write('RUNNING')
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def sql(query,params=(),rows=False):
 with db.cursor() as cur:
  cur.execute(query,params)
  return (cur.fetchall() if rows else cur.fetchone()[0]) if cur.description else cur.lastrowid
def request(route,method='GET',body=None,token=None,key=None):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 if body is not None:headers['Content-Type']='application/json'
 req=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,method=method,data=json.dumps(body).encode() if body is not None else None,headers=headers)
 try:r=urllib.request.urlopen(req,timeout=45)
 except urllib.error.HTTPError as e:r=e
 return r.status,json.loads(r.read()),dict(r.headers)
def call(*a,**kw):return request(*a,**kw)[:2]
def login(name,password):
 status,r=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Real login succeeds');return r['data']['access_token']
def register(label):
 name='pub_'+label+'_'+tag;password=secrets.token_urlsafe(20)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16));check(status==201,'Actual synthetic registration '+label)
 return {'id':r['data']['user_id'],'username':name,'password':password,'token':login(name,password)}
def pref(person,enabled=True,start=None,end=None):
 status,r=call('/me/notification-preferences','PUT',{'in_app_enabled':enabled,'email_enabled':False,'sms_enabled':False,'dnd_start':start,'dnd_end':end},person['token']);check(status==200,'Actual preference save');return r['data']
def draft(label,targets=None,scope='指定用户',filter=None,type='系统'):
 body={'notification_title':label+'_'+tag,'notification_content':'真实发布的合成纯文本 <script>alert(1)</script>\n第二行','notification_type':type,'target_scope':scope,'target_filter':filter if filter is not None else {'user_ids':targets or [a['id']]}}
 status,r=call('/admin/notifications','POST',body,admin,secrets.token_hex(16));check(status==201,'Actual draft '+label);return r['data']['notification_id']
def publish(id,key=None,channels=None,token=None):return call('/admin/notifications/'+id+'/publish','POST',{'channels':channels or ['站内']},token or admin,key or secrets.token_hex(16))
def detail(id,person):return call('/me/notifications/'+urllib.parse.quote(id),token=person['token'])
def status(id,person):return sql("SELECT delivery_status FROM notification_delivery WHERE notification_id=%s AND user_id=%s AND channel='站内' ORDER BY attempt_no DESC LIMIT 1",(id,person['id']))
def wait(predicate,seconds=15):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  if predicate():return True
  time.sleep(.2)
 return bool(predicate())
def window(seconds):
 now=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)));return (now-datetime.timedelta(seconds=5)).strftime('%H:%M:%S'),(now+datetime.timedelta(seconds=seconds)).strftime('%H:%M:%S')
try:
 admin=login(c['admin_username'],c['admin_password'])
 if args.phase=='prepare':
  tag=secrets.token_hex(4);a=register('a');b=register('b');off=register('off');other=register('other');quiet=register('quiet')
  status_code,r,headers=request('/me/notification-preferences',token=a['token']);default={'in_app_enabled':True,'email_enabled':False,'sms_enabled':False,'dnd_start':None,'dnd_end':None}
  check(status_code==200 and r['data']==default,'Unstored preference returns contracted default view');check(sql('SELECT COUNT(*) FROM notification_preferences WHERE user_id=%s',(a['id'],))==0,'Preference GET does not write a row');check('no-store' in headers.get('Cache-Control',''),'Preference response is noncacheable')
  pref(off,False)
  for change in [{'in_app_enabled':1},{'sms_enabled':True},{'email_enabled':True},{'dnd_start':'22:00:00'},{'dnd_start':'22:00:00','dnd_end':'22:00:00'},{'dnd_start':'24:00:00','dnd_end':'07:00:00'},{'dnd_start':'22:00','dnd_end':'07:00'},{'user_id':a['id']}]:
   code,_=call('/me/notification-preferences','PUT',default|change,off['token']);check(code==(501 if change.get('sms_enabled') or change.get('email_enabled') else 400),'Invalid or unconnected preference rejected '+','.join(change))
  check(call('/me/notification-preferences',token=off['token'])[1]['data']['in_app_enabled'] is False,'Rejected preference replacement leaves existing opt-out unchanged')
  check(call('/me/notification-preferences','PUT',{'in_app_enabled':True},a['token'])[0]==400,'Preferences require complete replacement')
  ordinary=draft('普通偏好',[a['id'],b['id'],off['id']]);key=secrets.token_hex(16);code,r=publish(ordinary,key);check(code==202 and r['data']['recipient_count']==2,'Publication snapshot excludes opted-out receiver')
  job=r['data']['job_id'];check(publish(ordinary,key)[1]['data']['job_id']==job,'Publication retry returns original durable job');check(publish(ordinary,key,['站内','站内'])[1]['data']['job_id']==job,'Channel deduplication gives canonical replay')
  check(publish(ordinary)[0]==409,'New-key second publication conflicts');check(sql('SELECT COUNT(*) FROM notification_delivery WHERE notification_id=%s',(ordinary,))==2 and sql('SELECT COUNT(*) FROM notification_outbox WHERE notification_id=%s',(ordinary,))==2,'One attempt and work row per frozen receiver')
  check(sql('SELECT COUNT(*) FROM admin_audit_log WHERE object_type=%s AND object_id=%s AND action_name=%s',('通知',ordinary,'审核发布'))==1,'Publication is audited once')
  check(detail(ordinary,a)[0]==404,'Queued notification is not visible before actual delivery')
  security=draft('安全也遵守关闭',[off['id']],type='安全');code,r=publish(security);check(code==202 and r['data']['recipient_count']==0 and sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(security,))==0,'Security notification does not bypass receiver opt-out')
  invalid=draft('渠道校验');check(publish(invalid,channels=['站内','短信'])[0]==400,'Ordinary notification cannot use SMS');safe=draft('安全渠道',type='安全');check(publish(safe,channels=['站内','邮件'])[0]==501,'Unconnected security email returns 501 without partial publication')
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(safe,))=='草稿' and sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(safe,))==0,'External-channel rejection leaves no partial inbox or job')
  for body in [{'channels':[]},{'channels':['未知']},{'channels':['短信']},{'channels':['站内'],'job_id':'client'},{'channels':['站内'],'publisher_id':a['id']}]:check(call('/admin/notifications/'+invalid+'/publish','POST',body,admin,secrets.token_hex(16))[0]==400,'Invalid publication request rejected')
  check(call('/admin/notifications/'+invalid+'/publish','POST',{'channels':['站内']},admin)[0]==400,'Publication requires idempotency key');check(publish(invalid,token=a['token'])[0]==403,'Member cannot publish as administrator')
  missing=draft('不存在接收者',['9223372036854775807']);check(publish(missing)[0]==404 and sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(missing,))==0,'Nonexistent specified target rejects entire publication')
  fault=draft('发布审计故障');trigger='publication_audit_'+tag
  sql("CREATE TRIGGER "+trigger+" BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='通知' AND NEW.object_id='"+fault+"' AND NEW.action_name='审核发布' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic publication audit rejection'; END IF; END")
  try:check(publish(fault)[0]==503,'Publication audit failure rejects request')
  finally:sql('DROP TRIGGER '+trigger)
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(fault,))=='草稿' and all(sql('SELECT COUNT(*) FROM '+table+' WHERE notification_id=%s',(fault,))==0 for table in ['notification_receipts','notification_delivery','notification_outbox','notification_publish_jobs']),'Audit failure rolls back snapshot, attempts, outbox, job and state')
  race=draft('并发发布',[a['id'],b['id']])
  with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:publish(race)[0],range(2)))
  check(sorted(results)==[202,409] and sql('SELECT COUNT(*) FROM notification_publish_jobs WHERE notification_id=%s',(race,))==1,'Concurrent publications establish one durable job')
  campus='通知测试校区_'+tag
  sql('UPDATE users SET campus=%s WHERE user_id IN (%s,%s,%s)',(campus,a['id'],b['id'],off['id'])) # Explicit account-campus fixture; no receipt injection.
  scoped=draft('校区发布',scope='校区',filter={'campus':campus});check(publish(scoped)[1]['data']['recipient_count']==2,'Campus snapshot includes matching enabled preferences only')
  empty=draft('空校区',scope='校区',filter={'campus':'空校区_'+tag});check(publish(empty)[1]['data']['recipient_count']==0,'Zero-recipient publication is a durable valid job')
  expected=sql('SELECT COUNT(*) FROM users u LEFT JOIN notification_preferences p ON p.user_id=u.user_id WHERE COALESCE(p.in_app_enabled,TRUE)=TRUE');all_id=draft('全体冻结',scope='全体',filter={});check(publish(all_id)[1]['data']['recipient_count']==expected,'All-user snapshot reflects current opt-outs')
  newcomer=register('late');check(sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s AND user_id=%s',(all_id,newcomer['id']))==0,'Registration after publication does not expand frozen target set')
  pref(off,True);check(sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s AND user_id=%s',(ordinary,off['id']))==0,'Enabling later does not backfill prior excluded target')
  fixtures={'tag':tag,'a':{k:v for k,v in a.items() if k!='token'},'b':{k:v for k,v in b.items() if k!='token'},'off':{k:v for k,v in off.items() if k!='token'},'other':{k:v for k,v in other.items() if k!='token'},'quiet':{k:v for k,v in quiet.items() if k!='token'},'ordinary':ordinary,'security':security,'empty':empty,'all_id':all_id,'scoped':scoped,'browser_draft':draft('浏览器真实发布',[a['id']])}
  # Private credentials are kept in their own ignored test-only file, never in acceptance reports.
  (out/'private-fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2),encoding='utf-8')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE notification_id=%s LIMIT 1',(ordinary,))=='待发送','Real worker OFF leaves publication durable for process-restart test');complete=True;write('PREPARED')
 else:
  f=json.loads((out/'private-fixtures.json').read_text(encoding='utf-8'));tag=f['tag'];a=f['a'];b=f['b'];off=f['off'];other=f['other'];quiet=f['quiet']
  for person in [a,b,off,other,quiet]:person['token']=login(person['username'],person['password'])
  id=f['ordinary'];check(wait(lambda:detail(id,a)[0]==200),'Actual publication queued before process restart becomes visible through actual inbox API')
  code,r=detail(id,a);check(code==200 and r['data']['read_at'] is None and r['data']['clicked_at'] is None,'GET detail never implicitly reads or clicks')
  check(set(r['data'])=={'notification_id','notification_title','notification_content','notification_type','publish_time','read_at','clicked_at'},'Member DTO contains no publisher, target filter, other identities or delivery internals')
  check(detail(id,other)[0]==404,'Unrelated receiver cannot guess notification detail')
  for action,method in [('read','PUT'),('click','PUT'),('','DELETE')]:check(call('/me/notifications/'+id+('/'+action if action else ''),method,token=other['token'])[0]==404,'Unrelated receiver cannot mutate '+(action or 'delete'))
  check(call('/me/notifications')[0]==401 and call('/me/notification-preferences')[0]==401,'Anonymous inbox and preferences rejected')
  code,r=call('/me/notifications/'+id+'/read','PUT',token=a['token']);first=r['data']['read_at'];check(code==200 and first is not None,'Read stores first receipt timestamp');check(call('/me/notifications/'+id+'/read','PUT',token=a['token'])[1]['data']['read_at']==first,'Repeated read keeps original timestamp')
  check(detail(id,b)[1]['data']['read_at'] is None,'Read state is isolated per receiver');code,r=call('/me/notifications/'+id+'/click','PUT',token=b['token']);data=r['data'];check(code==200 and data['read_at'] is not None and data['clicked_at']>=data['read_at'],'Click on unread receipt stores ordered read/click times');check(call('/me/notifications/'+id+'/click','PUT',token=b['token'])[1]['data']['clicked_at']==data['clicked_at'],'Repeated click keeps original timestamp')
  check(call('/me/notifications/'+id+'/click','PUT',{'url':'https://example.invalid'},a['token'])[0]==400,'Click cannot accept an arbitrary redirect URL')
  code,r=call('/me/notifications/'+id,'DELETE',token=a['token']);check(code==200 and r['data']=={},'Delete returns contracted empty object');check(call('/me/notifications/'+id,'DELETE',token=a['token'])[0]==200 and detail(id,a)[0]==404,'Repeated own delete succeeds and detail becomes hidden');check(detail(id,b)[0]==200,'Deleting one receipt preserves other receiver')
  for query in ['page=0','page_size=101','page=2147483647&page_size=100','notification_type=未知','unread_only=banana']:check(call('/me/notifications?'+urllib.parse.quote(query,safe='=&'),token=b['token'])[0]==400,'Invalid inbox query rejected '+query)
  listed=call('/me/notifications?unread_only=true',token=b['token'])[1]['data']['items'];check(all(row['read_at'] is None for row in listed),'Unread-only list uses this receiver read state')
  check(wait(lambda:sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(f['empty'],))=='已发送'),'Zero-recipient job completes after worker restart')
  # Real wall-clock DND: no runtime clock override, no synthetic receipt/attempt status.
  start,end=window(9);pref(quiet,True,start,end);deferred=draft('实际免打扰安全',[quiet['id']],type='安全');check(publish(deferred)[0]==202,'Safety notification uses actual DND queue')
  check(detail(deferred,quiet)[0]==404 and status(deferred,quiet)=='待发送','DND receipt is invisible before its worker delivery')
  time.sleep(1);check(detail(deferred,quiet)[0]==404,'No early inbox visibility during actual DND')
  check(wait(lambda:detail(deferred,quiet)[0]==200,15) and status(deferred,quiet)=='成功','Safety notification arrives only after real DND end')
  available,sent=sql('SELECT o.available_at,d.sent_at FROM notification_outbox o JOIN notification_delivery d ON d.delivery_id=o.delivery_id WHERE o.notification_id=%s',(deferred,),True)[0];check(sent>=available,'Actual send timestamp is never before persisted DND deadline')
  start,end=window(30);pref(quiet,True,start,end);cancelled=draft('排队后关闭',[quiet['id']]);publish(cancelled);pref(quiet,False,start,end)
  check(wait(lambda:status(cancelled,quiet)=='失败',8) and detail(cancelled,quiet)[0]==404,'Opt-out during delay wakes versioned work and cancels before delivery')
  pref(quiet,True);time.sleep(.5);check(detail(cancelled,quiet)[0]==404,'Re-enabling never automatically revives canceled work')
  start,end=window(30);pref(quiet,True,start,end);shortened=draft('缩短免打扰',[quiet['id']]);publish(shortened);check(detail(shortened,quiet)[0]==404,'Changed-window fixture initially remains invisible');pref(quiet,True)
  check(wait(lambda:detail(shortened,quiet)[0]==200,8),'Clearing DND wakes existing work without waiting for old deadline')
  start,end=window(30);pref(quiet,True,start,end);withdrawn=draft('延迟中撤回',[quiet['id']]);publish(withdrawn);code,_=call('/admin/notifications/'+withdrawn+'/withdraw','POST',{'reason':'合成延迟撤回'},admin,secrets.token_hex(16));check(code==200,'Administrator can withdraw a delayed publication');pref(quiet,True)
  check(detail(withdrawn,quiet)[0]==404 and sql('SELECT work_status FROM notification_outbox WHERE notification_id=%s',(withdrawn,))=='已取消','Withdrawal cancels delayed durable work and keeps inbox hidden')
  browser_notice=draft('浏览器收件',[a['id'],b['id']]);publish(browser_notice);check(wait(lambda:detail(browser_notice,a)[0]==200),'Browser fixture is created through actual publication and delivery')
  f.update({'browser_notice':browser_notice,'deferred':deferred,'cancelled':cancelled,'shortened':shortened,'withdrawn':withdrawn});(out/'private-fixtures.json').write_text(json.dumps({k:({x:y for x,y in v.items() if x!='token'} if isinstance(v,dict) else v) for k,v in f.items()},ensure_ascii=False,indent=2),encoding='utf-8')
  complete=True;write('PASS')
 print(json.dumps({'status':'PREPARED' if args.phase=='prepare' else 'PASS','checks':len(checks),'phase':args.phase,'credentials_printed':False}))
finally:
 if not complete:write('INCOMPLETE')
 db.close()
