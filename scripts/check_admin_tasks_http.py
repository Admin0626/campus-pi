"""Real task admin HTTP checks with explicitly synthetic, transactionally funded fixtures."""
from pathlib import Path
from decimal import Decimal
import argparse,concurrent.futures,datetime,http.client,json,secrets,struct,sys,urllib.error,urllib.parse,urllib.request,zlib
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--cash-only',action='store_true');args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
 assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute("SET time_zone='+00:00'")
out=ROOT/'.local/auth-dev/admin-tasks';out.mkdir(parents=True,exist_ok=True)
report=out/('cash-http-acceptance.json' if args.cash_only else 'http-acceptance.json');report.write_text('{"status":"RUNNING"}')
checks=[];finished=False;people={};files={};tag=secrets.token_hex(4)
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def sql(query,params=(),rows=False):
 with db.cursor() as cur:
  cur.execute(query,params)
  if cur.description:return cur.fetchall() if rows else cur.fetchone()[0]
  return cur.lastrowid
def call(route,method='GET',body=None,token=None,key=None,raw=None,content_type=None):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 data=raw if raw is not None else json.dumps(body).encode() if body is not None else None
 if content_type:headers['Content-Type']=content_type
 elif body is not None:headers['Content-Type']='application/json'
 request=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,method=method,headers=headers,data=data)
 try:response=urllib.request.urlopen(request,timeout=45)
 except urllib.error.HTTPError as error:response=error
 return response.status,json.loads(response.read())
def login(name,password):
 status,result=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Real login succeeds');return result['data']['access_token']
def user(label):
 name='task_'+label+'_'+tag;password=secrets.token_urlsafe(20)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16));check(status==201,'Create synthetic '+label)
 uid=r['data']['user_id'];token=login(name,password);people[uid]={'token':token,'name':name}
 # Only these new synthetic accounts receive fixture qualifications and a controlled test allocation.
 sql("UPDATE users SET identity_verification='已通过',campus_verification='已通过',campus='测试校区',student_no=%s WHERE user_id=%s",('TASK'+uid,uid))
 sql("INSERT INTO approved_campus_registry VALUES('测试校区',%s,%s)",('TASK'+uid,uid))
 sql('UPDATE reward_accounts SET available_amount=100000 WHERE user_id=%s',(uid,))
 return uid
def balance(uid,type='积分'):return sql('SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=%s AND reward_type=%s',(uid,type),True)[0]
def task(owner,label,type='积分',amount='20.00'):
 # This fixture models the existing publishing transaction, not an implemented API036.
 db.begin()
 try:
  id=sql("INSERT INTO RewardTasks(publisher_id,task_name,task_description,campus,reward_amount,reward_type) VALUES(%s,%s,'按要求完成并提供证据','测试校区',%s,%s)",(owner,label+'_'+tag,amount,type))
  sql('UPDATE reward_accounts SET available_amount=available_amount-%s,frozen_amount=frozen_amount+%s WHERE user_id=%s AND reward_type=%s',(amount,amount,owner,type))
  sql("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(%s,%s,%s,%s,'冻结',%s,%s)",('FREEZE:'+str(id),id,owner,type,-Decimal(amount),Decimal(amount)))
  db.commit();return str(id)
 except:db.rollback();raise
def app(task,uid):return str(sql('INSERT INTO task_applications(reward_task_id,applicant_id) VALUES(%s,%s)',(task,uid)))
def action(route,decision='通过',key=None,reason='合成任务审核'):
 return call(route,'POST',{'reason':reason} if decision is None else {'decision':decision,'reason':reason},admin,key or secrets.token_hex(16))
