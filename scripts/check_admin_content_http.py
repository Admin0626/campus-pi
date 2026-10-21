"""Real HTTP + isolated MySQL checks for the first admin implementation batch."""
from pathlib import Path
import concurrent.futures, datetime, json, secrets, sys, urllib.request, urllib.error
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text())
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
out=ROOT/'.local/auth-dev/admin-content';out.mkdir(parents=True,exist_ok=True)
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
 assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
 cur.execute("SET time_zone='+00:00'")
checks=[];finished=False
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def call(route,method='GET',body=None,token=None,key=None,reason=None):
 headers={'Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 if body is not None:headers['Content-Type']='application/json'
 if key:headers['Idempotency-Key']=key
 if reason:headers['X-Action-Reason']=urllib.parse.quote(reason) if not reason.isascii() else reason
 req=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,method=method,headers=headers,data=json.dumps(body).encode() if body is not None else None)
 try:res=urllib.request.urlopen(req,timeout=15)
 except urllib.error.HTTPError as e:res=e
 return res.status,json.loads(res.read())
def login(name,password):
 status,result=call('/auth/login','POST',{'account':name,'password':password})
 check(status==200,'HTTP login succeeds');return result['data']['access_token']
def scalar(sql,args=()):
 with db.cursor() as cur:cur.execute(sql,args);return cur.fetchone()[0]
def execute(sql,args=()):
 with db.cursor() as cur:cur.execute(sql,args);return cur.lastrowid
