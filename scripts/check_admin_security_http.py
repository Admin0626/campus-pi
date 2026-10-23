"""Real private-MySQL/HTTP checks; uploads pass the configured real antivirus scanner."""
from pathlib import Path
import concurrent.futures,datetime,hashlib,json,re,secrets,struct,sys,time,urllib.error,urllib.parse,urllib.request,zlib
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
out=ROOT/'.local/auth-dev/admin-security';out.mkdir(parents=True,exist_ok=True)
checks=[];finished=False
(out/'http-acceptance.json').write_text(json.dumps({'status':'RUNNING'}))
def check(value,name):
 checks.append({'name':name,'passed':bool(value)})
 if not value:raise AssertionError(name)
def query(sql,args=(),scalar=True):
 with db.cursor() as cur:
  cur.execute(sql,args)
  if cur.description:
   return cur.fetchone()[0] if scalar else cur.fetchall()
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
 try:res=urllib.request.urlopen(req,timeout=45)
 except urllib.error.HTTPError as error:res=error
 data=res.read()
 return res.status,data if binary else json.loads(data),dict(res.headers)
def call(*args,**kwargs):return request(*args,**kwargs)[:2]
def login(name,password):
 status,value=call('/auth/login','POST',{'account':name,'password':password})
 check(status==200,'Real login succeeds')
 return value['data']['access_token']
def create(label):
 name='sec_'+label+'_'+secrets.token_hex(4);password=secrets.token_urlsafe(18)
 status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16))
 check(status==201,'Create synthetic '+label+' account')
 return r['data']['user_id'],login(name,password),name,password
def png():
 def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
 return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',4,4,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x75\x55\xee'*4)*4))+chunk(b'IEND',b'')
