"""Real HTTP and isolated MySQL batch acceptance. SQL creates explicitly synthetic fixtures only.

Run normally with the worker enabled. --prepare-restart requires the worker disabled;
then restart the actual server with it enabled and run --verify-restart.
"""
from pathlib import Path
from decimal import Decimal
import argparse, concurrent.futures, datetime, http.client, json, secrets, sys, time, urllib.request, urllib.error
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/pydeps'))
import pymysql
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--prepare-restart',action='store_true');parser.add_argument('--verify-restart',action='store_true')
args=parser.parse_args()
c=json.loads((ROOT/'.local/auth-dev/private.json').read_text(encoding='utf-8'))
assert (c['host'],c['port'],c['database'])==('127.0.0.1',33320,'campus_pi_auth_local')
db=pymysql.connect(host=c['host'],port=c['port'],user='root',password=c['root_password'],database=c['database'],charset='utf8mb4',autocommit=True)
with db.cursor() as cur:
    cur.execute('SELECT @@datadir,@@port');directory,port=cur.fetchone()
    assert Path(directory).resolve()==(ROOT/'.local/auth-dev/mysql-data').resolve() and port==33320
    cur.execute("SET time_zone='+00:00'")
out=ROOT/'.local/auth-dev/admin-batches';out.mkdir(parents=True,exist_ok=True)
checks=[];finished=False;tag=secrets.token_hex(4)
def check(value,name):
    checks.append({'name':name,'passed':bool(value)})
    if not value:raise AssertionError(name)
def sql(query,params=(),rows=False):
    with db.cursor() as cur:
        cur.execute(query,params)
        if cur.description:return cur.fetchall() if rows else cur.fetchone()[0]
        return cur.lastrowid
def call(route,method='GET',body=None,token=None,key=None):
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if body is not None:headers['Content-Type']='application/json'
    if key:headers['Idempotency-Key']=key
    request=urllib.request.Request('http://127.0.0.1:8080/api/v1'+route,method=method,headers=headers,data=json.dumps(body).encode() if body is not None else None)
    try:response=urllib.request.urlopen(request,timeout=30)
    except urllib.error.HTTPError as error:response=error
    return response.status,json.loads(response.read())
def login(name,password):
    status,r=call('/auth/login','POST',{'account':name,'password':password});check(status==200,'Real login succeeds');return r['data']['access_token']
def user(label,admin=False):
    name='b_'+label+'_'+tag;password=secrets.token_urlsafe(20)
    status,r=call('/auth/register','POST',{'username':name,'email':name+'@example.invalid','password':password},key=secrets.token_hex(16))
    check(status==201,'Register synthetic '+label);uid=r['data']['user_id']
    if admin:sql("UPDATE users SET role='ADMIN' WHERE user_id=%s",(uid,))
    return uid,login(name,password),name
def submit(kind,ids,choice='通过',reason='批量验收',token=None,key=None):
    body={'ids':ids,('account_status' if kind=='users' else 'decision'):choice,'reason':reason}
    status,r=call('/admin/batch-operations/'+kind,'POST',body,token or admin,key or secrets.token_hex(16))
    check(status==202,'Batch is durably accepted: '+kind+' (HTTP '+str(status)+', '+r.get('code','')+')')
    return r['data'],body
def wait(job,token=None):
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        status,r=call('/admin/batch-operations/'+job['batch_id'],token=token or admin)
        check(status==200,'Owner can query batch result')
        if r['data']['status']=='COMPLETED':return r['data']
        time.sleep(.25)
    raise AssertionError('Batch did not complete')
def product(owner,category,label):
    return str(sql("INSERT INTO product_info(user_id,category_id,product_name,campus,original_price,discount_price,stock_quantity) VALUES(%s,%s,%s,'测试校区',20,10,2)",(owner,category,'批量_'+label+'_'+tag)))
def task(owner,label,type='积分',funded=True):
    # Fixture models a publication transaction. API036 user publication remains outside this delivery.
    db.begin()
    try:
        id=str(sql("INSERT INTO RewardTasks(publisher_id,task_name,task_description,campus,reward_amount,reward_type) VALUES(%s,%s,'合成批量验收','测试校区',20,%s)",(owner,'批量_'+label+'_'+tag,type)))
        if funded:
            sql('UPDATE reward_accounts SET available_amount=available_amount-20,frozen_amount=frozen_amount+20 WHERE user_id=%s AND reward_type=%s',(owner,type))
            sql("INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(%s,%s,%s,%s,'冻结',-20,20)",('FREEZE:'+id,id,owner,type))
        db.commit();return id
    except:db.rollback();raise