suffix=secrets.token_hex(4);name='admincheck_'+suffix;password=secrets.token_urlsafe(20)
try:
 admin=login(c['admin_username'],c['admin_password'])
 admin_id=scalar('SELECT user_id FROM users WHERE username=%s',(c['admin_username'],))
 for route in ['/admin/products','/admin/posts','/admin/dashboard','/admin/audit-logs']:
  check(call(route)[0]==401,'Unauthenticated management rejected '+route)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16))
 check(status==201,'Create synthetic test owner');uid=r['data']['user_id'];ordinary=login(name,password)
 check(call('/admin/products',token=ordinary)[0]==403,'Ordinary user cannot read admin content')
 execute("UPDATE users SET role='ADMIN' WHERE user_id=%s",(uid,));ungranted=login(name,password)
 check(call('/admin/audit-logs',token=ungranted)[0]==403,'ADMIN role alone does not grant audit access')
 body={'category_name':'验收分类'+suffix,'parent_category_id':None,'category_description':'合成接口测试'};key=secrets.token_hex(16)
 status,r=call('/admin/categories','POST',body,admin,key);check(status==201,'Create root category');root=r['data']['category_id']
 status,r=call('/admin/categories','POST',dict(reversed(list(body.items()))),admin,key)
 check(status==201 and r['data']['category_id']==root,'Canonical body order preserves idempotent replay')
 check(call('/admin/categories','POST',{**body,'category_name':'other'},admin,key)[0]==409,'Same key different body conflicts')
 check(call('/admin/categories','POST',body,admin,secrets.token_hex(16))[0]==409,'Duplicate root name rejected')
 parallel={'category_name':'并发分类'+suffix,'parent_category_id':None}
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  results=list(pool.map(lambda _:call('/admin/categories','POST',parallel,admin,secrets.token_hex(16)),range(3)))
 check(sorted(s for s,_ in results)==[201,409,409],'Concurrent same-name roots create exactly one row')
 child={'category_name':'二级'+suffix,'parent_category_id':root}
 status,r=call('/admin/categories','POST',child,admin,secrets.token_hex(16));check(status==201,'Create second-level category');child_id=r['data']['category_id']
 check(call('/admin/categories','POST',{'category_name':'三级','parent_category_id':child_id},admin,secrets.token_hex(16))[0]==409,'Third category level rejected')
 check(call('/admin/categories/'+str(root),'PUT',{**body,'parent_category_id':child_id},admin)[0]==409,'Cannot reparent existing category')
 check(call('/admin/categories/'+str(root),'DELETE',token=admin,reason='accept-delete')[0]==409,'Cannot delete parent with child')
 check(call('/admin/categories/'+str(child_id),'DELETE',token=admin)[0]==400,'Delete requires action reason')
 for _ in range(2):check(call('/admin/categories/'+str(child_id),'DELETE',token=admin,reason='删除分类验收')[0]==200,'Delete is repeatable')
 check(scalar("SELECT reason FROM admin_audit_log WHERE object_type='分类' AND object_id=%s AND action_name='删除'",(str(child_id),))=='删除分类验收','UTF-8 action reason is decoded before audit storage')
 status,r=call('/categories');check(status==200 and all(type(x['category_id']) is int for x in r['data']['items']),'Public category DTO uses integer IDs')
 product=execute("INSERT INTO product_info(user_id,category_id,product_name,product_desc,campus,original_price,discount_price,stock_quantity) VALUES(%s,%s,%s,'纯文本描述','测试校区',25.00,12.50,3)",(uid,root,'验收商品'+suffix))
 product_route='/admin/products/'+str(product)
 execute('INSERT INTO product_favor(product_id,user_id) VALUES(%s,%s)',(product,admin_id))
 status,r=call('/admin/products',token=admin)
 check(next(x for x in r['data']['items'] if x['product_id']==str(product))['is_favorited'],'Admin product projection reflects actual current-user favorite')
 review={'decision':'通过','reason':'验收审核'+suffix};review_key=secrets.token_hex(16)
 status,r=call(product_route+'/review','POST',review,admin,review_key)
 check(status==200 and r['data']['shelf_status']=='在售' and r['data']['discount_price']=='12.50','Review persists and returns exact money string')
 check(abs((datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(r['data']['create_time'].replace('Z','+00:00'))).total_seconds())<60,'Fixture timestamp and API UTC conversion agree')
 check(call(product_route+'/review','POST',review,admin,review_key)[0]==200,'Review retry reuses original result')
 check(call(product_route+'/review','POST',review,admin,secrets.token_hex(16))[0]==409,'Second review with new key conflicts')
 for state in ['已下架','在售','已下架']:
  status,r=call(product_route+'/shelf','POST',{'shelf_status':state,'reason':'上下架验收'},admin,secrets.token_hex(16))
  check(status==200 and r['data']['shelf_status']==state,'Shelf state persisted '+state)
 execute("UPDATE users SET account_status='DISABLED' WHERE user_id=%s",(uid,))
 check(call(product_route+'/shelf','POST',{'shelf_status':'在售','reason':'disabled seller'},admin,secrets.token_hex(16))[0]==409,'Disabled seller cannot be shelved')
 execute("UPDATE users SET account_status='ACTIVE' WHERE user_id=%s",(uid,))
 execute('UPDATE product_info SET stock_quantity=0 WHERE product_id=%s',(product,))
 check(call(product_route+'/shelf','POST',{'shelf_status':'在售','reason':'zero stock'},admin,secrets.token_hex(16))[0]==409,'Zero stock cannot be shelved')
 execute('UPDATE product_info SET stock_quantity=3 WHERE product_id=%s',(product,))
 check(call('/admin/products?page_size=101',token=admin)[0]==400,'Page size cap enforced')
 check(call('/admin/products?audit_status=invalid',token=admin)[0]==400,'Unknown filter enum rejected')
 post='ADMIN_POST_'+suffix
 execute("INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content,is_anonymous) VALUES(%s,%s,%s,%s,%s,1)",(post,uid,root,'匿名测试'+suffix,'<script>alert(1)</script> 这是纯文本'))
 status,r=call('/admin/posts?keyword='+suffix,token=admin);items=r['data']['items']
 row=next(x for x in items if x['post_id']==post)
 check(status==200 and row['author'] is None and row['author_label']=='校园同学' and 'publisher_id' not in row,'Anonymous admin post view hides identity')
 check(call('/admin/posts/'+post+'/review','POST',{'decision':'通过','reason':'验收发布'},admin,secrets.token_hex(16))[0]==200,'Post can be approved')
 execute('INSERT INTO post_collection(user_id,post_id) VALUES(%s,%s)',(uid,post))
 execute('INSERT INTO post_collection(user_id,post_id) VALUES(%s,%s)',(admin_id,post))
 status,r=call('/admin/posts?keyword='+suffix,token=admin)
 check(next(x for x in r['data']['items'] if x['post_id']==post)['is_collected'],'Admin post projection reflects actual current-user collection')
 status,r=call('/admin/posts/'+post+'/withdraw','POST',{'reason':'验收下架'},admin,secrets.token_hex(16))
 check(status==200 and r['data']['post_status']=='已下架','Published post can be withdrawn')
 check(scalar('SELECT collection_status FROM post_collection WHERE post_id=%s',(post,))=='失效','Withdrawal invalidates collections atomically')
 check(call('/admin/categories/'+str(root),'DELETE',token=admin,reason='referenced')[0]==409,'Category referenced by business cannot be deleted')
 rollback_product=execute("INSERT INTO product_info(user_id,category_id,product_name,campus,original_price,discount_price) VALUES(%s,%s,%s,'测试校区',1.00,1.00)",(uid,root,'回滚测试'+suffix))
 trigger='admin_rollback_'+suffix;abort_reason='abort_'+suffix;abort_key=secrets.token_hex(16)
 try:
  execute("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort_reason+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic audit failure'; END IF; END")
  check(call('/admin/products/'+str(rollback_product)+'/review','POST',{'decision':'通过','reason':abort_reason},admin,abort_key)[0]==503,'Audit failure returns dependency error')
  check(scalar('SELECT audit_status FROM product_info WHERE product_id=%s',(rollback_product,))=='待审核','Audit failure rolls back business change')
  check(scalar('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(abort_key,))==0,'Audit failure rolls back replay record')
 finally:execute('DROP TRIGGER IF EXISTS `'+trigger+'`')
 admin_id=scalar('SELECT user_id FROM users WHERE username=%s',(c['admin_username'],))
 order='ORDER'+str(int(datetime.datetime.now().timestamp()*1000000)%10**14).zfill(14)
 execute("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,actual_amount,order_status) VALUES(%s,'商品',%s,%s,%s,'合成成交',2,1.23,2.46,2.46,'已完成')",(order,admin_id,uid,product))
 status,r=call('/admin/dashboard',token=admin);dashboard=r['data']
 check(status==200 and all(isinstance(dashboard[k],str) for k in ['user_count','published_post_count','onsale_product_count','completed_task_count','completed_product_amount']),'Dashboard uses string counts and exact amounts')
 check(dashboard['completed_product_amount']==str(scalar("SELECT COALESCE(SUM(actual_amount),0) FROM user_orders WHERE order_type='商品' AND order_status='已完成'")),'Dashboard amount matches actual completed product orders')
 check(int(dashboard['user_count'])==scalar('SELECT COUNT(*) FROM users') and int(dashboard['published_post_count'])==scalar("SELECT COUNT(*) FROM post_info WHERE post_status='已发布'"),'Dashboard counts match authoritative tables')
 status,r=call('/admin/audit-logs?object_type='+urllib.parse.quote('帖子')+'&object_id='+post,token=admin)
 check(status==200 and len(r['data']['items'])==2,'Authorized filtered audit query returns review and withdrawal')
 check(all('before_data' not in x and 'after_data' not in x for x in r['data']['items']),'Audit query omits private raw JSON')
 check(call(product_route+'/shelf','POST',{'shelf_status':'在售','reason':'   '},admin,secrets.token_hex(16))[0]==400,'Whitespace action reason rejected')
 fixtures={'suffix':suffix,'root_category':root,'pending_product':rollback_product,'anonymous_post':'BROWSER_POST_'+suffix}
 execute("INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content,is_anonymous) VALUES(%s,%s,%s,%s,%s,1)",(fixtures['anonymous_post'],uid,root,'浏览器匿名帖子'+suffix,'<script>alert(1)</script> 浏览器应显示纯文本'))
 (out/'fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False),encoding='utf-8')
 finished=True
 print(json.dumps({'status':'PASS','checks':len(checks),'scope':'real HTTP + isolated MySQL admin content'}))
finally:
 (out/'http-acceptance.json').write_text(json.dumps({'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'PASS' if finished else 'INCOMPLETE','checks':checks,'passed':finished and all(x['passed'] for x in checks),'count':len(checks)},ensure_ascii=False,indent=2),encoding='utf-8')
 db.close()
