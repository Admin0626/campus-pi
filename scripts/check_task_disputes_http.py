"""Real task dispute API checks. Only task/order creation is an explicit SQL fixture."""
from pathlib import Path
import concurrent.futures,datetime,json,secrets,struct,sys,urllib.error,urllib.parse,urllib.request,zlib
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
 assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute("SET time_zone='+00:00'")
out=ROOT/'.local/auth-dev/task-disputes';out.mkdir(parents=True,exist_ok=True)
checks=[];finished=False;tag=secrets.token_hex(4);people={};temporary_admin=None
(out/'http-acceptance.json').write_text('{"status":"RUNNING"}',encoding='utf-8')
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def sql(query,args=(),rows=False):
 with db.cursor() as cur:
  cur.execute(query,args)
  return (cur.fetchall() if rows else cur.fetchone()[0]) if cur.description else cur.lastrowid
def call(route,method='GET',body=None,token=None,key=None,raw=None,content_type=None):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 if body is not None:raw=json.dumps(body,ensure_ascii=False).encode();headers['Content-Type']='application/json'
 if content_type:headers['Content-Type']=content_type
 request=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,data=raw,method=method,headers=headers)
 try:response=urllib.request.urlopen(request,timeout=45)
 except urllib.error.HTTPError as error:response=error
 return response.status,json.loads(response.read()),dict(response.headers)
def login(name,password):
 status,r,_=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Actual login');return r['data']['access_token']
def user(label):
 name='ds_'+label[:4]+'_'+tag;password=secrets.token_urlsafe(20)
 status,r,_=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16));check(status==201,'Register '+label)
 uid=r['data']['user_id'];people[uid]={'name':name,'password':password,'token':login(name,password)};return uid
def identifier(prefix):
 table,column=('user_orders','order_id') if prefix=='ORDER' else ('order_aftersales','aftersale_id')
 while True:
  value=prefix+datetime.datetime.now(datetime.timezone.utc).strftime('%y%m%d')+str(secrets.randbelow(40_000_000)).zfill(8)
  if sql('SELECT COUNT(*) FROM '+table+' WHERE '+column+'=%s',(value,))==0:return value
def task_order():
 # Synthetic user task/order creation, never a mock HTTP response or a synthetic aftersale case.
 task=sql("INSERT INTO RewardTasks(publisher_id,taker_id,task_name,task_description,campus,reward_amount,reward_type,task_status) VALUES(%s,%s,%s,'合成争议验收','测试校区',20,'积分','进行中')",(buyer,seller,'争议任务_'+tag))
 order=identifier('ORDER')
 sql("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,reward_task_id,item_name,quantity,unit_price,total_amount,order_status) VALUES(%s,'任务',%s,%s,%s,%s,1,20,20,'进行中')",(order,buyer,seller,task,'争议任务_'+tag));return order,str(task)
def apply(order,key=None,token=None,**change):
 body={'order_id':order,'apply_amount':'0.00','apply_reason':'合成争议 <script>not executed</script> '+tag,'evidence_refs':[]};body.update(change)
 return call('/aftersales','POST',body,token or people[buyer]['token'],key or secrets.token_hex(16))
def create(order,**change):
 status,r,_=apply(order,**change);check(status==201,'Task dispute created through actual API');return r['data']['aftersale_id']
def review(after,decision='通过',key=None,token=None,**change):
 body={'decision':decision,'reason':'合成管理员结论_'+tag}
 if decision=='通过':body['approve_amount']='0.00'
 body.update(change);return call('/admin/aftersales/'+after+'/review','POST',body,token or admin,key or secrets.token_hex(16))
def cancel(after,key=None,token=None,body=None):return call('/aftersales/'+after+'/cancel','POST',body,token or people[buyer]['token'],key or secrets.token_hex(16))
def untouched():
 return [sql('SELECT * FROM reward_accounts WHERE user_id IN (%s,%s) ORDER BY user_id,reward_type',(buyer,seller),True),sql('SELECT * FROM reward_ledger WHERE user_id IN (%s,%s) ORDER BY ledger_id',(buyer,seller),True),sql('SELECT * FROM task_settlement WHERE payer_id=%s OR payee_id=%s',(buyer,seller),True)]
def audit_count(after):return sql("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='售后' AND object_id=%s AND action_name='审核任务争议'",(after,))
def upload(uid):
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',5,5,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x66\x88\xbb'*5)*5))+chunk(b'IEND',b'')
 boundary='Campus'+secrets.token_hex(10)
 data=f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nAFTERSALE\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+image+f'\r\n--{boundary}--\r\n'.encode()
 status,r,_=call('/files','POST',token=people[uid]['token'],key=secrets.token_hex(16),raw=data,content_type='multipart/form-data; boundary='+boundary);check(status==201,'Real uploaded/scanned aftersale evidence');return r['data']
