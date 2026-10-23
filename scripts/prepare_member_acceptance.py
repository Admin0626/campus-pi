"""Prepare ONLY newly created synthetic accounts for real local Web/native adapter acceptance.
Requires the verified private 127.0.0.1:33320 campus_pi_auth_local instance and running backend8080.
Submits real files through Defender and real certification review. Assigns 100 test points only to
this run's new synthetic seller. This is NOT a user-facing funding API or production allocation.
Credentials and fixture metadata are written only to the ignored .local/remaining directory.
"""
from pathlib import Path
import sys,json,secrets,urllib.request,urllib.error,struct,zlib,base64
root=Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'.local/pydeps'));import pymysql
(root/'.local/remaining').mkdir(parents=True,exist_ok=True)
c=json.loads((root/'.local/auth-dev/private.json').read_text(encoding='utf-8'));assert(c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
 cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone();assert Path(directory).resolve()==(root/'.local/auth-dev/mysql-data').resolve() and port==33320

def call(path,method='GET',body=None,token=None,raw=None,content_type=None):
 headers={'Accept':'application/json','Idempotency-Key':secrets.token_hex(16)}
 if token:headers['Authorization']='Bearer '+token
 if body is not None:headers['Content-Type']='application/json'
 if content_type:headers['Content-Type']=content_type
 req=urllib.request.Request('http://127.0.0.1:8080/api/v1'+path,method=method,headers=headers,data=raw if raw is not None else json.dumps(body).encode() if body is not None else None)
 try:r=urllib.request.urlopen(req,timeout=40)
 except urllib.error.HTTPError as ex:r=ex
 value=json.loads(r.read());assert 200<=r.status<300, str(r.status)+' '+value.get('message','');return value['data']
admin=call('/auth/login','POST',{'account':c['admin_username'],'password':c['admin_password']})['access_token']
def chunk(name,data):return struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',24,24,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\x90\x80\xd0'*24)*24))+chunk(b'IEND',b'')
(root/'.local/remaining/evidence.png').write_bytes(png)
def upload(token,purpose):
 boundary='Campus'+secrets.token_hex(12);raw=('--'+boundary+'\r\nContent-Disposition: form-data; name="purpose"\r\n\r\n'+purpose+'\r\n--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="evidence.png"\r\nContent-Type: image/png\r\n\r\n').encode()+png+('\r\n--'+boundary+'--\r\n').encode();return call('/files','POST',token=token,raw=raw,content_type='multipart/form-data; boundary='+boundary)['file_ref']
people=[]
for label in ['seller','buyer']:
 name='ui_'+label+'_'+secrets.token_hex(5);password=secrets.token_urlsafe(24);uid=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password})['user_id'];token=call('/auth/login','POST',{'account':name,'password':password})['access_token']
 # Real submission/Defender upload/admin review. Only reward funding below is an explicit synthetic fixture.
 ref=upload(token,'VERIFICATION')
 for kind in ['实名','校园']:
  body={'verification_type':kind,'real_name':'合成页面验收人员','evidence_refs':[ref]}
  if kind=='校园':body.update(campus='测试校区',student_no='UI'+uid)
  vid=call('/me/verifications','POST',body,token)['verification_id'];call('/admin/verifications/'+vid+'/review','POST',{'decision':'通过','reason':'仅本机独立验收用户的合成材料'},admin)
 people.append({'name':name,'password':password,'id':uid,'token':token})
with db.cursor() as cur:cur.execute("UPDATE reward_accounts SET available_amount=100.00 WHERE user_id=%s AND reward_type='积分'",(people[0]['id'],))
category=call('/admin/categories','POST',{'category_name':'页面验收_'+secrets.token_hex(4),'parent_category_id':None},admin)
(root/'.local/remaining/ui-fixtures.json').write_text(json.dumps({'seller':people[0],'buyer':people[1],'category':category['category_id'],'admin_token':admin},ensure_ascii=False),encoding='utf-8')
print(json.dumps({'status':'PASS','new_synthetic_users':2,'real_upload_and_review':True,'funding':'synthetic new seller only, not self-credit API'}))