def publication(id,decision='通过',key=None,reason='合成发布审核'):return action('/admin/tasks/'+id+'/publication-review',decision,key,reason)
def assign(id,decision='通过',key=None):return action('/admin/task-applications/'+id+'/review',decision,key)
def review(id,decision='通过',key=None,reason='合成验收'):return action('/admin/task-proofs/'+id+'/review',decision,key,reason)
def close(id,key=None):return action('/admin/tasks/'+id+'/close',None,key)
def evidence(uid):
 if uid in files:return files[uid]
 def chunk(type,data):return struct.pack('>I',len(data))+type+data+struct.pack('>I',zlib.crc32(type+data)&0xffffffff)
 image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',6,6,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x22\x99\x77'*6)*6))+chunk(b'IEND',b'')
 boundary='Campus'+secrets.token_hex(10)
 raw=f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\nTASK_PROOF\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+image+f'\r\n--{boundary}--\r\n'.encode()
 status,r=call('/files','POST',token=people[uid]['token'],key=secrets.token_hex(16),raw=raw,content_type='multipart/form-data; boundary='+boundary)
 check(status==201,'Task proof image passes real file scanning');files[uid]=r['data'];return files[uid]
def proof(task_id,uid):
 material=evidence(uid);db.begin()
 try:
  state,taker=sql('SELECT task_status,taker_id FROM RewardTasks WHERE reward_task_id=%s FOR UPDATE',(task_id,),True)[0]
  assert state=='进行中' and str(taker)==uid
  version=sql('SELECT COALESCE(MAX(submission_no),0)+1 FROM task_proofs WHERE reward_task_id=%s',(task_id,))
  id=sql("INSERT INTO task_proofs(reward_task_id,submitter_id,submission_no,proof_text,evidence_urls) VALUES(%s,%s,%s,'<script>not executed</script> 合成完成凭证',%s)",(task_id,uid,version,json.dumps([material['file_ref']])))
  sql("INSERT INTO business_file_bindings VALUES(%s,'TASK_PROOF',%s,UTC_TIMESTAMP(3))",(material['file_id'],str(id)))
  sql("UPDATE RewardTasks SET task_status='待验收',submitted_at=UTC_TIMESTAMP(3),version=version+1 WHERE reward_task_id=%s",(task_id,))
  sql("UPDATE user_orders SET order_status='待验收' WHERE reward_task_id=%s",(task_id,));db.commit();return str(id)
 except:db.rollback();raise
def prepared(owner,taker,label):
 id=task(owner,label);check(publication(id)[0]==200,'Approve funded fixture '+label);application=app(id,taker);check(assign(application)[0]==200,'Assign fixture '+label);return id
def state(id):return sql('SELECT task_status FROM RewardTasks WHERE reward_task_id=%s',(id,))
def order_state(id):return sql('SELECT order_status FROM user_orders WHERE reward_task_id=%s',(id,))
def persisted_counts(id):
 return tuple(sql('SELECT COUNT(*) FROM '+table+' WHERE reward_task_id=%s',(id,)) for table in ['task_settlement','reward_ledger','task_audit_log','user_orders'])
def response_loss(route,body,key):
 connection=http.client.HTTPConnection('127.0.0.1',8080,timeout=20)
 connection.request('POST','/api/v1'+route,json.dumps(body),{'Content-Type':'application/json','Authorization':'Bearer '+admin,'Idempotency-Key':key})
 response=connection.getresponse();check(response.status==200,'Server committed before client discards response body');connection.close()
 return call(route,'POST',body,admin,key)
