"""Real admin order/report HTTP checks against this task's isolated MySQL only."""
from pathlib import Path
from decimal import Decimal
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
out=ROOT/'.local/auth-dev/admin-trade';out.mkdir(parents=True,exist_ok=True)
checks=[];finished=False;ungranted_id=None;tag=secrets.token_hex(4);people={}
(out/'http-acceptance.json').write_text('{"status":"RUNNING"}')
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def sql(query,params=(),rows=False):
 with db.cursor() as cur:
  cur.execute(query,params)
  if cur.description:return cur.fetchall() if rows else cur.fetchone()[0]
  return cur.lastrowid
def request(route,method='GET',body=None,token=None,key=None,raw=None,content_type=None,binary=False):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if key:headers['Idempotency-Key']=key
 data=raw if raw is not None else json.dumps(body).encode() if body is not None else None
 if content_type:headers['Content-Type']=content_type
 elif body is not None:headers['Content-Type']='application/json'
 url='http://127.0.0.1:8080'+(route if route.startswith('/file-content/') else '/api/v1'+route)
 req=urllib.request.Request(url,method=method,headers=headers,data=data)
 try:r=urllib.request.urlopen(req,timeout=45)
 except urllib.error.HTTPError as e:r=e
 data=r.read();return r.status,data if binary else json.loads(data),dict(r.headers)
def call(*args,**kwargs):return request(*args,**kwargs)[:2]

def paged_items(route,token):
 items=[]
 for page in range(1,101):
  separator='&' if '?' in route else '?'
  status,result=call(route+separator+'page_size=100&page='+str(page),token=token)
  if status!=200:raise AssertionError('Paged fixture lookup failed')
  batch=result['data']['items'];items.extend(batch)
  if not batch or len(items)>=int(result['data']['total']):return items
 raise AssertionError('Paged fixture lookup exceeded local test limit')
def login(name,password):
 status,r=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Real login succeeds');return r['data']['access_token']
def user(label):
 name='trade_'+label+'_'+tag;password=secrets.token_urlsafe(20)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16));check(status==201,'Create synthetic '+label)
 uid=r['data']['user_id'];people[uid]={'name':name,'password':password,'token':login(name,password)};return uid
def identifier(prefix):
 table,column=('user_orders','order_id') if prefix=='ORDER' else ('order_aftersales','aftersale_id')
 while True:
  value=prefix+datetime.datetime.now(datetime.timezone.utc).strftime('%y%m%d')+str(secrets.randbelow(40_000_000)).zfill(8)
  if sql('SELECT COUNT(*) FROM '+table+' WHERE '+column+'=%s',(value,))==0:return value
def product(label,stock=2):return str(sql("INSERT INTO product_info(user_id,category_id,product_name,product_desc,campus,original_price,discount_price,stock_quantity,audit_status,shelf_status) VALUES(%s,%s,%s,'合成库存测试','测试校区',15.00,12.50,%s,'已通过','在售')",(seller,category,label+'_'+tag,stock)))
def order(product_id,quantity=2):
 # Explicit fixture for the unimplemented buyer-side order creation API; no fake payment request.
 db.begin()
 try:
  stock,price=sql('SELECT stock_quantity,discount_price FROM product_info WHERE product_id=%s FOR UPDATE',(product_id,),True)[0];assert stock>=quantity
  id=identifier('ORDER')
  sql("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,shipping_address,order_status) VALUES(%s,'商品',%s,%s,%s,%s,%s,%s,%s,%s,'待支付')",(id,buyer,seller,product_id,'合成订单_'+tag,quantity,price,price*quantity,'合成敏感地址_'+tag))
  sql("UPDATE product_info SET stock_quantity=stock_quantity-%s,shelf_status=IF(stock_quantity=0,'已售出',shelf_status) WHERE product_id=%s",(quantity,product_id));db.commit();return id
 except:db.rollback();raise
def cancel(id,key=None,token=None,reason='合成取消原因'):
 return call('/admin/orders/'+id+'/cancel','POST',{'reason':reason},token or admin,key or secrets.token_hex(16))