def pdf():
 objects=[b'<< /Type /Catalog /Pages 2 0 R >>',b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>',b'<< /Length 0 >>\nstream\n\nendstream']
 data=b'%PDF-1.4\n';offsets=[0]
 for i,obj in enumerate(objects,1):offsets.append(len(data));data+=str(i).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
 offset=len(data);data+=b'xref\n0 5\n0000000000 65535 f \n'+b''.join(f'{v:010d} 00000 n \n'.encode() for v in offsets[1:])
 return data+b'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n'+str(offset).encode()+b'\n%%EOF\n'
def upload(data,purpose,token,key=None,extra=False):
 boundary='CampusPi'+secrets.token_hex(12)
 parts=[f'--{boundary}\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n{purpose}\r\n'.encode(),f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="not-trusted.html"\r\nContent-Type: text/html\r\n\r\n'.encode()+data+b'\r\n']
 if extra:parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="owner_id"\r\n\r\n1\r\n'.encode())
 return call('/files','POST',token=token,key=key or secrets.token_hex(16),raw=b''.join(parts)+f'--{boundary}--\r\n'.encode(),content_type='multipart/form-data; boundary='+boundary)
def file(data,purpose,token):
 status,r=upload(data,purpose,token);check(status==201,'Real scanned upload '+purpose);return r['data']
def access(file,token=None):return call('/files/'+file['file_id']+'/access',token=token)
def transport(url):return urllib.parse.urlsplit(url).path
def submit(token,ref,type='实名',student='test-student'):
 body={'verification_type':type,'real_name':'合成测试姓名','evidence_refs':[ref]}
 if type=='校园':body.update(campus='测试校区',student_no=student)
 return call('/me/verifications','POST',body,token,key=secrets.token_hex(16))
def review(id,decision='通过',key=None):return call('/admin/verifications/'+str(id)+'/review','POST',{'decision':decision,'reason':'合成接口审核'},admin,key or secrets.token_hex(16))
try:
 admin=login(c['admin_username'],c['admin_password']);admin_id=query('SELECT user_id FROM users WHERE username=%s',(c['admin_username'],))
 uid,owner,name,password=create('owner');other_id,other,_,_=create('other')
 check(upload(png(),'VERIFICATION',None)[0]==401,'Anonymous multipart upload rejected before scan')
 for route in ['/admin/verifications','/me/verifications']:
  check(call(route)[0]==401,'Anonymous request rejected '+route)
 check(call('/admin/verifications',token=owner)[0]==403,'Ordinary user cannot read verification queue')
 # Use another synthetic administrator with credentials held only in memory.
 no_id,no_token,no_name,no_password=create('ungranted');query("UPDATE users SET role='ADMIN' WHERE user_id=%s",(no_id,));no_token=login(no_name,no_password)
 for route in ['/admin/verifications','/admin/users/'+uid+'/role']:
  check(call(route,method='POST' if route.endswith('/role') else 'GET',body={'role':'ADMIN','reason':'denied'} if route.endswith('/role') else None,token=no_token,key=secrets.token_hex(16))[0]==403,'ADMIN alone lacks sensitive duty '+route)
 query("UPDATE users SET role='USER' WHERE user_id=%s",(no_id,))
 check(upload(b'<html>not an image</html>','VERIFICATION',owner)[0]==415,'HTML disguised as image rejected')
 check(upload(b'<svg xmlns="http://www.w3.org/2000/svg"/>','VERIFICATION',owner)[0]==415,'SVG disguised as image rejected')
 check(upload(png(),'INVALID',owner)[0]==400,'Unknown file purpose rejected')
 check(upload(b'','VERIFICATION',owner)[0]==400,'Empty upload rejected')
 check(upload(png(),'VERIFICATION',owner,extra=True)[0]==400,'Forged multipart owner field rejected')
 check(upload(pdf(),'PRODUCT_IMAGE',owner)[0]==415,'PDF is not accepted as a product image')
 check(upload(png()+b'0'*(2*1024*1024),'AVATAR',owner)[0]==413,'Avatar over 2 MiB rejected')
 check(upload(b'0'*(11*1024*1024),'VERIFICATION',owner)[0]==413,'Multipart size limit enforced')
 key=secrets.token_hex(16);status,r=upload(png(),'VERIFICATION',owner,key);check(status==201,'PNG detected by bytes despite false filename and MIME');material=r['data']
 check(material['mime_type']=='image/png' and material['file_ref']=='file://'+material['file_id'],'Upload DTO exposes controlled reference and actual MIME')
 check(not any(k in material for k in ['path','original_name','url','owner_id']),'Upload DTO hides path, original name and owner metadata')
 row=query('SELECT scan_status,scanner_name,sha256 FROM business_files WHERE file_id=%s',(material['file_id'],),False)[0]
 check(row==('CLEAN','DEFENDER',hashlib.sha256(png()).hexdigest()),'Actual Defender-scanned bytes persisted with matching digest')
 before=query('SELECT COUNT(*) FROM business_files WHERE owner_id=%s',(uid,))
 status,r=upload(png(),'VERIFICATION',owner,key)
 check(status==201 and r['data']==material and query('SELECT COUNT(*) FROM business_files WHERE owner_id=%s',(uid,))==before,'Same upload key/bytes replay metadata without duplicate row')
 check(upload(png(),'TASK_PROOF',owner,key)[0]==409,'Same upload key with other purpose conflicts')
 check(access(material)[0]==403 and access(material,other)[0]==403,'Unbound material is owner-only')
 status,r=access(material,owner);check(status==200,'Owner obtains a controlled preview grant');url=r['data']['url']
 expires=datetime.datetime.fromisoformat(r['data']['expires_at'].replace('Z','+00:00'))
 check(0<(expires-datetime.datetime.now(datetime.timezone.utc)).total_seconds()<=60,'Preview URL lifetime is at most 60 seconds')
 status,data,headers=request(transport(url),binary=True)
 check(status==200 and data==png() and headers.get('Content-Type')=='image/png','Controlled binary stream matches uploaded bytes')
 check(headers.get('X-Content-Type-Options')=='nosniff' and 'no-store' in headers.get('Cache-Control',''),'Binary transport is non-sniffing and non-cacheable')
 token2=login(name,password);status,r=access(material,token2);call('/auth/logout','POST',token=token2)
 check(request(transport(r['data']['url']),binary=True)[0]==401,'Logout invalidates a previously issued private URL')
 status,r=access(material,owner);grant_hash=hashlib.sha256(r['data']['url'].split('/')[-1].encode()).hexdigest()
 query('UPDATE business_file_grants SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE token_hash=%s',(grant_hash,))
 check(request(transport(r['data']['url']),binary=True)[0]==404,'Expired preview URL is rejected')
 check(submit(owner,material['file_ref'],'校园')[0]==409,'Campus application requires approved real-name verification')
 wrong=file(png(),'PRODUCT_IMAGE',owner)
 check(submit(owner,wrong['file_ref'])[0]==403,'Wrong-purpose evidence binding rejected atomically')
 check(query('SELECT COUNT(*) FROM user_verification WHERE user_id=%s',(uid,))==0,'Failed evidence binding rolls back verification insertion')
 foreign=file(png(),'VERIFICATION',other)
 check(submit(owner,foreign['file_ref'])[0]==403,'Evidence from another owner rejected')
 trigger='security_file_'+secrets.token_hex(4);abort_key=secrets.token_hex(16)
 existing_objects={p.name for p in (ROOT/'.local/auth-dev/business-files/objects').glob('*.blob')}
 try:
  query("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON business_files FOR EACH ROW BEGIN IF NEW.owner_id="+uid+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic file metadata failure'; END IF; END")
  check(upload(png(),'VERIFICATION',owner,abort_key)[0]==503,'Metadata dependency failure rejects a scanned upload')
  check(query('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(abort_key,))==0,'Failed upload does not retain a successful replay record')
  check(not ({p.name for p in (ROOT/'.local/auth-dev/business-files/objects').glob('*.blob')}-existing_objects),'Failed upload removes its newly stored private bytes')
 finally:query('DROP TRIGGER IF EXISTS `'+trigger+'`')
 status,r=submit(owner,material['file_ref']);check(status==201,'Owner submits real-name evidence');verification=r['data']['verification_id']
 check(submit(owner,material['file_ref'])[0]==409,'Only one pending application per owner/type')
 queue_page=1;row=None
 while row is None:
  status,r=call('/admin/verifications?audit_status='+urllib.parse.quote('待审核')+'&page_size=100&page='+str(queue_page),token=admin)
  check(status==200,'Read paged verification queue')
  row=next((x for x in r['data']['items'] if x['verification_id']==verification),None)
  if row is not None:break
  if queue_page*100>=int(r['data']['total']):raise AssertionError('New verification absent from complete queue')
  queue_page+=1
 check(set(row)=={'verification_id','user_id','verification_type','campus','audit_status','submitted_at'},'Queue projection excludes real name, student number and evidence')
 audits=query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='认证' AND object_id=%s",(verification,))
 status,r=call('/admin/verifications/'+verification,token=admin)
 check(status==200 and r['data']['evidence_refs']==[material['file_ref']],'Privileged detail explicitly returns bound evidence')
 check(query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='认证' AND object_id=%s",(verification,))==audits+1,'Sensitive detail access is audited')
 check(access(material,no_token)[0] in [401,403] and access(material,admin)[0]==200,'Bound certification evidence needs owner or explicit review duty')
 key=secrets.token_hex(16);status,r=review(verification,key=key)
 check(status==200 and r['data']['audit_status']=='已通过' and query('SELECT identity_verification FROM users WHERE user_id=%s',(uid,))=='已通过','Review updates application and owner status together')
 check(review(verification,key=key)[0]==200 and review(verification)[0]==409,'Same-key review replays, new-key duplicate review conflicts')
 check(query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='认证' AND object_id=%s AND action_name='审核'",(verification,))==1,'Review replay does not duplicate audit')
 check(submit(owner,material['file_ref'])[0]==409,'Approved real-name owner cannot apply again')
 campus_body={'verification_type':'校园','real_name':'合成测试姓名','campus':'未授权校区','student_no':'synthetic','evidence_refs':[material['file_ref']]}
 check(call('/me/verifications','POST',campus_body,owner,secrets.token_hex(16))[0]==400,'Campus is checked against server-owned allowlist')
 student='S'+secrets.token_hex(6);status,r=submit(owner,material['file_ref'],'校园',student);check(status==201,'Submit campus after real-name approval');campus_id=r['data']['verification_id']
 check(review(campus_id)[0]==200 and query('SELECT student_no FROM approved_campus_registry WHERE user_id=%s',(uid,))==student,'Approved campus/student registration is persisted atomically')
 status,r=submit(other,foreign['file_ref']);other_ver=r['data']['verification_id'];check(review(other_ver)[0]==200,'Approve second synthetic real name')
 status,r=submit(other,foreign['file_ref'],'校园',student);other_campus=r['data']['verification_id'];check(review(other_campus)[0]==409,'Same approved campus/student pair cannot belong to another owner')
 check(query('SELECT audit_status FROM user_verification WHERE verification_id=%s',(other_campus,))=='待审核','Duplicate student conflict rolls back review')
 status,r=submit(owner,material['file_ref'],'校园',student+'new');check(status==201 and query('SELECT campus_verification FROM users WHERE user_id=%s',(uid,))=='待审核' and query('SELECT COUNT(*) FROM approved_campus_registry WHERE user_id=%s',(uid,))==0,'Changing campus/student immediately suspends previous campus qualification');changed_id=r['data']['verification_id']
 check(review(other_campus,'驳回')[0]==200,'Reject conflicting pending campus application')
 status,r=submit(other,foreign['file_ref'],'校园',student+'new');check(status==201,'Prepare same-student concurrent review');other_changed=r['data']['verification_id']
 with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:results=list(pool.map(lambda id:review(id)[0],[changed_id,other_changed]))
 check(sorted(results)==[200,409] and query('SELECT COUNT(*) FROM approved_campus_registry WHERE campus=%s AND student_no=%s',('测试校区',student+'new'))==1,'Concurrent approvals preserve unique campus/student ownership')
 pending_self=query("SELECT verification_id FROM user_verification WHERE user_id=%s AND verification_type='实名' AND audit_status='待审核'",(admin_id,),False)
 if pending_self:self_id=pending_self[0][0]
 else:
  self_file=file(png(),'VERIFICATION',admin);status,r=submit(admin,self_file['file_ref']);check(status==201,'Create own synthetic administrator application');self_id=r['data']['verification_id']
 check(review(self_id)[0]==403,'Reviewer cannot approve their own evidence')
 rollback_file=file(png(),'VERIFICATION',no_token);status,r=submit(no_token,rollback_file['file_ref']);rollback_id=r['data']['verification_id']
 trigger='security_audit_'+secrets.token_hex(4);abort_reason='audit_abort_'+secrets.token_hex(4);abort_key=secrets.token_hex(16)
 try:
  query("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON admin_audit_log FOR EACH ROW BEGIN IF NEW.reason='"+abort_reason+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic verification audit failure'; END IF; END")
  check(call('/admin/verifications/'+rollback_id+'/review','POST',{'decision':'通过','reason':abort_reason},admin,abort_key)[0]==503,'Audit dependency failure rejects review')
  check(query('SELECT audit_status FROM user_verification WHERE verification_id=%s',(rollback_id,))=='待审核' and query('SELECT identity_verification FROM users WHERE user_id=%s',(no_id,))=='待审核','Audit failure rolls back application and owner qualification')
  check(query('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(abort_key,))==0,'Audit failure rolls back review replay record')
 finally:query('DROP TRIGGER IF EXISTS `'+trigger+'`')
 # SQL below only creates the product-to-file relation for this isolated fixture, not a claim that product publishing is implemented.
 status,r=call('/admin/categories','POST',{'category_name':'安全预览'+secrets.token_hex(4),'parent_category_id':None},admin,secrets.token_hex(16));check(status==201,'Prepare category through actual admin API');category=r['data']['category_id']
 product=query("INSERT INTO product_info(user_id,category_id,product_name,campus,original_price,discount_price) VALUES(%s,%s,%s,'测试校区',1,1)",(uid,category,'文件预览'+name))
 query('INSERT INTO product_media(product_id,image_url,sort_order) VALUES(%s,%s,0)',(product,wrong['file_ref']))
 query("INSERT INTO business_file_bindings(file_id,object_type,object_id,bound_at) VALUES(%s,'PRODUCT',%s,UTC_TIMESTAMP(3))",(wrong['file_id'],str(product)))
 check(access(wrong)[0]==403 and access(wrong,admin)[0]==200,'Pending product image is private to owner/admin')
 review_body={'decision':'通过','reason':'图片访问验收'};review_key=secrets.token_hex(16)
 status,r=call('/admin/products/'+str(product)+'/review','POST',review_body,admin,review_key);public_url=r['data']['image_urls'][0]
 check(status==200 and access(wrong)[0]==200,'Approved on-sale image becomes publicly readable')
 query('DELETE FROM business_file_grants WHERE token_hash=%s',(hashlib.sha256(public_url.split('/')[-1].encode()).hexdigest(),))
 status,r=call('/admin/products/'+str(product)+'/review','POST',review_body,admin,review_key)
 check(status==200 and r['data']['image_urls'][0]!=public_url and request(transport(r['data']['image_urls'][0]),binary=True)[0]==200,'Idempotent product reply issues fresh preview URLs rather than caching them for 24 hours')
 call('/admin/products/'+str(product)+'/shelf','POST',{'shelf_status':'已下架','reason':'可见性验收'},admin,secrets.token_hex(16))
 check(request(transport(r['data']['image_urls'][0]),binary=True)[0]==403,'Previously public URL rechecks current shelf visibility')
 # These SQL fixtures exercise existing object ACLs, not yet-unimplemented publishing APIs.
 proof_file=file(png(),'TASK_PROOF',owner)
 task=query("INSERT INTO RewardTasks(publisher_id,taker_id,task_name,task_description,campus,reward_amount,reward_type,task_status) VALUES(%s,%s,'合成凭证任务','测试文件权限','测试校区',1,'积分','待验收')",(other_id,uid))
 proof=query("INSERT INTO task_proofs(reward_task_id,submitter_id,submission_no,proof_text,evidence_urls) VALUES(%s,%s,1,'合成文件证据',%s)",(task,uid,json.dumps([proof_file['file_ref']])))
 query("INSERT INTO business_file_bindings VALUES(%s,'TASK_PROOF',%s,UTC_TIMESTAMP(3))",(proof_file['file_id'],str(proof)))
 check(access(proof_file,owner)[0]==200 and access(proof_file,other)[0]==200 and access(proof_file,admin)[0]==200 and access(proof_file)[0]==403,'Task proof is limited to current parties and administrator')
 avatar=file(png(),'AVATAR',owner);query("INSERT INTO business_file_bindings VALUES(%s,'AVATAR',%s,UTC_TIMESTAMP(3))",(avatar['file_id'],uid))
 check(access(avatar,owner)[0]==403,'Binding metadata alone cannot grant access without actual business relation')
 query('UPDATE users SET avatar_url=%s WHERE user_id=%s',(avatar['file_ref'],uid))
 check(access(avatar)[0]==200,'Current public avatar is anonymously readable')
 message_file=file(png(),'CHAT_IMAGE',owner)
 session=query('INSERT INTO t_chat_session(sender_id,receiver_id) VALUES(%s,%s)',tuple(sorted([int(uid),int(other_id)])))
 message=query("INSERT INTO t_private_msg(session_id,sender_id,receiver_id,content_type,content) VALUES(%s,%s,%s,'图片',%s)",(session,uid,other_id,message_file['file_ref']))
 query("INSERT INTO business_file_bindings VALUES(%s,'MESSAGE',%s,UTC_TIMESTAMP(3))",(message_file['file_id'],str(message)))
 check(access(message_file,owner)[0]==200 and access(message_file,other)[0]==200 and access(message_file,admin)[0]==403,'Even a reporting administrator cannot traverse unreported private message images')
 query("INSERT INTO t_msg_report(msg_id,reporter_id,reason) VALUES(%s,%s,'合成图片举报')",(message,other_id))
 before=query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='文件' AND object_id=%s",(message_file['file_id'],))
 status,r=access(message_file,admin);check(status==200 and request(transport(r['data']['url']),binary=True)[0]==200,'Reporting duty reads only case-linked message image')
 check(query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='文件' AND object_id=%s",(message_file['file_id'],))==before+2,'Sensitive message image grant and read are both audited')
 query('UPDATE t_private_msg SET sender_deleted=1 WHERE msg_id=%s',(message,))
 check(access(message_file,owner)[0]==403 and access(message_file,other)[0]==200,'Private message deletion is respected per participant')
 aftersale_file=file(png(),'AFTERSALE',owner);order='ORDER'+str(time.time_ns()%10**14).zfill(14);aftersale='AFTER'+order[5:]
 query("INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,actual_amount,order_status) VALUES(%s,'商品',%s,%s,%s,'合成售后',1,1,1,1,'售后中')",(order,uid,other_id,product))
 query("INSERT INTO order_aftersales(aftersale_id,order_id,user_id,apply_amount,apply_reason,evidence_urls) VALUES(%s,%s,%s,1,'合成材料',%s)",(aftersale,order,uid,json.dumps([aftersale_file['file_ref']])))
 query("INSERT INTO business_file_bindings VALUES(%s,'AFTERSALE',%s,UTC_TIMESTAMP(3))",(aftersale_file['file_id'],aftersale))
 check(access(aftersale_file,owner)[0]==200 and access(aftersale_file,other)[0]==200 and access(aftersale_file,admin)[0]==200 and access(aftersale_file)[0]==403,'Aftersale evidence requires order party or explicit orders duty')
 disposable=file(png(),'TASK_PROOF',owner);path=ROOT/'.local/auth-dev/business-files/objects'/(disposable['file_id']+'.blob')
 query('UPDATE business_files SET unbound_expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE file_id IN (%s,%s)',(disposable['file_id'],material['file_id']))
 deadline=time.monotonic()+8
 while path.exists() and time.monotonic()<deadline:time.sleep(.25)
 check(not path.exists() and query('SELECT COUNT(*) FROM business_files WHERE file_id=%s',(disposable['file_id'],))==0,'Expired unbound file bytes and metadata are reclaimed')
 check(query('SELECT COUNT(*) FROM business_files WHERE file_id=%s',(material['file_id'],))==1 and access(material,owner)[0]==200,'Bound certification evidence survives unbound retention deadline')
 role_token=login(name,password);role_token2=login(name,password);key=secrets.token_hex(16)
 body={'role':'ADMIN','reason':'合成角色验收'};route='/admin/users/'+uid+'/role'
 status,r=access(material,owner);before_role_url=r['data']['url']
 status,r=call(route,'POST',body,admin,key);check(status==200 and r['data']['role']=='ADMIN','Explicitly granted administrator can adjust roles')
 check(call('/me',token=role_token)[0]==401 and call('/me',token=role_token2)[0]==401,'Role adjustment revokes every target session')
 check(request(transport(before_role_url),binary=True)[0]==401,'Role adjustment also invalidates private file grants issued to old sessions')
 check(call(route,'POST',body,admin,key)[0]==200 and query("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='USER' AND object_id=%s AND action_name='ROLE_CHANGE'",(uid,))==1,'Role change replay does not duplicate audit')
 check(call(route,'POST',{**body,'role':'USER'},admin,key)[0]==409,'Role idempotency key rejects altered request')
 check(call(route,'POST',{'role':'ADMIN','reason':' '},admin,secrets.token_hex(16))[0]==400,'Role change requires a nonblank reason')
 check(call(route,'POST',{**body,'duties':['ROLES']},admin,secrets.token_hex(16))[0]==400,'Client cannot grant server-owned duties')
 call(route,'POST',{'role':'USER','reason':'恢复合成账号'},admin,secrets.token_hex(16))
 # Preserve all other accounts exactly; last-admin test only mutates known synthetic admin fixtures.
 others=query("SELECT user_id,username,email FROM users WHERE role='ADMIN' AND account_status='ACTIVE' AND user_id<>%s",(admin_id,),False)
 # These exact names/domains are generated by the repository's content and batch regression scripts.
 # Any account outside these known synthetic fixtures still stops this test without mutation.
 if all(re.fullmatch(r'(?:admincheck|batch_revoked|b_revoked|b_other_admin)_[0-9a-f]{8}',username) and email==username+'@example.invalid' for _,username,email in others):
  try:
   for other_uid,_,_ in others:query("UPDATE users SET role='USER' WHERE user_id=%s",(other_uid,))
   check(call('/admin/users/'+str(admin_id)+'/role','POST',{'role':'USER','reason':'最后管理员保护验收'},admin,secrets.token_hex(16))[0]==409,'Last active administrator cannot be demoted')
  finally:
   for other_uid,_,_ in others:query("UPDATE users SET role='ADMIN' WHERE user_id=%s",(other_uid,))
 else:raise AssertionError('Unexpected non-fixture administrator: last-admin check requires a separate clean fixture')
 # Leave new pending evidence for the real browser test, with identifiers only, no credentials.
 owner=login(name,password)
 fixture_id,browser_token,browser_name,browser_password=create('browser');browser_evidence=file(png(),'VERIFICATION',browser_token);browser_pdf=file(pdf(),'VERIFICATION',browser_token)
 status,r=call('/me/verifications','POST',{'verification_type':'实名','real_name':'合成测试姓名','evidence_refs':[browser_evidence['file_ref'],browser_pdf['file_ref']]},browser_token,secrets.token_hex(16));check(status==201,'Prepare real pending browser application with PNG and PDF')
 (out/'fixtures.json').write_text(json.dumps({'verification_id':r['data']['verification_id'],'browser_user':browser_name,'role_user':name,'product_id':str(product),'product_name':'文件预览'+name,'file_ref':browser_evidence['file_ref']},ensure_ascii=False),encoding='utf-8')
 finished=True;print(json.dumps({'status':'PASS','checks':len(checks),'scope':'real HTTP + private MySQL + real Defender scan'}))
finally:
 (out/'http-acceptance.json').write_text(json.dumps({'status':'PASS' if finished else 'INCOMPLETE','checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'checks':checks,'scope':'real HTTP + private MySQL + real Defender scan'},ensure_ascii=False,indent=2),encoding='utf-8')
 db.close()