try:
 admin=login(c['admin_username'],c['admin_password']);owner=user('owner');taker=user('taker');other=user('other');ordinary=people[other]['token']
 if args.cash_only:
  id=task(owner,'cash-positive','现金','20.50');check(publication(id)[0]==200,'Explicit synthetic cash flag permits ledger-only review')
  application=app(id,taker);check(assign(application)[0]==200,'Assign synthetic cash task');p=proof(id,taker);before=balance(taker,'现金')
  check(review(p)[0]==200 and balance(taker,'现金')[0]==before[0]+Decimal('20.50') and order_state(id)=='已完成','Synthetic cash amount settles exactly without external payment claim')
  check(sql('SELECT payment_transaction_no FROM user_orders WHERE reward_task_id=%s',(id,)) is None,'No fake external payment transaction is generated')
 else:
  for route in ['/admin/tasks','/admin/task-applications','/admin/task-proofs','/me/reward-accounts','/me/reward-ledger']:
   check(call(route)[0]==401,'Anonymous access rejected '+route)
  for route in ['/admin/tasks','/admin/task-applications','/admin/task-proofs']:check(call(route,token=ordinary)[0]==403,'Ordinary user rejected '+route)
  for route in ['/admin/tasks?page_size=101','/admin/tasks?task_status=invalid','/admin/task-applications?reward_task_id=0','/admin/task-proofs?audit_status=invalid']:
   check(call(route,token=admin)[0]==400,'Invalid management filter rejected '+route)
  sql('DELETE FROM reward_accounts WHERE user_id=%s',(other,))
  status,r=call('/me/reward-accounts',token=ordinary)
  check(status==200 and len(r['data']['items'])==2 and all(x['available_amount']=='0.00' and x['frozen_amount']=='0.00' for x in r['data']['items']) and sql('SELECT COUNT(*) FROM reward_accounts WHERE user_id=%s',(other,))==0,'Missing reward accounts read as zero without creating balances or ledger')
  sql("INSERT INTO reward_accounts(user_id,reward_type,available_amount) VALUES(%s,'积分',100000),(%s,'现金',100000)",(other,other))
  pending=task(owner,'publication');check(call('/tasks/'+pending)[0]==404 and call('/tasks/'+pending,token=people[owner]['token'])[0]==200,'Pending task is private to actual parties/admin')
  for route in ['/tasks/'+pending+'/proofs','/tasks/'+pending+'/logs']:
   check(call(route)[0]==401 and call(route,token=ordinary)[0]==404,'Proof/log access is restricted '+route)
  key=secrets.token_hex(16);before=balance(owner)
  status,r=publication(pending,key=key);check(status==200 and r['data']['task_status']=='待接取' and r['data']['reward_amount']=='20.00' and r['data']['version']==1,'Publication approval persists exact task DTO and version')
  check(balance(owner)==before and publication(pending,key=key)[0]==200 and publication(pending)[0]==409,'Publication approval preserves freeze and replay does not change state twice')
  check(publication(pending,key=key,reason='changed')[0]==409,'Same publication key with different reason conflicts')
  check(call('/tasks/'+pending)[0]==200,'Approved task is public')
  reject=task(owner,'reject');before=balance(owner);key=secrets.token_hex(16)
  check(publication(reject,'驳回',key)[0]==200 and state(reject)=='已驳回' and balance(owner)==(before[0]+20,before[1]-20),'Publication rejection releases only its frozen reward')
  check(publication(reject,'驳回',key)[0]==200 and sql("SELECT COUNT(*) FROM reward_ledger WHERE event_key=%s",('RELEASE:'+reject,))==1,'Publication rejection replay cannot release twice')
  ineligible=task(owner,'ineligible');sql("UPDATE users SET campus_verification='待审核' WHERE user_id=%s",(owner,))
  try:check(publication(ineligible)[0]==409 and state(ineligible)=='待审核','Publisher campus change blocks publication approval')
  finally:sql("UPDATE users SET campus_verification='已通过' WHERE user_id=%s",(owner,))
  unbacked=task(owner,'unbacked');freeze_row=sql('SELECT ledger_id,event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta,created_at FROM reward_ledger WHERE event_key=%s',('FREEZE:'+unbacked,),True)[0]
  try:
   sql('DELETE FROM reward_ledger WHERE event_key=%s',('FREEZE:'+unbacked,))
   check(publication(unbacked,'驳回')[0]==409 and state(unbacked)=='待审核','Aggregate frozen balance cannot substitute for missing per-task freeze')
  finally:sql('INSERT INTO reward_ledger(ledger_id,event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta,created_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)',freeze_row)
  cash=task(owner,'cash-disabled','现金','20.50');check(publication(cash)[0]==501 and state(cash)=='待审核','Cash reward mutation is closed by default without synthetic flag')
  application=app(pending,other);before=balance(owner)
  check(assign(application,'驳回')[0]==200 and state(pending)=='待接取' and sql('SELECT COUNT(*) FROM user_orders WHERE reward_task_id=%s',(pending,))==0 and balance(owner)==before,'Application rejection changes only the application')
  first,second=app(pending,taker),app(pending,owner)
  check(assign(second)[0]==409,'Task cannot be assigned to publisher themselves')
  sql("UPDATE users SET campus='另一个校区' WHERE user_id=%s",(taker,))
  try:check(assign(first)[0]==409 and state(pending)=='待接取','Assignment rechecks applicant current campus')
  finally:sql("UPDATE users SET campus='测试校区' WHERE user_id=%s",(taker,))
  competing=task(owner,'competition');check(publication(competing)[0]==200,'Approve competition task');a,b=app(competing,taker),app(competing,other)
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(assign,[a,b]))
  check(sorted(s for s,_ in results)==[200,409],'Concurrent approvals choose exactly one applicant')
  check(sql('SELECT COUNT(*) FROM user_orders WHERE reward_task_id=%s',(competing,))==1 and sql("SELECT COUNT(*) FROM task_applications WHERE reward_task_id=%s AND application_status='已通过'",(competing,))==1 and sql("SELECT COUNT(*) FROM task_applications WHERE reward_task_id=%s AND application_status='已驳回'",(competing,))==1,'Assignment creates one order and rejects other pending applicants atomically')
  key=secrets.token_hex(16);status,r=assign(first,key=key);check(status==200 and r['data']['application']['application_status']=='已通过' and r['data']['order_id']==r['data']['task']['order_id'],'Assignment result follows its contract')
  order=r['data']['order_id'];check(len(order)==19 and order.startswith('ORDER') and order[5:].isdigit(),'Durable counter generates a valid order identifier')
  check(assign(first,key=key)[1]['data']['order_id']==order and sql('SELECT COUNT(*) FROM user_orders WHERE reward_task_id=%s',(pending,))==1,'Assignment retry reuses its original order')
  public=call('/tasks/'+pending)[1]['data'];check(public['taker'] is None and public['order_id'] is None,'Public task DTO hides current taker and order')
  check(call('/tasks/'+pending,token=people[taker]['token'])[1]['data']['order_id']==order,'Actual taker can read their order relation')
  check(close(pending)[0]==409,'Ongoing task cannot be directly closed')
  p=proof(pending,taker);check(close(pending)[0]==409,'Pending acceptance task cannot be directly closed')
  before=balance(owner),balance(taker)
  status,r=review(p,'补充凭证');check(status==200 and state(pending)=='进行中' and order_state(pending)=='进行中' and sql('SELECT audit_status FROM task_proofs WHERE proof_id=%s',(p,))=='补充凭证' and (balance(owner),balance(taker))==before,'Supplement request retains proof version and frozen reward without settlement')
  p2=proof(pending,taker);check(sql('SELECT submission_no FROM task_proofs WHERE proof_id=%s',(p2,))==2 and review(p)[0]==409,'Only the current pending proof is reviewable')
  sql("UPDATE task_proofs SET audit_status='待审核' WHERE proof_id=%s",(p,))
  try:check(review(p)[0]==409,'An old pending-looking proof cannot bypass the latest-version check')
  finally:sql("UPDATE task_proofs SET audit_status='补充凭证' WHERE proof_id=%s",(p,))
  status,r=call('/tasks/'+pending+'/proofs',token=admin);check(status==200 and [x['submission_no'] for x in r['data']['items']]==[2,1],'Proof history keeps descending submission versions')
  before_owner,before_taker=balance(owner),balance(taker);key=secrets.token_hex(16)
  with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(lambda _:review(p2,key=key),range(3)))
  check(all(s==200 for s,_ in results),'Concurrent same-key acceptance returns the same durable result')
  check(balance(owner)==(before_owner[0],before_owner[1]-20) and balance(taker)==(before_taker[0]+20,before_taker[1]),'Acceptance transfers frozen reward exactly once')
  check(sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=%s',(pending,))==1 and sql("SELECT COUNT(*) FROM reward_ledger WHERE reward_task_id=%s AND event_type IN ('支付','收入')",(pending,))==2 and state(pending)=='已完成' and order_state(pending)=='已完成','Settlement, two ledger events, task and order agree')
  check(sql('SELECT actual_amount FROM user_orders WHERE reward_task_id=%s',(pending,))==Decimal('20.00') and review(p2)[0]==409,'Completed task cannot be settled using a new key')
  two_keys=prepared(owner,taker,'two-keys');two_proof=proof(two_keys,taker)
  with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:review(two_proof),range(2)))
  check(sorted(s for s,_ in results)==[200,409] and sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=%s',(two_keys,))==1,'Concurrent different-key acceptance settles once and rejects the loser')
  status,r=call('/me/reward-ledger?reward_type='+urllib.parse.quote('积分'),token=people[taker]['token']);check(status==200 and any(x['reward_task_id']==pending and x['event_type']=='收入' and x['available_delta']=='20.00' for x in r['data']['items']),'Taker reward ledger reflects the real settlement')
  failed=prepared(owner,taker,'failed');p=proof(failed,taker);before=balance(owner),balance(taker)
  check(review(p,'未通过')[0]==200 and state(failed)=='未完成' and order_state(failed)=='未完成' and (balance(owner),balance(taker))==before,'Failed acceptance holds reward pending administrative closure')
  key=secrets.token_hex(16);check(close(failed,key)[0]==200 and state(failed)=='已取消' and order_state(failed)=='已取消' and balance(owner)==(before[0][0]+20,before[0][1]-20),'Closing failed task releases reward and cancels its order')
  check(close(failed,key)[0]==200 and sql("SELECT COUNT(*) FROM reward_ledger WHERE event_key=%s",('RELEASE:'+failed,))==1,'Close replay releases once')
  cancel=task(owner,'cancel');check(publication(cancel)[0]==200,'Approve cancellable task');application=app(cancel,taker)
  check(close(cancel)[0]==200 and sql('SELECT application_status FROM task_applications WHERE application_id=%s',(application,))=='已取消','Closing unassigned task cancels pending applications')
  rollback=prepared(owner,taker,'rollback');p=proof(rollback,taker);before=(balance(owner),balance(taker),persisted_counts(rollback));key=secrets.token_hex(16);abort='abort_'+tag;trigger='task_abort_'+tag
  try:
   sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON task_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic task audit failure'; END IF; END")
   check(review(p,key=key,reason=abort)[0]==503,'Task audit dependency failure rejects acceptance')
   check((balance(owner),balance(taker),persisted_counts(rollback))==before and state(rollback)=='待验收' and order_state(rollback)=='待验收' and sql('SELECT audit_status FROM task_proofs WHERE proof_id=%s',(p,))=='待审核','Audit failure rolls back all reward, settlement, task, proof and order effects')
   check(sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Failed acceptance does not persist success replay')
  finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
  check(review(p,key=key,reason=abort)[0]==200 and sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=%s',(rollback,))==1,'Same failed request key safely succeeds once dependency is restored')
  assignment_rollback=task(owner,'assignment-rollback');publication(assignment_rollback);application=app(assignment_rollback,taker);counter_before=sql("SELECT counter_value FROM business_number_sequences WHERE number_type='ORDER'");trigger='task_assign_abort_'+tag
  try:
   sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON task_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic assignment audit failure'; END IF; END")
   check(action('/admin/task-applications/'+application+'/review',reason=abort)[0]==503,'Assignment audit dependency failure is surfaced')
   check(state(assignment_rollback)=='待接取' and sql('SELECT application_status FROM task_applications WHERE application_id=%s',(application,))=='待审核' and sql('SELECT COUNT(*) FROM user_orders WHERE reward_task_id=%s',(assignment_rollback,))==0 and sql("SELECT counter_value FROM business_number_sequences WHERE number_type='ORDER'")==counter_before,'Assignment failure rolls back applicant, order, task and number counter')
  finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
  insufficient=prepared(owner,taker,'insufficient-frozen');p=proof(insufficient,taker);frozen_before=balance(owner)[1]
  try:
   sql("UPDATE reward_accounts SET frozen_amount=0 WHERE user_id=%s AND reward_type='积分'",(owner,))
   check(review(p)[0]==409 and state(insufficient)=='待验收' and sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=%s',(insufficient,))==0,'Insufficient frozen balance rejects settlement without partial effects')
  finally:sql("UPDATE reward_accounts SET frozen_amount=%s WHERE user_id=%s AND reward_type='积分'",(frozen_before,owner))
  lost=prepared(owner,taker,'response-loss');p=proof(lost,taker);key=secrets.token_hex(16)
  status,r=response_loss('/admin/task-proofs/'+p+'/review',{'decision':'通过','reason':'丢失响应恢复'},key)
  check(status==200 and r['data']['task_status']=='已完成' and sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=%s',(lost,))==1,'Discarded response body recovers original result without another payment')
  exhausted=task(owner,'counter');check(publication(exhausted)[0]==200,'Approve counter-boundary fixture');application=app(exhausted,taker);counter=sql("SELECT counter_value FROM business_number_sequences WHERE number_type='ORDER'")
  try:
   sql("UPDATE business_number_sequences SET counter_value=99999999999999 WHERE number_type='ORDER'")
   check(assign(application)[0]==503 and state(exhausted)=='待接取' and sql('SELECT COUNT(*) FROM user_orders WHERE reward_task_id=%s',(exhausted,))==0,'Order number exhaustion rolls back assignment instead of generating an invalid ID')
  finally:sql("UPDATE business_number_sequences SET counter_value=%s WHERE number_type='ORDER'",(counter,))
  check(call('/admin/tasks/'+exhausted+'/publication-review','POST',{'decision':'通过','reason':'x','operator_id':owner},admin,secrets.token_hex(16))[0]==400,'Client cannot supply actor identity')
  check(publication(exhausted,reason=' ')[0]==400,'Task mutation requires a nonblank reason')
  # Fresh, untouched fixtures for the browser. Only IDs and display labels are written.
  browser_review=task(owner,'浏览器发布');browser_close=task(owner,'浏览器关闭');browser_assign=task(owner,'浏览器分配');publication(browser_assign);browser_application=app(browser_assign,taker)
  browser_accept=prepared(owner,taker,'浏览器验收');browser_proof=proof(browser_accept,taker)
  (out/'fixtures.json').write_text(json.dumps({'tag':tag,'review_task':browser_review,'close_task':browser_close,'assignment_task':browser_assign,'application_id':browser_application,'acceptance_task':browser_accept,'proof_id':browser_proof,'owner_id':owner,'taker_id':taker,'file_ref':files[taker]['file_ref']},ensure_ascii=False),encoding='utf-8')
 finished=True;print(json.dumps({'status':'PASS','checks':len(checks),'cash_only':args.cash_only,'scope':'real HTTP + isolated MySQL + actual scanned task proof'}))
finally:
 report.write_text(json.dumps({'status':'PASS' if finished else 'INCOMPLETE','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks,'cash_only':args.cash_only},ensure_ascii=False,indent=2),encoding='utf-8');db.close()