def upload(uid,purpose):
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 image=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',5,5,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x66\x88\xbb'*5)*5))+chunk(b'IEND',b'')
 boundary='Campus'+secrets.token_hex(10)
 data=f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+image+f'\r\n--{boundary}--\r\n'.encode()
 status,r=call('/files','POST',token=people[uid]['token'],key=secrets.token_hex(16),raw=data,content_type='multipart/form-data; boundary='+boundary);check(status==201,'Real scanned '+purpose+' fixture');return r['data']
def grant(file,token=None):return call('/files/'+file['file_id']+'/access',token=token or admin)
def aftersale(order_id,file=None):
 id=identifier('AFTER');sql("INSERT INTO order_aftersales(aftersale_id,order_id,user_id,apply_amount,apply_reason,evidence_urls) VALUES(%s,%s,%s,1.00,%s,%s)",(id,order_id,buyer,'合成售后申请_'+tag,json.dumps([file['file_ref']]) if file else None))
 if file:sql("INSERT INTO business_file_bindings VALUES(%s,'AFTERSALE',%s,UTC_TIMESTAMP(3))",(file['file_id'],id))
 return id
def session(a,b):
 low,high=sorted([int(a),int(b)]);sql('INSERT IGNORE INTO t_chat_session(sender_id,receiver_id) VALUES(%s,%s)',(low,high));return sql('SELECT session_id FROM t_chat_session WHERE sender_id=%s AND receiver_id=%s',(low,high))
def message(content,file=None,session_id=None):
 id=sql('INSERT INTO t_private_msg(session_id,sender_id,receiver_id,content_type,content) VALUES(%s,%s,%s,%s,%s)',(session_id or session(buyer,seller),buyer,seller,'图片' if file else '文本',file['file_ref'] if file else content))
 if file:sql("INSERT INTO business_file_bindings VALUES(%s,'MESSAGE',%s,UTC_TIMESTAMP(3))",(file['file_id'],str(id)))
 return str(id)
def report(message_id,reporter=None):return str(sql('INSERT INTO t_msg_report(msg_id,reporter_id,reason) VALUES(%s,%s,%s)',(message_id,reporter or seller,'合成举报原因_'+tag)))
def review(id,decision='成立',key=None,reason='合成举报处置'):
 return call('/admin/message-reports/'+id+'/review','POST',{'decision':decision,'reason':reason},admin,key or secrets.token_hex(16))
def audit_count(type,id,action=None):
 return sql('SELECT COUNT(*) FROM admin_audit_log WHERE object_type=%s AND object_id=%s'+(' AND action_name=%s' if action else ''),(type,id,action) if action else (type,id))
def task_order():
 sql("UPDATE users SET identity_verification='已通过',campus_verification='已通过',campus='测试校区',student_no=CONCAT('TRADE',user_id) WHERE user_id IN (%s,%s)",(buyer,seller))
 for uid in [buyer,seller]:sql("INSERT INTO approved_campus_registry VALUES('测试校区',%s,%s)",('TRADE'+uid,uid))
 sql("UPDATE reward_accounts SET available_amount=100 WHERE user_id=%s AND reward_type='积分'",(buyer,));db.begin()
 try:
  task=sql("INSERT INTO RewardTasks(publisher_id,task_name,task_description,campus,reward_amount,reward_type) VALUES(%s,%s,'合成任务订单映射','测试校区',20,'积分')",(buyer,'商贸任务_'+tag))
  sql("UPDATE reward_accounts SET available_amount=available_amount-20,frozen_amount=frozen_amount+20 WHERE user_id=%s AND reward_type='积分'",(buyer,))
  sql("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(%s,%s,%s,'积分','冻结',-20,20)",('FREEZE:'+str(task),task,buyer));db.commit()
 except:db.rollback();raise
 status,_=call('/admin/tasks/'+str(task)+'/publication-review','POST',{'decision':'通过','reason':'合成订单映射'},admin,secrets.token_hex(16));check(status==200,'Task order fixture uses actual publication review')
 application=sql('INSERT INTO task_applications(reward_task_id,applicant_id) VALUES(%s,%s)',(task,seller))
 status,r=call('/admin/task-applications/'+str(application)+'/review','POST',{'decision':'通过','reason':'合成订单映射'},admin,secrets.token_hex(16));check(status==200,'Task order is created by actual assignment service');return r['data']['order_id'],str(task)

