"""Real HTTP/MySQL acceptance for draft management, withdrawal, retries and durable recovery.
Publish remains unimplemented. Publication/snapshot setup below is explicitly synthetic SQL.
Run --phase prepare with the worker OFF, restart the real server with it ON, then --phase resume.
"""
from pathlib import Path
import argparse,concurrent.futures,datetime,json,secrets,subprocess,sys,time,urllib.request,urllib.error,urllib.parse
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--phase',choices=['prepare','resume'],required=True);args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone();assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute("SET time_zone='+00:00'")
out=ROOT/'.local/auth-dev/admin-notifications';out.mkdir(parents=True,exist_ok=True)
probe=subprocess.run(['powershell','-NoProfile','-Command',"$noticeOwner=(Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess; (Get-CimInstance Win32_Process -Filter ('ProcessId='+$noticeOwner)).CommandLine"],capture_output=True,text=True,check=True)
assert 'campus-pi-server-0.0.1-SNAPSHOT.jar' in probe.stdout
assert '--campus.notifications.worker-enabled='+('false' if args.phase=='prepare' else 'true') in probe.stdout,'Start the expected real worker mode before running this phase'
if args.phase=='prepare' and (out/'fixtures.json').exists():
 previous=json.loads((out/'fixtures.json').read_text(encoding='utf-8'))
 with db.cursor() as cur:
  # Restore only the previous exact negative-test fixture before replacing its evidence pointer.
  cur.execute("UPDATE notification_publish_jobs j SET j.recipient_count=1 WHERE j.notification_id=%s AND j.recipient_count=2 AND (SELECT COUNT(*) FROM notification_receipts r WHERE r.notification_id=j.notification_id)=1",(previous['broken'],))
  if previous.get('worker_trigger'):
   assert previous['worker_trigger'].startswith('notice_worker_') and previous['worker_trigger'].replace('_','').isalnum()
   cur.execute('DROP TRIGGER IF EXISTS '+previous['worker_trigger'])
report=out/'http-acceptance.json';checks=[];complete=False;ungranted_id=None
if args.phase=='resume':checks=json.loads(report.read_text(encoding='utf-8'))['checks']
def write(status):report.write_text(json.dumps({'status':status,'phase':args.phase,'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks,'publication_is_synthetic_fixture':True},ensure_ascii=False,indent=2),encoding='utf-8')
write('RUNNING')
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def sql(query,params=(),rows=False):
 with db.cursor() as cur:
  cur.execute(query,params)
  return (cur.fetchall() if rows else cur.fetchone()[0]) if cur.description else cur.lastrowid
def call(route,method='GET',body=None,token=None,key=None):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 if body is not None:headers['Content-Type']='application/json'
 request=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,method=method,data=json.dumps(body).encode() if body is not None else None,headers=headers)
 try:r=urllib.request.urlopen(request,timeout=30)
 except urllib.error.HTTPError as e:r=e
 return r.status,json.loads(r.read())
def login(name,password):
 status,r=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Actual login succeeds');return r['data']['access_token']
def user(label):
 name='notice_'+label+'_'+tag;password=secrets.token_urlsafe(20)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16));check(status==201,'Actual synthetic registration '+label)
 return r['data']['user_id'],name,password,login(name,password)
def body(title='通知验收',scope='指定用户',filter=None):return {'notification_title':title+'_'+tag,'notification_content':'合成纯文本 <script>alert(1)</script>\n第二行','notification_type':'系统','target_scope':scope,'target_filter':filter if filter is not None else {'user_ids':[recipient]}}
def create(data=None,key=None,token=None):return call('/admin/notifications','POST',data if data is not None else body(),token or admin,key or secrets.token_hex(16))
def draft(title='通知验收'):
 status,r=create(body(title));check(status==201,'Actual notification draft creation '+title);return r['data']['notification_id']
