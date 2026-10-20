"""Real HTTP + private MySQL acceptance. Run only against local_auth_db.py's instance."""
from pathlib import Path
import json, sys, secrets, urllib.request, urllib.error, concurrent.futures
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
from argon2 import PasswordHasher
config=json.loads((ROOT/'.local/auth-dev/private.json').read_text())
BASE='http://127.0.0.1:8080/api/v1'
checks=[]
def check(value,name):
    checks.append({'name':name,'passed':bool(value)})
    if not value: raise AssertionError(name)
def call(path,method='GET',body=None,token=None,key=None):
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if key:headers['Idempotency-Key']=key
    if body is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
    try:result=urllib.request.urlopen(request,timeout=15)
    except urllib.error.HTTPError as error:result=error
    return result.status,json.loads(result.read()),result.headers
def login(account,password):
    status,data,_=call('/auth/login','POST',{'account':account,'password':password})
    check(status==200,'login '+account.split('_')[0]);return data['data']['access_token']
conn=pymysql.connect(host=config['host'],port=config['port'],user='root',password=config['root_password'],database=config['database'],charset='utf8mb4',autocommit=True)
with conn.cursor() as cursor:
    cursor.execute('SELECT @@datadir,@@port');location,port=cursor.fetchone()
    assert Path(location).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
name='accept_'+secrets.token_hex(4)
password='AcceptCampus2026!'+secrets.token_hex(3)
body={'username':name,'password':password,'email':name+'@example.invalid'}
key=secrets.token_hex(16)
status,result,_=call('/auth/register','POST',body,key=key)
check(status==201,'register persisted via HTTP')
user=result['data'];uid=user['user_id']
check(user['role']=='USER' and user['account_status']=='ACTIVE','registration cannot create admin')
check(user['identity_verification']=='未认证' and user['campus_verification']=='未认证','registration not verified')
check(not user['privacy_settings']['show_campus'] and not user['privacy_settings']['show_personal_bio'],'privacy defaults private')
check('password_hash' not in user and 'access_token' not in user,'register no hash or automatic login')
with conn.cursor() as cursor:
    cursor.execute('SELECT password_hash FROM users WHERE user_id=%s',(uid,));hashed=cursor.fetchone()[0]
    check(hashed.startswith('$argon2id$') and PasswordHasher().verify(hashed,password),'database stores verifiable salted Argon2id')
    cursor.execute('SELECT reward_type,available_amount,frozen_amount FROM reward_accounts WHERE user_id=%s',(uid,));balances=cursor.fetchall()
    check(len(balances)==2 and all(a==0 and f==0 for _,a,f in balances),'registration atomically initializes both zero accounts')
status,replayed,_=call('/auth/register','POST',body,key=key)
check(status==201 and replayed['data']['user_id']==uid,'idempotent retry same user')
status,value,_=call('/auth/register','POST',{**body,'password':password+'x'},key=key)
check(status==409 and value['code']=='IDEMPOTENCY_CONFLICT','same key different body conflicts')
status,_,_=call('/auth/register','POST',body,key=secrets.token_hex(16));check(status==409,'duplicate account rejected')
status,_,_=call('/auth/register','POST',{**body,'role':'ADMIN'},key=secrets.token_hex(16));check(status==400,'unknown registration privilege field rejected')
status,_,_=call('/auth/register','POST',{**body,'username':'a'},key=secrets.token_hex(16));check(status==400,'username format enforced')
status,_,_=call('/auth/register','POST',{'username':name+'b','password':password},key=secrets.token_hex(16));check(status==400,'contact required')
status,_,_=call('/auth/register','POST',{**body,'username':name+'b','phone':'+123'},key=secrets.token_hex(16));check(status==400,'E164 minimum length enforced')
parallel={**body,'username':name+'p','email':name+'p@example.invalid'}
parallel_key=secrets.token_hex(16)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    parallel_results=list(pool.map(lambda _:call('/auth/register','POST',parallel,key=parallel_key),range(3)))
check(all(r[0]==201 for r in parallel_results) and len({r[1]['data']['user_id'] for r in parallel_results})==1,'concurrent idempotent registration creates one account')
with conn.cursor() as cursor:
    trigger='auth_accept_'+secrets.token_hex(4)
    reject_name=name+'r'
    cursor.execute("CREATE TRIGGER `"+trigger+"` BEFORE INSERT ON reward_accounts FOR EACH ROW BEGIN IF EXISTS(SELECT 1 FROM users WHERE user_id=NEW.user_id AND username='"+reject_name+"') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled acceptance failure'; END IF; END")