try:
 admin=login(c['admin_username'],c['admin_password']);buyer=user('buyer');seller=user('seller');outsider=user('other');ungranted_id=user('admin')
 sql("UPDATE users SET role='ADMIN' WHERE user_id=%s",(ungranted_id,));ungranted=login(people[ungranted_id]['name'],people[ungranted_id]['password']);ordinary=people[outsider]['token']
 status,r=call('/admin/categories','POST',{'category_name':'商贸验收_'+tag,'parent_category_id':None},admin,secrets.token_hex(16));check(status==201,'Prepare actual category');category=r['data']['category_id']
 p=product('初始商品');o=order(p);address='合成敏感地址_'+tag
 for route in ['/admin/orders','/admin/orders/'+o,'/admin/aftersales','/admin/message-reports']:
  check(call(route)[0]==401,'Anonymous management request rejected '+route)
  check(call(route,token=ordinary)[0]==403,'Ordinary account denied '+route)
 check(call('/admin/orders/'+o,token=ungranted)[0]==403,'ADMIN role alone cannot read sensitive order detail')
 check(call('/admin/message-reports',token=ungranted)[0]==403,'ADMIN role alone cannot browse report queue')
 status,r,headers=request('/admin/orders?order_id='+o,token=ungranted);row=r['data']['items'][0]
 check(status==200 and row['shipping_address'] is None and address not in json.dumps(r,ensure_ascii=False),'List is usable by ordinary ADMIN and never contains delivery address')
 check('no-store' in headers.get('Cache-Control',''),'Order list response is private and noncacheable')
 check(row['unit_price']=='12.50' and row['total_amount']=='25.00' and row['buyer_id']==buyer and row['seller_id']==seller,'Order DTO preserves decimal strings and participant mapping')
 check(not any(k in row for k in ['user_id','payment_transaction_no','password_hash']),'Order response contains only its contracted projection')
 before=audit_count('订单',o)
 status,r=call('/admin/orders/'+o,token=admin);check(status==200 and r['data']['shipping_address']==address and audit_count('订单',o)==before+1,'Privileged detail reveals address only with a sensitive access audit')
 trigger='trade_order_read_'+tag
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='订单' AND NEW.object_id='"+o+"' AND NEW.action_name='读取敏感详情' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private read audit failure'; END IF; END")
  status,r=call('/admin/orders/'+o,token=admin);check(status==503 and address not in json.dumps(r,ensure_ascii=False),'Failed sensitive-read audit cannot return a shipping address')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 for route in ['/admin/orders?page_size=101','/admin/orders?order_type=invalid','/admin/orders?order_status=invalid','/admin/orders?order_id=bad','/admin/aftersales?aftersale_status=bad','/admin/message-reports?audit_status=bad']:
  check(call(route,token=admin)[0]==400,'Invalid filter rejected '+route)
 check(call('/admin/orders/ORDER00000000000000',token=admin)[0]==404,'Unknown syntactically valid order returns 404')
 key=secrets.token_hex(16);status,r=cancel(o,key,ungranted)
 check(status==200 and r['data']['shipping_address'] is None and r['data']['order_status']=='已取消','Cancellation does not bypass the sensitive address duty')
 check(sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==2 and sql('SELECT shelf_status FROM product_info WHERE product_id=%s',(p,))=='在售','Cancel restores reserved stock and original sold-out availability')
 check(cancel(o,key,ungranted)[0]==200 and cancel(o,token=ungranted)[0]==409 and sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==2 and audit_count('订单',o,'取消')==1,'Same-key cancel replays while a new key cannot restore stock twice')
 check(cancel(o,key,ungranted,reason='changed')[0]==409,'Cancellation key cannot be reused with another reason')
 p=product('并发商品');o=order(p)
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda _:cancel(o),range(2)))
 check(sorted(s for s,_ in results)==[200,409] and sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==2 and audit_count('订单',o,'取消')==1,'Concurrent different-key cancellations release inventory once')
 p=product('同键商品');o=order(p);key=secrets.token_hex(16)
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:results=list(pool.map(lambda _:cancel(o,key),range(3)))
 check(all(s==200 for s,_ in results) and sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==2,'Concurrent same-key cancellations replay one result')
 p=product('下架商品');o=order(p);sql("UPDATE product_info SET shelf_status='已下架' WHERE product_id=%s",(p,));check(cancel(o)[0]==200 and sql('SELECT shelf_status FROM product_info WHERE product_id=%s',(p,))=='已下架','Cancel does not relist an explicitly off-shelf product')
 p=product('已付合成商品');paid=order(p);sql("UPDATE user_orders SET order_status='已支付',actual_amount=total_amount,payment_time=UTC_TIMESTAMP(3) WHERE order_id=%s",(paid,))
 check(cancel(paid)[0]==409 and sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==0,'A synthetic paid-state fixture cannot be canceled through unpaid flow')
 p=product('状态不一致商品');o=order(p);sql('UPDATE user_orders SET actual_amount=1 WHERE order_id=%s',(o,))
 try:check(cancel(o)[0]==409 and sql('SELECT order_status FROM user_orders WHERE order_id=%s',(o,))=='待支付','Inconsistent paid amount is rejected without inventory change')
 finally:sql('UPDATE user_orders SET actual_amount=0 WHERE order_id=%s',(o,))
 p=product('库存上限商品');o=order(p);sql('UPDATE product_info SET stock_quantity=2147483647 WHERE product_id=%s',(p,))
 try:check(cancel(o)[0]==409 and sql('SELECT order_status FROM user_orders WHERE order_id=%s',(o,))=='待支付','Inventory restoration overflow is rejected transactionally')
 finally:sql('UPDATE product_info SET stock_quantity=0 WHERE product_id=%s',(p,))
 p=product('回滚商品');o=order(p);key=secrets.token_hex(16);abort='trade_abort_'+tag;trigger='trade_order_abort_'+tag
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic order audit failure'; END IF; END")
  check(cancel(o,key,reason=abort)[0]==503,'Audit dependency failure rejects cancellation')
  check(sql('SELECT order_status FROM user_orders WHERE order_id=%s',(o,))=='待支付' and sql('SELECT stock_quantity FROM product_info WHERE product_id=%s',(p,))==0 and sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Failed cancellation rolls back order, inventory and replay record')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 check(cancel(o,key,reason=abort)[0]==200,'Same failed key succeeds after audit dependency recovery')
 task_o,task_id=task_order();check(cancel(task_o)[0]==409,'Task order cannot use product inventory cancellation')
 status,r=call('/admin/orders?order_id='+task_o,token=admin);task_row=r['data']['items'][0]
 check(task_row['order_type']=='任务' and task_row['buyer_id']==buyer and task_row['seller_id']==seller and task_row['product_id'] is None and task_row['reward_task_id']==task_id and task_row['shipping_address'] is None,'Task order maps publisher/taker and hides nonapplicable shipping data')
 check(task_o.startswith('ORDER'+datetime.datetime.now(datetime.timezone.utc).strftime('%y%m%d')) and len(task_o)==19,'Actual task assignment now generates date plus eight-digit sequence')
 check(call('/admin/orders/'+o+'/cancel','POST',{'reason':' '},admin,secrets.token_hex(16))[0]==400,'Blank cancel reason rejected')
 check(call('/admin/orders/'+o+'/cancel','POST',{'reason':'x','buyer_id':buyer},admin,secrets.token_hex(16))[0]==400,'Cancellation cannot accept client-supplied participants')
 material=upload(buyer,'AFTERSALE');after=aftersale(paid,material);empty_after=aftersale(paid)
 aftersale_rows=paged_items('/admin/aftersales?aftersale_status='+urllib.parse.quote('待审核'),admin);a=next(x for x in aftersale_rows if x['aftersale_id']==after)
 check(a['evidence_refs']==[material['file_ref']] and a['apply_amount']=='1.00' and 'refund_transaction_no' not in a,'Aftersale queue is readable with stable evidence refs and exact amounts')
 check(next(x for x in aftersale_rows if x['aftersale_id']==empty_after)['evidence_refs']==[],'Null legacy aftersale attachments become an empty list')
 check(grant(material)[0]==200 and grant(material,ungranted)[0]==403,'Aftersale material requires order party or ORDERS duty')
 before=sql('SELECT aftersale_status FROM order_aftersales WHERE aftersale_id=%s',(after,)),audit_count('售后',after)
 check(call('/admin/aftersales/'+after+'/review','POST',{'decision':'通过','approve_amount':'1.00','reason':'not enabled'},admin,secrets.token_hex(16))[0]==501,'P1 refund review is explicitly unavailable without a real refund model/channel')
 check((sql('SELECT aftersale_status FROM order_aftersales WHERE aftersale_id=%s',(after,)),audit_count('售后',after))==before,'Disabled refund review does not mutate or fabricate refund success')
 text_msg=message('<script>not executed</script> 合成被举报文本_'+tag);text_case=report(text_msg);hidden=message('UNREPORTED_PRIVATE_'+tag)
 status,r=call('/admin/message-reports',token=admin)
 check(status==200 and all('content' not in x and 'session_id' not in x for x in r['data']['items']) and 'UNREPORTED_PRIVATE_'+tag not in json.dumps(r,ensure_ascii=False),'Report queue does not include private message bodies or entire conversations')
 before=audit_count('举报',text_case);status,r=call('/admin/message-reports/'+text_case+'?msg_id='+hidden,token=admin)
 check(status==200 and r['data']['message']['msg_id']==text_msg and set(r['data'])=={'report','message'} and audit_count('举报',text_case)==before+1,'Evidence endpoint returns one case-linked message and audits access')
 trigger='trade_report_read_'+tag
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.object_type='举报' AND NEW.object_id='"+text_case+"' AND NEW.action_name='读取案件证据' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic evidence read audit failure'; END IF; END")
  status,failed=call('/admin/message-reports/'+text_case,token=admin);check(status==503 and '合成被举报文本_'+tag not in json.dumps(failed,ensure_ascii=False),'Failed evidence-read audit returns no private message content')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 check('UNREPORTED_PRIVATE_'+tag not in json.dumps(r,ensure_ascii=False),'Client cannot select an adjacent unreported message through query parameters')
 check(call('/admin/message-reports/'+text_case,token=ungranted)[0]==403 and call('/admin/message-reports/'+text_case+'/review','POST',{'decision':'成立','reason':'x'},ungranted,secrets.token_hex(16))[0]==403,'UngrantED ADMIN cannot read or decide a report')
 check(call('/admin/messages',token=admin)[0]==404,'No full-site private message browsing endpoint was added')
 sql('UPDATE t_private_msg SET sender_deleted=1,receiver_deleted=1 WHERE msg_id=%s',(text_msg,));check(call('/admin/message-reports/'+text_case,token=admin)[0]==200,'Legitimate report evidence is retained despite participant-side deletion')
 key=secrets.token_hex(16);status,r=review(text_case,key=key);check(status==200 and r['data']['audit_status']=='成立' and sql('SELECT account_status FROM users WHERE user_id=%s',(buyer,))=='ACTIVE','A founded report records a decision without automatically banning its sender')
 check(review(text_case,key=key)[0]==200 and review(text_case)[0]==409 and audit_count('举报',text_case,'处理')==1,'Report decision replay writes one audit and rejects new-key duplicate handling')
 check(review(text_case,'不成立',key)[0]==409,'Report idempotency rejects an altered decision')
 image_file=upload(buyer,'CHAT_IMAGE');image_msg=message('',image_file);image_case=report(image_msg)
 before=audit_count('文件',image_file['file_id']);status,r=grant(image_file);check(status==200 and request(urllib.parse.urlsplit(r['data']['url']).path,binary=True)[0]==200 and audit_count('文件',image_file['file_id'])==before+2,'Case-linked private image grant and binary read are separately audited')
 unrelated_file=upload(buyer,'CHAT_IMAGE');message('',unrelated_file);check(grant(unrelated_file)[0]==403,'Reporting duty cannot traverse other images in the same conversation')
 bad_file=upload(buyer,'CHAT_IMAGE');bad_msg=message('',bad_file,session(buyer,outsider));bad_case=report(bad_msg)
 try:check(call('/admin/message-reports/'+bad_case,token=admin)[0]==404 and review(bad_case)[0]==404 and grant(bad_file)[0]==403,'Mismatched conversation membership cannot grant evidence or file access')
 finally:sql('UPDATE t_private_msg SET session_id=%s WHERE msg_id=%s',(session(buyer,seller),bad_msg))
 outsider_file=upload(buyer,'CHAT_IMAGE');outsider_msg=message('',outsider_file);outsider_case=report(outsider_msg,outsider)
 try:check(call('/admin/message-reports/'+outsider_case,token=admin)[0]==404 and grant(outsider_file)[0]==403,'A nonparticipant forged reporter does not authorize private evidence')
 finally:sql('UPDATE t_msg_report SET reporter_id=%s WHERE report_id=%s',(seller,outsider_case))
 concurrent_case=report(message('并发举报_'+tag))
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda decision:review(concurrent_case,decision),['成立','不成立']))
 check(sorted(s for s,_ in results)==[200,409] and audit_count('举报',concurrent_case,'处理')==1,'Concurrent different decisions handle a report exactly once')
 rollback_case=report(message('回滚举报_'+tag));trigger='trade_report_abort_'+tag;key=secrets.token_hex(16)
 try:
  sql("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic report audit failure'; END IF; END")
  check(review(rollback_case,key=key,reason=abort)[0]==503 and sql('SELECT audit_status FROM t_msg_report WHERE report_id=%s',(rollback_case,))=='待审核' and sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(key,))==0,'Report audit failure rolls back decision and idempotency result')
 finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
 check(review(rollback_case,key=key,reason=abort)[0]==200,'Report review recovers using the same key after audit repair')
 check(review(image_case,reason=' ')[0]==400,'Report decision requires a nonblank reason')
 # Fresh browser fixtures have not been mutated by the preceding checks.
 browser_product=product('浏览器订单');browser_order=order(browser_product)
 browser_text=report(message('<script>not executed</script> 浏览器举报证据_'+tag));browser_image=report(message('',upload(buyer,'CHAT_IMAGE')))
 (out/'fixtures.json').write_text(json.dumps({'tag':tag,'order_id':browser_order,'product_id':browser_product,'task_order':task_o,'task_id':task_id,'aftersale_id':after,'text_report':browser_text,'image_report':browser_image,'address':address},ensure_ascii=False),encoding='utf-8')
 finished=True;print(json.dumps({'status':'PASS','checks':len(checks),'scope':'real HTTP + isolated MySQL + scanned private evidence; P1 remains disabled'}))
finally:
 try:
  if ungranted_id is not None:
   sql("UPDATE users SET role='USER',auth_version=auth_version+1 WHERE user_id=%s",(ungranted_id,));sql('UPDATE auth_sessions SET revoked_at=COALESCE(revoked_at,UTC_TIMESTAMP(3)) WHERE user_id=%s',(ungranted_id,))
 except:finished=False;raise
 finally:
  (out/'http-acceptance.json').write_text(json.dumps({'status':'PASS' if finished else 'INCOMPLETE','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8');db.close()