def mutate(id,action,key=None,reason='合成验收原因',token=None):return call('/admin/notifications/'+id+'/'+action,'POST',{'reason':reason},token or admin,key or secrets.token_hex(16))
def retry(id,key=None,reason='合成重试原因',token=None):return call('/admin/notification-deliveries/'+str(id)+'/retry','POST',{'reason':reason},token or admin,key or secrets.token_hex(16))
def seed(title,state='已发送',attempt_state='失败',attempt_no=1,channel='站内',job=True):
 id=draft(title)
 # Isolated synthetic publication; not evidence that API107 has been implemented.
 db.begin()
 try:
  sql('UPDATE system_notifications SET publish_status=%s,publish_time=IF(%s=%s,UTC_TIMESTAMP(3),NULL),notification_type=%s WHERE notification_id=%s',(state,state,'已发送','安全' if channel!='站内' else '系统',id))
  sql('INSERT INTO notification_receipts(notification_id,user_id) VALUES(%s,%s)',(id,recipient))
  if job:sql('INSERT INTO notification_publish_jobs(job_id,notification_id,recipient_count,job_status) VALUES(%s,%s,1,%s)',('job_'+secrets.token_hex(16),id,'待执行' if state=='待发送' else '已完成'))
  delivery=str(sql('INSERT INTO notification_delivery(notification_id,user_id,channel,delivery_status,attempt_no,failure_reason) VALUES(%s,%s,%s,%s,%s,%s)',(id,recipient,channel,attempt_state,attempt_no,'合成失败' if attempt_state=='失败' else None)))
  if job:sql('INSERT INTO notification_outbox(delivery_id,notification_id,work_status) VALUES(%s,%s,%s)',(delivery,id,'待执行' if attempt_state=='待发送' else '已完成'))
  db.commit();return id,delivery
 except:db.rollback();raise
