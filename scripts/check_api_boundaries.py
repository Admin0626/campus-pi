"""BB-V: API equivalence/boundary/authorization cases. Local synthetic identity; no DB fixture mutation."""
from pathlib import Path
import json,secrets,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'.local/testing';OUT.mkdir(parents=True,exist_ok=True)
f=json.loads((ROOT/'.local/remaining/ui-fixtures.json').read_text(encoding='utf-8'))
BASE='http://127.0.0.1:8080/api/v1'
# Test fixtures can outlive the 30-minute idle session. Obtain fresh sessions
# before checking business status codes; do not weaken a 400/403 expectation to 401.
for who in ('seller','buyer'):
    payload=json.dumps({'account':f[who]['name'],'password':f[who]['password']}).encode()
    login=urllib.request.Request(BASE+'/auth/login',method='POST',headers={'Content-Type':'application/json'},data=payload)
    try:
        with urllib.request.urlopen(login,timeout=20) as response:
            f[who]['token']=json.loads(response.read())['data']['access_token']
    except (urllib.error.URLError,KeyError,ValueError):
        raise SystemExit('Cannot authenticate the local synthetic '+who+' fixture; no credentials printed.')
cases=[('V01','GET','/products?page=0',None,None,400),('V02','GET','/products?page_size=101',None,None,400),('V03','GET','/posts?page=2147483647',None,None,400),('V04','GET','/tasks?page_size=0',None,None,400),('V05','GET','/products?min_price=NaN',None,None,400),('V06','GET','/products?min_price=01.00',None,None,400),('V07','GET','/products?min_price=2.00&max_price=1.00',None,None,400),('V08','GET','/products?min_price=0.00&max_price=9999999999.99',None,None,200),('V09','GET','/posts?keyword=%27%20OR%201%3D1%20--',None,None,200),('V10','GET','/tasks?task_status=INVALID',None,None,400),('V11','GET','/chat-sessions',None,None,401),('V12','GET','/admin/users',None,'seller',403),('V13','GET','/me/orders?side=INVALID',None,'buyer',400),('V14','GET','/me/notifications?notification_type=INVALID',None,'buyer',400),('V15','POST','/posts',{'category_id':f['category'],'post_title':'','post_content':'x','is_anonymous':False},'seller',400),('V16','POST','/posts',{'category_id':f['category'],'post_title':'x','post_content':'x','is_anonymous':False,'publisher_id':f['buyer']['id']},'seller',400),('V17','POST','/chat-sessions',{'peer_user_id':f['seller']['id']},'seller',400),('V18','PUT','/me/notification-preferences',{'in_app_enabled':True,'email_enabled':False,'sms_enabled':False,'dnd_start':'12:00:00','dnd_end':None},'seller',400),('V19','POST','/tasks',{'task_name':'x','task_description':'x','campus':'测试校区','reward_type':'积分','reward_amount':'0.50'},'seller',400),('V20','GET','/unknown-testing-resource',None,None,404)]
results=[]
for id,method,path,body,who,status in cases:
    headers={'Accept':'application/json','Idempotency-Key':secrets.token_hex(16)}
    if who:headers['Authorization']='Bearer '+f[who]['token']
    if body is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request(BASE+path,method=method,headers=headers,data=json.dumps(body).encode() if body is not None else None)
    try:r=urllib.request.urlopen(request,timeout=20)
    except urllib.error.HTTPError as e:r=e
    value=json.loads(r.read());passed=r.status==status and all(k in value for k in ['code','message','data','request_id']) and (status<400 or value['data'] is None)
    results.append({'case':id,'method':method,'path':path,'expected':status,'actual':r.status,'passed':passed})
report={'status':'PASS' if all(r['passed'] for r in results) else 'FAIL','cases':results}
(OUT/'api-boundaries.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'status':report['status'],'cases':len(results),'failed':[r['case'] for r in results if not r['passed']]}))
raise SystemExit(0 if report['status']=='PASS' else 1)
