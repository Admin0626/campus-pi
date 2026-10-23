"""Check the dedicated synthetic manual fixture DB and its loopback HTTP service.
Never prints credentials/tokens or mutates business objects; HTTP reads may log audit events.
Initial-state checks deliberately fail after the tester has consumed the fixtures.
"""
from pathlib import Path
import argparse, hashlib, json, urllib.request, urllib.error, urllib.parse
import pymysql

ROOT=Path(__file__).resolve().parents[1]
SCHEMA='campus_pi_manual_test'

def main():
    p=argparse.ArgumentParser();p.add_argument('--config',type=Path,required=True);args=p.parse_args()
    c=json.loads(args.config.read_text(encoding='utf-8'))
    if c.get('host') not in ('127.0.0.1','localhost') or c.get('database')!=SCHEMA:raise SystemExit('Dedicated loopback manual schema only')
    checks=[]
    def check(value,name):
        checks.append({'name':name,'passed':bool(value)})
        if not value:raise AssertionError(name)
    out=ROOT/'.local/manual-test/verification.json'
    tokens=[]
    def call(path,token=None,body=None,expected=200):
        headers={'Accept':'application/json'}
        if token:headers['Authorization']='Bearer '+token
        if body is not None:headers['Content-Type']='application/json'
        q=urllib.request.Request('http://127.0.0.1:8082/api/v1'+path,headers=headers,data=None if body is None else json.dumps(body).encode())
        try:r=urllib.request.urlopen(q,timeout=15)
        except urllib.error.HTTPError as e:r=e
        value=json.loads(r.read());check(r.status==expected and 'request_id' in value,'HTTP '+path+' => '+str(expected))
        return value.get('data')
    try:
        with pymysql.connect(host=c['host'],port=int(c.get('port',3306)),user=c['user'],password=c['password'],database=SCHEMA,charset='utf8mb4',autocommit=True) as db,db.cursor() as q:
            def sql(s,a=None):
                q.execute(s,a);return q.fetchall()
            check(sql('SELECT DATABASE()')[0][0]==SCHEMA,'Dedicated schema selected')
            check(sql('SELECT COUNT(*) FROM users')[0][0]==13,'13 synthetic accounts')
            matrix=json.loads((ROOT/'database/manual/接口人工测试矩阵.json').read_text(encoding='utf-8'))
            actual=sql('SELECT case_id,path FROM manual_test_cases ORDER BY case_id')
            check(actual==tuple((r['case_id'],r['path']) for r in matrix),'All 117 case IDs and paths match generated matrix')
            check(sql("SELECT COUNT(*) FROM manual_test_cases WHERE case_id LIKE 'API%'")[0][0]==112,'112 baseline cases present')
            check(sql("SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=900003 AND reward_type='积分'")[0]==(840,140),'Publisher fixture point balance and freeze')
            check(sql("SELECT a.frozen_amount=SUM(t.reward_amount) FROM reward_accounts a JOIN RewardTasks t ON t.publisher_id=a.user_id AND t.reward_type=a.reward_type AND t.task_status IN ('待审核','待接取','进行中','待验收','未完成') WHERE a.user_id=900003 AND a.reward_type='积分' GROUP BY a.frozen_amount")[0][0]==1,'Point freeze matches active tasks')
            check(sql('SELECT COUNT(*) FROM task_settlement WHERE reward_task_id=900005')[0][0]==1,'One historical settlement')
            check(sql('SELECT COUNT(*) FROM notification_receipts WHERE user_id=900008')[0][0]==0,'Opt-out receives no receipt')
            check(sql('SELECT COUNT(*) FROM account_wechat_bindings')[0][0]==0,'No fabricated WeChat binding')
            assets=sql('SELECT file_id,content,sha256 FROM manual_test_assets')
            check(len(assets)==25 and all(hashlib.sha256(data).hexdigest()==digest and (ROOT/'.local/manual-test/files/objects'/(fid+'.blob')).read_bytes()==data for fid,data,digest in assets),'25 materialized files match SQL bytes and SHA')
            before=sql('SELECT (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM RewardTasks),(SELECT COUNT(*) FROM reward_ledger)')
            try:q.execute('CALL campus_pi_manual_test.campus_manual_seed_v1()')
            except pymysql.MySQLError as e:check(e.args[0]==1644 and 'Refusing populated' in str(e.args[1]),'Re-import is rejected before business writes')
            else:raise AssertionError('Re-import should refuse populated fixture DB')
            check(before==sql('SELECT (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM RewardTasks),(SELECT COUNT(*) FROM reward_ledger)'),'Re-import preserved users, tasks and ledger')
        def login(name,password='CampusPi!2026'):
            data=call('/auth/login',body={'account':name,'password':password});tokens.append(data['access_token']);return data['access_token']
        admin=login('admin','123456');buyer=login('manual_buyer');limited=login('manual_admin_limited')
        call('/auth/login',body={'account':'manual_disabled','password':'CampusPi!2026'},expected=403)
        date='date_from=2026-10-20&date_to=2026-10-20'
        users=call('/admin/users?keyword=900004&'+date,admin)
        check(users['total']=='1' and users['items'][0]['user_id']=='900004' and users['items'][0]['last_login_time'],'ID/date filtering and actual last login')
        logs=call('/admin/task-audit-logs?reward_task_id=900005&'+date,admin)
        check(int(logs['total'])>0 and all(r['reward_task_id']=='900005' and r['task_name'] for r in logs['items']),'Global task logs include real task projection')
        history=call('/admin/notification-deliveries?'+date,admin)
        check(history['total']=='1' and history['items'][0]['delivery_id']=='900001' and history['items'][0]['notification_title'],'Delivery date matches actual sent_at, not pending attempts')
        failed=call('/admin/notification-deliveries?'+urllib.parse.urlencode({'delivery_status':'失败','keyword':'MANUAL_NOTICE_SENT'}),admin)
        check(failed['total']=='1' and failed['items'][0]['delivery_id']=='900002','Global failed delivery search')
        for route in ['/admin/task-audit-logs','/admin/notification-deliveries']:
            call(route,expected=401);call(route,buyer,expected=403)
            call(route+'?page_size=101',admin,expected=400)
            call(route+'?date_from=2026-10-21&date_to=2026-10-20',admin,expected=400)
            call(route+'?date_from=2026-02-30',admin,expected=400)
            call(route+'?keyword='+('x'*101),admin,expected=400)
        call('/admin/notification-deliveries',limited,expected=403)
        call('/admin/users?date_to=invalid',admin,expected=400)
        dashboard=call('/admin/dashboard',admin)
        check(all(k in dashboard for k in ['pending_verifications','pending_products','pending_posts','pending_tasks','pending_applications','pending_proofs','pending_reports']),'Dashboard has seven real pending counters')
        product=call('/products/900001',buyer)
        check(bool(product['image_urls']),'Seeded product supplies an actual image grant')
        with urllib.request.urlopen(product['image_urls'][0],timeout=15) as image:
            check(image.status==200 and image.read().startswith(b'\x89PNG\r\n\x1a\n'),'Restored PNG is served through an actual grant')
        call('/products/900007',buyer,expected=404)
        call('/auth/wechat/config')
        report={'status':'PASS','checks':len(checks),'scope':'Dedicated synthetic DB + real loopback HTTP, read/permission checks only','results':checks}
        out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='results'}))
    except Exception:
        out.write_text(json.dumps({'status':'FAIL','checks':len(checks),'results':checks},ensure_ascii=False,indent=2)+'\n',encoding='utf-8');raise
    finally:
        for token in tokens:
            try:call('/auth/logout',token,body={})
            except Exception:pass

if __name__=='__main__':main()