def audit_count(type,id):return sql('SELECT COUNT(*) FROM admin_audit_log WHERE object_type=%s AND object_id=%s',(type,id))
def result(job,id):return next(row for row in job['items'] if row['target_id']==id)
report='restart-preparation.json' if args.prepare_restart else 'restart-acceptance.json' if args.verify_restart else 'http-acceptance.json'
try:
    admin=login(c['admin_username'],c['admin_password'])
    admin_id=str(sql('SELECT user_id FROM users WHERE username=%s',(c['admin_username'],)))
    if args.verify_restart:
        fixtures=json.loads((out/'restart-fixtures.json').read_text(encoding='utf-8'))
        done=wait(fixtures['job']);check(done['succeeded']==1 and done['failed']==1,'Persisted batch resumes after actual Java process restart')
        check(sql('SELECT account_status FROM users WHERE user_id=%s',(fixtures['target'],))=='DISABLED','Restarted worker performs persisted status change')
        check(audit_count('USER',fixtures['target'])==fixtures['audit_before']+1,'Restart produces exactly one business audit')
        status,r=call('/admin/batch-operations/users','POST',fixtures['body'],admin,fixtures['key'])
        check(status==202 and r['data']==done,'Original request replay after restart retains success and failure')
        for name,expected in [('revoked','UNAUTHENTICATED'),('demoted','FORBIDDEN')]:
            job=fixtures[name];deadline=time.monotonic()+30
            while time.monotonic()<deadline and sql("SELECT COUNT(*) FROM admin_batch_items WHERE job_id=%s AND item_status='PENDING'",(job['batch_id'],)):time.sleep(.2)
            check(sql("SELECT COUNT(*) FROM admin_batch_items WHERE job_id=%s AND item_status='FAILED' AND error_code=%s",(job['batch_id'],expected))==1,'Worker revalidates '+name+' origin session/role')
            check(sql('SELECT account_status FROM users WHERE user_id=%s',(fixtures[name+'_target'],))=='ACTIVE','Invalid owner cannot mutate remaining items: '+name)
    elif args.prepare_restart:
        target,_,_=user('restart');key=secrets.token_hex(16);before=audit_count('USER',target)
        job,body=submit('users',[target,admin_id],'DISABLED',key=key)
        fixtures={'target':target,'key':key,'job':job,'body':body,'audit_before':before}
        for label in ['revoked','demoted']:
            actor,token,_=user(label,True);victim,_,_=user(label+'_target')
            job,_=submit('users',[victim],'DISABLED',token=token)
            if label=='revoked':sql('UPDATE auth_sessions SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=%s',(actor,))
            else:sql("UPDATE users SET role='USER' WHERE user_id=%s",(actor,))
            fixtures[label]=job;fixtures[label+'_target']=victim
        time.sleep(1.5)
        check(sql("SELECT COUNT(*) FROM admin_batch_items WHERE job_id=%s AND item_status='PENDING'",(fixtures['job']['batch_id'],))==2,'Worker disabled: batch remains durable without premature execution')
        (out/'restart-fixtures.json').write_text(json.dumps(fixtures),encoding='utf-8')
    else:
        owner,ordinary,owner_name=user('owner');other_id,other_admin,_=user('other_admin',True)
        target,target_token,target_name=user('target');missing='9999999999999999'
        for route in ['/admin/batch-operations?kind=users','/admin/batch-operations/unknown']:
            check(call(route)[0]==401,'Anonymous batch query rejected')
            check(call(route,token=ordinary)[0]==403,'Ordinary member batch query rejected')
        good={'ids':[target],'account_status':'DISABLED','reason':'validate'}
        endpoint='/admin/batch-operations/users'
        check(call(endpoint,'POST',good,ordinary,secrets.token_hex(16))[0]==403,'Ordinary member cannot create batch')
        for changes in [{'ids':[]},{'ids':[target]*51},{'ids':[target,target]},{'ids':[int(target)]},{'ids':['0']},{'ids':['9223372036854775808']},{'reason':'   '},{'account_status':'ADMIN'},{'operator_id':admin_id}]:
            check(call(endpoint,'POST',{**good,**changes},admin,secrets.token_hex(16))[0]==400,'Malformed batch rejected: '+','.join(changes))
        check(call(endpoint,'POST',good,admin)[0]==400,'Idempotency key required')
        check(call('/admin/batch-operations?kind=roles',token=admin)[0]==400,'Unsupported batch history kind rejected')
        key=secrets.token_hex(16);before=audit_count('USER',target)
        job,body=submit('users',[target,admin_id,missing],'DISABLED',reason='逐条禁用'+tag,key=key)
        done=wait(job)
        check((done['succeeded'],done['failed'],done['pending'])==(1,2,0),'User batch preserves valid success beside self-disable and missing-ID failures')
        check(result(done,admin_id)['http_status']==409 and result(done,missing)['http_status']==404,'Per-item failure codes are retained')
        check(sql('SELECT account_status FROM users WHERE user_id=%s',(target,))=='DISABLED','User status actually persisted')
        check(call('/me',token=target_token)[0]==401,'Disabled user old session is invalidated')
        status,r=call(endpoint,'POST',dict(reversed(list(body.items()))),admin,key)
        check(status==202 and r['data']==done and audit_count('USER',target)==before+1,'Canonical replay preserves results without duplicate audit')
        check(call(endpoint,'POST',{**body,'reason':'different'},admin,key)[0]==409,'Same batch key with different body rejected')
        check(call('/admin/batch-operations/'+job['batch_id'],token=other_admin)[0]==404,'Another administrator cannot inspect owner batch')
        status,r=call('/admin/batch-operations?kind=users',token=other_admin)
        check(status==200 and all(x['batch_id']!=job['batch_id'] for x in r['data']['items']),'Recent batch list is scoped to owner')
        check(not any(x in json.dumps(done) for x in ['session_hash','request_digest','token_hash','password_hash','address','phone']),'Public batch result omits credentials and private resource snapshots')
        enabled,_=submit('users',[target],'ACTIVE');check(wait(enabled)['succeeded']==1,'Bulk enable is implemented too')
        # Concurrent same-key submissions reserve one durable job and one item per target.
        concurrent_key=secrets.token_hex(16);concurrent_body={**good,'reason':'并发'+tag}
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            responses=list(pool.map(lambda _:call(endpoint,'POST',concurrent_body,admin,concurrent_key),range(4)))
        check(all(status==202 for status,_ in responses) and len({r['data']['batch_id'] for _,r in responses})==1,'Concurrent same-key requests create exactly one batch')
        done=wait(responses[0][1]['data']);check(done['succeeded']==1 and audit_count('USER',target)==before+3,'Concurrent replay mutates and audits once')
        category=sql("INSERT INTO category(category_name,category_level) VALUES(%s,1)",('批量验收'+tag,))
        p1,p2,p3=[product(owner,category,label) for label in ['first','already','last']]
        sql("UPDATE product_info SET audit_status='已通过',shelf_status='在售' WHERE product_id=%s",(p2,))
        job,_=submit('products',[p1,p2,missing,p3]);done=wait(job)
        check((done['succeeded'],done['failed'])==(2,2),'Product conflict does not stop later successful item')
        check(sql('SELECT audit_status FROM product_info WHERE product_id=%s',(p3,))=='已通过','Later product actually approved')
        # A failure while saving the success result must also roll back original state, audit and replay.
        rollback=product(owner,category,'rollback');later=product(owner,category,'after_failure');trigger='batch_failure_'+tag
        try:
            sql("CREATE TRIGGER `"+trigger+"` BEFORE UPDATE ON admin_batch_items FOR EACH ROW BEGIN IF NEW.target_id='"+rollback+"' AND NEW.item_status='SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic private database failure'; END IF; END")
            job,_=submit('products',[rollback,later]);done=wait(job)
            check((done['succeeded'],done['failed'])==(1,1) and result(done,rollback)['error_code']=='DEPENDENCY_UNAVAILABLE','One database fault becomes isolated per-item failure')
            check(sql('SELECT audit_status FROM product_info WHERE product_id=%s',(rollback,))=='待审核' and audit_count('商品',rollback)==0,'Result persistence failure rolls back business and audit')
            check(sql('SELECT COUNT(*) FROM auth_idempotency WHERE request_key=%s',(job['batch_id']+'_1',))==0,'Failure rolls back original single-item replay too')
            check('synthetic private' not in json.dumps(done),'Database diagnostic text never leaks into batch result')
        finally:sql('DROP TRIGGER IF EXISTS `'+trigger+'`')
        # Old failures are immutable. An explicit new key retries only selected failure IDs.
        status,r=call('/admin/batch-operations/'+job['batch_id'],token=admin)
        check(r['data']==done,'Dependency recovery does not silently re-execute a failed batch')
        retry,_=submit('products',[row['target_id'] for row in done['items'] if row['item_status']=='FAILED']);retried=wait(retry)
        check(retried['succeeded']==1 and audit_count('商品',later)==1 and audit_count('商品',rollback)==1,'New retry batch only executes failures, preserving earlier successes')
        posts=[]
        for i in range(3):
            id='BATCH_POST_'+tag+'_'+str(i);posts.append(id)
            sql("INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content,is_anonymous) VALUES(%s,%s,%s,%s,'合成匿名正文',1)",(id,owner,category,'批量帖子'+tag))
        sql("UPDATE post_info SET post_status='已发布' WHERE post_id=%s",(posts[1],))
        job,_=submit('posts',posts,'驳回');done=wait(job)
        check((done['succeeded'],done['failed'])==(2,1) and sql('SELECT post_status FROM post_info WHERE post_id=%s',(posts[2],))=='已驳回','Post review shares independent transaction behavior')
        sql("UPDATE users SET identity_verification='已通过',campus_verification='已通过',campus='测试校区',student_no=%s WHERE user_id=%s",('BATCH'+owner,owner))
        sql("INSERT INTO approved_campus_registry VALUES('测试校区',%s,%s)",('BATCH'+owner,owner))
        sql('UPDATE reward_accounts SET available_amount=1000 WHERE user_id=%s',(owner,))
        approved=task(owner,'approve');unbacked=task(owner,'unbacked',funded=False);cash=task(owner,'cash','现金');approved2=task(owner,'approve2')
        job,_=submit('tasks',[approved,unbacked,cash,approved2]);done=wait(job)
        check((done['succeeded'],done['failed'])==(2,2),'Task publication batch preserves eligibility and per-task frozen ledger checks')
        check(result(done,cash)['http_status']==501 and sql('SELECT task_status FROM RewardTasks WHERE reward_task_id=%s',(cash,))=='待审核','Cash switch cannot be bypassed in batch')
        rejected=[task(owner,'reject1'),task(owner,'reject2')]
        before=sql("SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=%s AND reward_type='积分'",(owner,),True)[0]
        key=secrets.token_hex(16);job,body=submit('tasks',[rejected[0],approved,rejected[1]],'驳回',key=key);done=wait(job)
        after=sql("SELECT available_amount,frozen_amount FROM reward_accounts WHERE user_id=%s AND reward_type='积分'",(owner,),True)[0]
        check((done['succeeded'],done['failed'])==(2,1) and after==(before[0]+Decimal(40),before[1]-Decimal(40)),'Task rejection releases exactly the two successful frozen rewards')
        check(call('/admin/batch-operations/tasks','POST',body,admin,key)[0]==202 and all(sql('SELECT COUNT(*) FROM reward_ledger WHERE event_key=%s',('RELEASE:'+id,))==1 for id in rejected),'Task batch replay cannot release reward twice')
        limit_ids=[product(owner,category,'limit'+str(index)) for index in range(50)]
        job,_=submit('products',limit_ids);done=wait(job)
        check(done['total']==50 and done['succeeded']==50 and done['failed']==0,'Valid 50-item batch spans multiple worker polls without losing items')
        placeholders=','.join(['%s']*50)
        check(sql("SELECT COUNT(*) FROM admin_audit_log WHERE object_type='商品' AND object_id IN ("+placeholders+")",limit_ids)==50,'Every boundary-batch product has exactly one audit across competing workers')
        # Actual transport response loss: resend the same key to discover the accepted job.
        loss=product(owner,category,'lost-response');loss_key=secrets.token_hex(16);loss_body={'ids':[loss],'decision':'通过','reason':'丢失响应'+tag}
        connection=http.client.HTTPConnection('127.0.0.1',8080,timeout=20)
        connection.request('POST','/api/v1/admin/batch-operations/products',json.dumps(loss_body),{'Authorization':'Bearer '+admin,'Content-Type':'application/json','Idempotency-Key':loss_key})
        response=connection.getresponse();check(response.status==202,'Batch accepted before client discards response');connection.close()
        status,r=call('/admin/batch-operations/products','POST',loss_body,admin,loss_key);done=wait(r['data'])
        check(status==202 and done['succeeded']==1 and audit_count('商品',loss)==1,'Lost-response retry recovers same durable batch exactly once')
        # Browser fixtures are new pending resources, not claims that user-side create APIs exist.
        browser_products=[product(owner,category,'browser'+str(i)) for i in range(3)]
        browser_post='BATCH_BROWSER_'+tag
        sql("INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content) VALUES(%s,%s,%s,%s,'合成浏览器帖子')",(browser_post,owner,category,'批量浏览器帖子'+tag))
        browser_task=task(owner,'browser')
        browser_user,browser_token,browser_name=user('browser')
        fixtures={'tag':tag,'products':browser_products,'post':browser_post,'task':browser_task,'user':browser_user,'username':browser_name,'owner':owner,'category':category}
        (out/'fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False),encoding='utf-8')
    finished=True
    print(json.dumps({'status':'PASS','checks':len(checks),'scope':report}))
finally:
    (out/report).write_text(json.dumps({'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'status':'PASS' if finished else 'INCOMPLETE','count':len(checks),'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
    db.close()