try:
 admin=login(c['admin_username'],c['admin_password']);buyer=user('publisher');seller=user('taker');outsider=user('other');temporary_admin=user('admin')
 sql("UPDATE users SET role='ADMIN' WHERE user_id=%s",(temporary_admin,));ungranted=login(people[temporary_admin]['name'],people[temporary_admin]['password'])
 before=untouched();order,task=task_order();key=secrets.token_hex(16)
 check(call('/aftersales','POST',{'order_id':order})[0]==401,'Anonymous create denied before validation')
 for amount in ['1.00',0,'0','00.00','-1.00','0.000']:
  check(apply(order,apply_amount=amount)[0]==400,'Invalid or nonzero task amount rejected '+str(amount))
 check(apply(order,token=people[seller]['token'])[0]==404,'Taker cannot apply as order payer')
 check(apply(order,token=people[outsider]['token'])[0]==404,'Unrelated party cannot create case')
 check(apply(order,apply_reason=' ')[0]==400 and apply(order,unknown=True)[0]==400,'Blank reason and unknown field rejected')
 check(call('/aftersales','POST',{'order_id':order,'apply_amount':'0.00','apply_reason':'x','evidence_refs':[]},people[buyer]['token'])[0]==400,'Creation requires idempotency key')
 file=upload(buyer);status,r,headers=apply(order,key=key,evidence_refs=[file['file_ref']]);check(status==201,'Actual case creation binds uploaded evidence');after=r['data']['aftersale_id']
 check('no-store' in headers.get('Cache-Control','') and r['data']['apply_amount']=='0.00' and r['data']['aftersale_status']=='待审核','Contracted zero-money response and no-store')
 check(after.startswith('AFTER'+datetime.datetime.now(datetime.timezone.utc).strftime('%y%m%d')) and len(after)==19,'AFTER number uses UTC date and persistent eight-digit sequence')
 check(sql('SELECT original_order_status FROM aftersale_order_context WHERE aftersale_id=%s',(after,))=='进行中','Application-time original state stored in same transaction')
 check(apply(order,key=key,evidence_refs=[file['file_ref']])[1]['data']==r['data'] and apply(order,key=key,apply_reason='changed')[0]==409,'Creation exact replay and changed-body conflict')
 check(apply(order)[0]==409,'Only one pending case per order')
 for token in [people[buyer]['token'],people[seller]['token']]:check(call('/aftersales/'+after,token=token)[0]==200,'Actual order party can read detail')
 for token in [people[outsider]['token'],admin]:check(call('/aftersales/'+after,token=token)[0]==404,'Unrelated user/admin cannot use member detail to bypass ownership')
 check(call('/aftersales/'+after)[0]==401 and call('/me/aftersales')[0]==401,'Anonymous detail and owner list denied')
 check(call('/me/aftersales',token=people[buyer]['token'])[1]['data']['total']=='1' and call('/me/aftersales',token=people[seller]['token'])[1]['data']['total']=='0','Own-case list excludes other participant applications')
 check(call('/me/aftersales?page_size=101',token=people[buyer]['token'])[0]==400,'Owner pagination bounded')
 for uid in [buyer,seller]:check(call('/files/'+file['file_id']+'/access',token=people[uid]['token'])[0]==200,'Bound evidence remains accessible to order party')
 check(call('/files/'+file['file_id']+'/access',token=people[outsider]['token'])[0]==403,'Bound evidence rejects outsider')
 check(cancel(after,token=people[seller]['token'])[0]==404 and cancel(after,body={})[0]==400,'Only applicant may cancel and body injection rejected')
 check(review(after,token=people[seller]['token'])[0]==403 and review(after,token=ungranted)[0]==403,'Review requires ADMIN and ORDERS duty')
 check(review(after,approve_amount='1.00')[0]==400 and review(after,'驳回',approve_amount='0.00')[0]==400,'Nonzero approval and reject-with-amount rejected')
 check(review(after,reason=' ')[0]==400,'Review reason required')
 sql("UPDATE user_orders SET order_status='待验收' WHERE order_id=%s",(order,));sql("UPDATE RewardTasks SET task_status='待验收' WHERE reward_task_id=%s",(task,))
 key=secrets.token_hex(16);status,r,_=review(after,key=key)
 check(status==200 and r['data']['aftersale_status']=='已完成' and r['data']['approve_amount']=='0.00' and r['data']['complete_time'] is not None,'Zero-money approval actually completes case')
 check(review(after,key=key)[1]['data']==r['data'] and review(after)[0]==409 and review(after,'驳回',key)[0]==409 and audit_count(after)==1,'Review replay is stable and alternate decisions cannot duplicate audit')
 check(sql('SELECT order_status FROM user_orders WHERE order_id=%s',(order,))=='待验收' and sql('SELECT task_status FROM RewardTasks WHERE reward_task_id=%s',(task,))=='待验收','Review does not revert natural task/order progression')
 check(sql('SELECT original_order_status FROM aftersale_order_context WHERE aftersale_id=%s',(after,))=='进行中' and sql('SELECT refund_transaction_no FROM order_aftersales WHERE aftersale_id=%s',(after,)) is None,'Original context is immutable and no refund transaction invented')
 check(cancel(after)[0]==409,'Completed case cannot be withdrawn')
 rejected=create(order);status,r,_=review(rejected,'驳回');check(status==200 and r['data']['aftersale_status']=='已驳回' and r['data']['approve_amount'] is None and r['data']['complete_time'] is None,'Rejection records decision without completing refund')
 withdrawn=create(order);key=secrets.token_hex(16);status,r,_=cancel(withdrawn,key)
 check(status==200 and r['data']['aftersale_status']=='已取消' and cancel(withdrawn,key)[1]['data']==r['data'] and cancel(withdrawn)[0]==409 and review(withdrawn)[0]==409,'Withdrawal replay is stable and excludes later review')
 other_file=upload(seller);key=secrets.token_hex(16);count=sql('SELECT COUNT(*) FROM order_aftersales')
 check(apply(order,key=key,evidence_refs=[other_file['file_ref']])[0]==403 and sql('SELECT COUNT(*) FROM order_aftersales')==count and sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Wrong evidence owner rolls back case/context/number/idempotency')
 concurrent_order,_=task_order()
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:apply(concurrent_order),range(2)))
 check(sorted(s for s,_,_ in results)==[201,409] and sql("SELECT COUNT(*) FROM order_aftersales WHERE order_id=%s AND aftersale_status='待审核'",(concurrent_order,))==1,'Concurrent creates establish one active case')
 race=create(order)
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda action:review(race) if action else cancel(race),[True,False]))
 check(sorted(s for s,_,_ in results)==[200,409] and audit_count(race)<=1,'Review versus withdrawal has one winner')
 rollback_order,_=task_order();trigger='dispute_context_'+tag;key=secrets.token_hex(16);sequence='AFTER:'+datetime.datetime.now(datetime.timezone.utc).strftime('%y%m%d');old_counter=sql('SELECT counter_value FROM business_number_sequences WHERE number_type=%s',(sequence,))
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON aftersale_order_context FOR EACH ROW BEGIN IF NEW.order_id='"+rollback_order+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic context failure'; END IF; END")
  check(apply(rollback_order,key=key)[0]==503 and sql('SELECT COUNT(*) FROM order_aftersales WHERE order_id=%s',(rollback_order,))==0 and sql('SELECT counter_value FROM business_number_sequences WHERE number_type=%s',(sequence,))==old_counter and sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Context failure rolls back case, sequence and replay record')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 status,r,_=apply(rollback_order,key=key);check(status==201,'Same creation key succeeds after real database fault repaired');rollback=r['data']['aftersale_id'];trigger='dispute_audit_'+tag;key=secrets.token_hex(16)
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='售后' AND NEW.object_id='"+rollback+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic dispute audit failure'; END IF; END")
  check(review(rollback,key=key)[0]==503 and sql('SELECT aftersale_status FROM order_aftersales WHERE aftersale_id=%s',(rollback,))=='待审核' and audit_count(rollback)==0 and sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Actual audit failure rolls back review and idempotency')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 check(review(rollback,key=key)[0]==200,'Same review key recovers after audit repair')
 legacy_order,_=task_order();legacy=identifier('AFTER');sql("INSERT INTO order_aftersales(aftersale_id,order_id,user_id,apply_amount,apply_reason) VALUES(%s,%s,%s,0,'合成旧工单无上下文')",(legacy,legacy_order,buyer))
 check(review(legacy)[0]==409 and cancel(legacy)[0]==409,'Legacy task case without original context cannot be inferred or mutated')
 check(untouched()==before,'All dispute operations preserve reward accounts, reward ledger and settlements')
 approve_order,approve_task=task_order();approve_case=create(approve_order);reject_order,reject_task=task_order();reject_case=create(reject_order)
 (out/'fixtures.json').write_text(json.dumps({'approve':approve_case,'reject':reject_case,'approve_order':approve_order,'reject_order':reject_order,'approve_task':approve_task,'reject_task':reject_task,'tag':tag},ensure_ascii=False),encoding='utf-8')
 finished=True;print(json.dumps({'status':'PASS','checks':len(checks),'scope':'actual task dispute APIs, isolated MySQL, scanned evidence; product refunds not enabled'}))
finally:
 try:
  if temporary_admin:
   sql("UPDATE users SET role='USER',auth_version=auth_version+1 WHERE user_id=%s",(temporary_admin,));sql('UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE user_id=%s',(temporary_admin,))
 finally:
  (out/'http-acceptance.json').write_text(json.dumps({'status':'PASS' if finished else 'INCOMPLETE','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8');db.close()