try:
    rejected={**body,'username':reject_name,'email':reject_name+'@example.invalid'}
    rejected_key=secrets.token_hex(16)
    status,failure,_=call('/auth/register','POST',rejected,key=rejected_key)
    check(status==503 and failure['code']=='DEPENDENCY_UNAVAILABLE' and 'trace' not in failure,'dependency failure gives safe error')
    with conn.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM users WHERE username=%s',(reject_name,));check(cursor.fetchone()[0]==0,'reward initialization failure rolls back user')
        cursor.execute('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(rejected_key,));check(cursor.fetchone()[0]==0,'reward initialization failure rolls back idempotency record')
finally:
    with conn.cursor() as cursor:cursor.execute('DROP TRIGGER `'+trigger+'`')
status,_,_=call('/me');check(status==401,'me requires token')
token=login(name,password)
status,mine,_=call('/me',token=token);check(status==200 and mine['data']['user_id']==uid,'me returns self')
email_token=login(body['email'],password)
check(email_token!=token,'every login produces a new opaque token')
with conn.cursor() as cursor:
    cursor.execute('SELECT token_hash FROM auth_sessions WHERE user_id=%s',(uid,));stored=[x[0] for x in cursor.fetchall()]
    check(token not in stored and all(len(x)==64 for x in stored),'raw token not stored in database')
status,_,_=call('/admin/users',token=token);check(status==403,'ordinary user cannot access management')
status,_,_=call('/me','PATCH',{'role':'ADMIN'},token);check(status==400,'profile cannot escalate role')
status,_,_=call('/me','PATCH',{'personal_bio':'公开介绍','address':'私有地址','privacy_settings':{'show_campus':False,'show_personal_bio':True}},token);check(status==200,'profile and privacy updates persisted')
status,public,_=call('/users/'+uid+'/profile');check(status==200 and public['data']['personal_bio']=='公开介绍' and not any(k in public['data'] for k in ['email','phone','address','role']),'public view protects private fields')
admin=login(config['admin_username'],config['admin_password'])
status,listing,_=call('/admin/users?keyword='+name,token=admin);check(status==200 and listing['data']['total']=='2' and any(item['user_id']==uid for item in listing['data']['items']),'admin real filtering returns string total and matching users')
check(listing['data']['items'][0]['email']!=body['email'] and listing['data']['items'][0]['address'] is None,'admin masks contact and private address')
status,details,_=call('/admin/users/'+uid,token=admin);check(status==200 and 'password_hash' not in details['data'],'admin detail safe')
status,_,_=call('/admin/users/'+uid+'/role','POST',{'role':'ADMIN','reason':'test'},admin,secrets.token_hex(16));check(status==403,'role changes default disabled')
status,_,_=call('/admin/users/'+uid+'/status','POST',{'account_status':'DISABLED','reason':''},admin,secrets.token_hex(16));check(status==400,'management reason required')
status,_,_=call('/admin/users/'+uid+'/status','POST',{'account_status':'DISABLED','reason':'本地自动验收'},admin,secrets.token_hex(16));check(status==200,'admin disables user')
status,_,_=call('/me',token=token);check(status==401,'disable revokes existing session')
status,disabled,_=call('/auth/login','POST',{'account':name,'password':password});check(status==403 and disabled['code']=='ACCOUNT_DISABLED','disabled account cannot log in')
status,_,_=call('/admin/users/'+uid+'/status','POST',{'account_status':'ACTIVE','reason':'本地验收恢复'},admin,secrets.token_hex(16));check(status==200,'admin enables user')
status,_,_=call('/me',token=token);check(status==401,'enable does not revive old session')
token=login(name,password)
status,_,_=call('/me/password','POST',{'current_password':password,'new_password':password+'new'},token);check(status==200,'password change persisted')
status,_,_=call('/me',token=token);check(status==401,'password change revokes token')
status,_,_=call('/auth/login','POST',{'account':name,'password':password});check(status==401,'old password rejected')
token=login(name,password+'new')
status,_,_=call('/auth/logout','POST',token=token);check(status==200,'logout success')
status,_,_=call('/auth/logout','POST',token=token);check(status==200,'logout retry remains success')
status,_,_=call('/me',token=token);check(status==401,'logged out token rejected')
token=login(name,password+'new')
with conn.cursor() as cursor:cursor.execute("UPDATE auth_sessions SET last_seen_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 31 MINUTE) WHERE user_id=%s AND revoked_at IS NULL",(uid,))
status,_,_=call('/me',token=token);check(status==401,'idle expiration enforced by database session')
token=login(name,password+'new')
with conn.cursor() as cursor:cursor.execute("UPDATE auth_sessions SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE user_id=%s AND revoked_at IS NULL",(uid,))
status,_,_=call('/me',token=token);check(status==401,'absolute expiration enforced')
for attempt in range(5):status,value,headers=call('/auth/login','POST',{'account':'missing_'+name,'password':'WrongPassword2026!'})
check(status==429 and value['code']=='RATE_LIMITED' and int(headers['Retry-After'])>0,'five failures rate limited')
status,_,_=call('/auth/recovery-challenges','POST',{});check(status==501,'P1 recovery not faked')
status,_,_=call('/__baseline_unknown_route__');check(status==404,'unknown API remains 404')
with conn.cursor() as cursor:
    cursor.execute('SELECT COUNT(*) FROM admin_audit_log WHERE object_id=%s',(uid,));check(cursor.fetchone()[0]>=2,'management operations create durable audit')
report={'status':'PASS','scope':'private MySQL + real Spring Boot HTTP; no WeChat runtime acceptance','total':len(checks),'checks':checks}
(ROOT/'.local/auth-dev/http-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'status':'PASS','checks':len(checks)},ensure_ascii=False))
conn.close()