def audit(id,action):return sql('SELECT COUNT(*) FROM admin_audit_log WHERE object_type=%s AND object_id=%s AND action_name=%s',('通知',id,action))
try:
 admin=login(c['admin_username'],c['admin_password'])
 if args.phase=='prepare':
  tag=secrets.token_hex(4);recipient,username,password,ordinary=user('target');ungranted_id,ungranted_name,ungranted_password,_=user('admin')
  sql("UPDATE users SET role='ADMIN' WHERE user_id=%s",(ungranted_id,));ungranted=login(ungranted_name,ungranted_password)
  check(call('/admin/notifications')[0]==401,'Anonymous list rejected');check(call('/admin/notifications',token=ordinary)[0]==403,'Ordinary account rejected')
  data=body('草稿');key=secrets.token_hex(16);status,r=create(data,key);check(status==201,'Draft returns 201');id=r['data']['notification_id'];view=r['data']
  check(len(id)<=50 and view['publish_status']=='草稿' and view['publish_time'] is None,'Time-prefixed opaque draft ID and null publish time')
  check(set(view)=={'notification_id','notification_title','notification_content','notification_type','target_scope','target_filter','publish_status','publish_time'},'Admin DTO contains only contracted safe fields')
  check(sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(id,))==0 and sql('SELECT COUNT(*) FROM notification_publish_jobs WHERE notification_id=%s',(id,))==0,'Creating draft never sends or schedules')
  check(create(dict(reversed(list(data.items()))),key)[1]['data']['notification_id']==id,'Canonical same-key replay returns original draft')
  check(create(body('不同正文'),key)[0]==409,'Same key with changed content rejected');check(audit(id,'创建草稿')==1,'Draft audit written once')
  check(call('/admin/notifications','POST',data,admin)[0]==400,'Creation requires idempotency key')
  for change in [{'target_scope':'全体','target_filter':{'user_ids':[recipient]}},{'target_scope':'指定用户','target_filter':{'user_ids':[recipient,recipient]}},{'target_scope':'指定用户','target_filter':{'user_ids':[int(recipient)]}},{'target_scope':'指定用户','target_filter':{'user_ids':['9223372036854775808']}},{'target_scope':'指定用户','target_filter':{'user_ids':[]}},{'target_scope':'校区','target_filter':{'campus':''}},{'notification_type':'自定义'},{'notification_title':'x'*201},{'notification_content':'x'*10001},{'scheduled_at':None},{'publisher_id':recipient},{'publish_status':'已发送'}]:
   check(create(data|change)[0]==400,'Invalid/privileged draft field rejected '+','.join(change))
  check(create(body('全体','全体',{}))[0]==201,'All-target draft accepted')
  check(create(body('校区','校区',{'campus':'测试校区'}))[0]==201,'Campus-target draft accepted')
  check(create(body('大编号','指定用户',{'user_ids':['9007199254740993']}))[0]==201,'BIGINT target preserved as a string')
  replacement=body('替换','校区',{'campus':'测试校区'});status,r=call('/admin/notifications/'+id,'PUT',replacement,admin)
  check(status==200 and r['data']['target_filter']=={'campus':'测试校区'} and r['data']['notification_title']==replacement['notification_title'],'Draft replacement updates content and target atomically')
  check(call('/admin/notifications/'+id,token=admin)[1]['data']==r['data'],'Detail reflects saved draft')
  check(call('/admin/notifications/'+id,token=ordinary)[0]==403,'Ordinary user cannot inspect draft')
  for query in ['page=0','page_size=101','page=2147483647&page_size=100','publish_status=未知']:
   check(call('/admin/notifications?'+urllib.parse.quote(query,safe='=&'),token=admin)[0]==400,'Invalid notification pagination/filter '+query)
  check(call('/admin/notifications/does_not_exist',token=admin)[0]==404,'Missing notification returns 404')
  rows=call('/admin/notifications?publish_status='+urllib.parse.quote('草稿'),token=admin)[1]['data']['items'];ids=[r['notification_id'] for r in rows]
  check(ids==sorted(ids,reverse=True) and all(r['publish_status']=='草稿' for r in rows),'Notification list uses contracted status filter and stable ID ordering')
  check(mutate(id,'withdraw')[0]==409,'Draft cannot be withdrawn')
  check(call('/admin/notifications/'+id+'/publish','POST',{'channels':[]},admin,secrets.token_hex(16))[0]==400,'Publication rejects empty channel selection without sending')
  check(call('/admin/notifications/'+id+'/publish','POST',{'channels':['站内']},ungranted,secrets.token_hex(16))[0]==403,'ADMIN alone does not grant publishing duty')
  check(call('/admin/notifications/'+id+'/deliveries',token=ungranted)[0]==403,'Delivery identities require explicit publishing duty')
  check(call('/admin/notifications/'+id+'/deliveries',token=admin)[1]['data']['total']=='0','Unsent draft has no deliveries')
  active,pending=seed('撤回','待发送','待发送');key=secrets.token_hex(16)
  check(mutate(active,'withdraw',token=ungranted)[0]==403,'Withdrawal requires publishing duty')
  status,r=mutate(active,'withdraw',key);check(status==200 and r['data']['publish_status']=='已撤回','Pending notice withdrawal succeeds')
  check(sql('SELECT work_status FROM notification_outbox WHERE delivery_id=%s',(pending,))=='已取消' and sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(pending,))=='失败','Withdrawal cancels durable work and pending attempt together')
  check(sql('SELECT job_status FROM notification_publish_jobs WHERE notification_id=%s',(active,))=='已取消' and sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(active,))==1,'Withdrawal retains snapshot and cancels activation')
  check(mutate(active,'withdraw',key)[0]==200 and audit(active,'撤回')==1,'Withdrawal replay audited once');check(mutate(active,'withdraw')[0]==409,'Second withdrawal action conflicts');check(retry(pending)[0]==409,'Withdrawn notice cannot retry')
  sent,failed=seed('失败重试');key=secrets.token_hex(16);status,r=retry(failed,key);check(status==202 and r['data']['attempt_no']==2 and r['data']['delivery_status']=='待发送','Retry appends pending attempt and returns 202');new=r['data']['delivery_id']
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(failed,))=='失败','Old failed attempt is preserved')
  check(retry(failed,key)[1]['data']['delivery_id']==new and audit(sent,'重试投递')==1,'Retry replay returns same new attempt and one audit')
  check(retry(failed)[0]==409 and retry(new)[0]==409,'Old failed and current pending attempts cannot be retried again')
  check(retry(failed,key,reason='改变原因')[0]==409,'Retry idempotency cannot be reused with another body')
  capped,maxid=seed('次数上限',attempt_no=5);check(retry(maxid)[0]==409,'Five-attempt maximum enforced')
  external,sms=seed('外部渠道',channel='短信');check(retry(sms)[0]==501 and sql('SELECT COUNT(*) FROM notification_delivery WHERE notification_id=%s',(external,))==1,'Unconnected SMS stays 501 without another attempt')
  legacy,old=seed('历史缺失任务',job=False);check(retry(old)[0]==503,'Legacy notice without durable task cannot silently retry')
  race,raceid=seed('并发重试')
  with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda _:retry(raceid)[0],range(2)))
  check(sorted(results)==[202,409] and sql('SELECT COUNT(*) FROM notification_delivery WHERE notification_id=%s',(race,))==2,'Concurrent different-key retries create one new attempt')
  fault,faultid=seed('审计回滚','已发送','待发送')
  trigger='notice_audit_'+tag
  sql("CREATE TRIGGER "+trigger+" BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='通知' AND NEW.object_id='"+fault+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic audit rejection'; END IF; END")
  try:check(mutate(fault,'withdraw')[0]==503,'Audit failure blocks withdrawal')
  finally:sql('DROP TRIGGER '+trigger)
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(fault,))=='已发送' and sql('SELECT work_status FROM notification_outbox WHERE delivery_id=%s',(faultid,))=='待执行','Withdrawal audit failure rolls back state and outbox')
  retryfault,retryfaultid=seed('重试审计回滚');trigger='notice_retry_audit_'+tag
  sql("CREATE TRIGGER "+trigger+" BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='通知' AND NEW.object_id='"+retryfault+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic retry audit rejection'; END IF; END")
  try:check(retry(retryfaultid)[0]==503,'Audit failure blocks retry')
  finally:sql('DROP TRIGGER '+trigger)
  check(sql('SELECT COUNT(*) FROM notification_delivery WHERE notification_id=%s',(retryfault,))==1,'Retry audit failure leaves no new attempt')
  status,r=call('/admin/notifications/'+sent+'/deliveries',token=admin);deliveries=r['data']['items']
  check(status==200 and [int(d['delivery_id']) for d in deliveries]==sorted([int(d['delivery_id']) for d in deliveries],reverse=True),'Delivery list uses stable descending BIGINT order')
  check(all('read_at' not in d and 'provider_reference' not in d for d in deliveries),'Delivery list excludes private reading detail and provider payload')
  for query in ['page=0','delivery_status=未知','channel=未知']:
   check(call('/admin/notifications/'+sent+'/deliveries?'+urllib.parse.quote(query,safe='=&'),token=admin)[0]==400,'Invalid delivery query '+query)
  check(call('/admin/notifications/'+sent,'PUT',data,admin)[0]==409,'Sent content cannot be overwritten')
  recovery,recoveryid=seed('重启恢复','待发送','待发送');broken,brokenid=seed('快照错误','待发送','待发送');sql('UPDATE notification_publish_jobs SET recipient_count=2 WHERE notification_id=%s',(broken,))
  browser_id=draft('浏览器草稿');browser_withdraw,_=seed('浏览器撤回','已发送','成功');browser_retry,browser_retryid=seed('浏览器重试')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(new,))=='待发送','Worker OFF leaves durable retry pending before server restart')
  worker_fault,worker_faultid=seed('实际投递失败','已发送','待发送');worker_trigger='notice_worker_'+tag
  fixtures={'tag':tag,'recipient':recipient,'draft':browser_id,'withdraw':browser_withdraw,'retry_notice':browser_retry,'retry_delivery':browser_retryid,'recovery':recovery,'recovery_delivery':recoveryid,'retry_new':new,'broken':broken,'canceled':active,'old_failed':failed,'worker_fault':worker_fault,'worker_faultid':worker_faultid,'worker_trigger':worker_trigger}
  (out/'fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2),encoding='utf-8')
  sql("CREATE TRIGGER "+worker_trigger+" BEFORE UPDATE ON notification_delivery FOR EACH ROW BEGIN IF NEW.delivery_id="+worker_faultid+" AND NEW.delivery_status='成功' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Synthetic durable delivery failure'; END IF; END")
  write('PREPARED');complete=True
 else:
  f=json.loads((out/'fixtures.json').read_text(encoding='utf-8'));tag=f['tag'];recipient=f['recipient'];deadline=time.monotonic()+20
  while time.monotonic()<deadline:
   if sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['recovery_delivery'],))=='成功' and sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['retry_new'],))=='成功':break
   time.sleep(.25)
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(f['recovery'],))=='已发送','Real worker after process restart activates persisted complete snapshot')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['recovery_delivery'],))=='成功','Real worker resumes pre-restart outbox work')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['retry_new'],))=='成功','Real worker completes pre-restart operator retry')
  check(sql('SELECT COUNT(*) FROM notification_receipts WHERE notification_id=%s',(f['recovery'],))==1,'Worker recovery creates no duplicate receiver')
  check(sql('SELECT read_at FROM notification_receipts WHERE notification_id=%s',(f['recovery'],)) is None,'In-app success does not imply read')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['old_failed'],))=='失败','Recovery does not overwrite prior failed attempt')
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(f['broken'],))=='待发送','Mismatched snapshot is never marked sent')
  check(sql('SELECT publish_status FROM system_notifications WHERE notification_id=%s',(f['canceled'],))=='已撤回','Restart does not reactivate withdrawn notification')
  check(sql('SELECT sent_at FROM notification_delivery WHERE delivery_id=%s',(f['recovery_delivery'],)) is not None,'Actual delivery stores timestamp')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['worker_faultid'],))=='失败','Actual worker failure is durably recorded without claiming success')
  check(sql('SELECT work_status FROM notification_outbox WHERE delivery_id=%s',(f['worker_faultid'],))=='已完成','Failed attempt is not endlessly redelivered')
  sql('DROP TRIGGER IF EXISTS '+f['worker_trigger']);status,r=retry(f['worker_faultid']);check(status==202,'Real failed worker attempt can be explicitly retried')
  new=r['data']['delivery_id'];deadline=time.monotonic()+15
  while time.monotonic()<deadline:
   if sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(new,))=='成功':break
   time.sleep(.2)
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(new,))=='成功','Worker completes the new retry after dependency recovers')
  check(sql('SELECT delivery_status FROM notification_delivery WHERE delivery_id=%s',(f['worker_faultid'],))=='失败','Real failure history remains intact after successful retry')
  # Repair only this script's intentionally mismatched synthetic job so it cannot starve later acceptance.
  sql('UPDATE notification_publish_jobs SET recipient_count=1 WHERE notification_id=%s',(f['broken'],))
  complete=True;write('PASS')
 print(json.dumps({'status':'PREPARED' if args.phase=='prepare' else 'PASS','checks':len(checks),'phase':args.phase,'credential_values_printed':False}))
finally:
 if ungranted_id:
  sql("UPDATE users SET role='USER',auth_version=auth_version+1 WHERE user_id=%s",(ungranted_id,));sql('UPDATE auth_sessions SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=%s',(ungranted_id,))
 if args.phase=='resume':
  saved=json.loads((out/'fixtures.json').read_text(encoding='utf-8'))
  if saved.get('worker_trigger'):sql('DROP TRIGGER IF EXISTS '+saved['worker_trigger'])
 if not complete:write('INCOMPLETE')
 db.close()
