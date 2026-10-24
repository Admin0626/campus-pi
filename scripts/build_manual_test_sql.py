"""Build deterministic synthetic fixtures for the fixed, isolated manual-test schema.
No database connection, real credentials, WeChat identity or provider success is generated.
"""
from pathlib import Path
import hashlib, json, struct, uuid, zlib
from argon2.low_level import hash_secret, Type
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'.local/manual-test/generated'
DB='campus_pi_manual_test'
SQL=[]
class Raw(str): pass
def value(v):
    if isinstance(v,Raw): return str(v)
    if v is None:return 'NULL'
    if isinstance(v,(int,float)):return str(v)
    if isinstance(v,(dict,list)):v=json.dumps(v,ensure_ascii=False,separators=(',',':'))
    return "'"+str(v).replace('\\','\\\\').replace("'","''")+"'"
def insert(table,**row):
    SQL.append('INSERT INTO '+DB+'.`'+table+'` ('+','.join('`'+k+'`' for k in row)+') VALUES ('+','.join(value(v) for v in row.values())+');')
def update(table,assignment,where):SQL.append('UPDATE '+DB+'.`'+table+'` SET '+assignment+' WHERE '+where+';')
T=Raw("'2026-10-20 08:00:00.000'")
def password(text):return hash_secret(text.encode(),b'CampusPiManualV1!',2,19456,1,32,Type.ID).decode()
def png(color):
    w,h=240,160
    raw=b''.join(b'\0'+b''.join(bytes((245,245,248) if 65<x<175 and 25<y<135 and (x<70 or x>168 or y<30 or y>128) else ((255,255,255) if 76<x<164 and 34<y<126 else color)) for x in range(w)) for y in range(h))
    def chunk(kind,data):return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',w,h,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b'')
def file(owner,purpose,name,object_type,object_id,color=(112,103,222)):
    fid=str(uuid.uuid5(uuid.NAMESPACE_URL,'campus-pi-manual-v1/'+name));data=png(color);digest=hashlib.sha256(data).hexdigest()
    insert('manual_test_assets',file_id=fid,filename=name+'.png',content=Raw('0x'+data.hex()),sha256=digest)
    insert('business_files',file_id=fid,owner_id=owner,purpose=purpose,mime_type='image/png',size_bytes=len(data),sha256=digest,scan_status='CLEAN',scanner_name='TEST_FIXTURE',scanned_at=T,created_at=T,unbound_expires_at=Raw('DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 365 DAY)'))
    insert('business_file_bindings',file_id=fid,object_type=object_type,object_id=str(object_id),bound_at=T)
    return 'file://'+fid

def users():
    names=['admin','manual_admin_limited','manual_seller','manual_buyer','manual_peer','manual_unverified','manual_campus_pending','manual_optout','manual_dnd','manual_other_campus','manual_password','manual_disabled','manual_empty_wallet']
    for i,name in enumerate(names,900001):
        verified=i not in (900006,900007,900011)
        campus='测试分校区' if i==900010 else '测试校区'
        insert('users',user_id=i,username=name,password_hash=password('123456' if i==900001 else 'CampusPi!2026'),email=name+'@example.invalid',role='ADMIN' if i in (900001,900002) else 'USER',account_status='DISABLED' if i==900012 else 'ACTIVE',campus=campus if verified or i==900007 else None,student_no='MTEST'+str(i) if verified or i==900007 else None,real_name='合成测试用户'+str(i),identity_verification='已通过' if verified or i==900007 else '未认证',campus_verification='已通过' if verified else ('待审核' if i==900007 else '未认证'),registration_time=T,address='合成自提交接点',personal_bio='人工测试专用合成账号')
        if verified:insert('approved_campus_registry',campus=campus,student_no='MTEST'+str(i),user_id=i)
        insert('notification_preferences',user_id=i,in_app_enabled=0 if i==900008 else 1,email_enabled=0,sms_enabled=0,dnd_start='00:00:00' if i==900009 else None,dnd_end='23:59:00' if i==900009 else None,config_version=0)
    for i,state,owner,typ in [(900001,'待审核',900006,'实名'),(900002,'待审核',900007,'校园'),(900003,'已通过',900003,'实名'),(900004,'已驳回',900006,'实名')]:
        ref=file(owner,'VERIFICATION','verification-'+str(i),'VERIFICATION',i)
        insert('user_verification',verification_id=i,user_id=owner,verification_type=typ,campus='测试校区' if typ=='校园' else None,student_no='MTEST'+str(owner) if typ=='校园' else None,real_name='合成测试用户'+str(owner),evidence_urls=[ref],audit_status=state,reviewer_id=900001 if state!='待审核' else None,audit_reason='合成历史审核' if state!='待审核' else None,submitted_at=T,reviewed_at=T if state!='待审核' else None)
    avatar=file(900003,'AVATAR','seller-avatar','AVATAR',900003)
    update('users','avatar_url='+value(avatar),'user_id=900003')

def content():
    for id,name,parent in [(900001,'人工测试-学习',None),(900002,'人工测试-数码',None),(900011,'教材图书',900001),(900012,'校园互助',900001),(900013,'电脑配件',900002),(900099,'可删除的空分类',None)]:
        insert('category',category_id=id,category_name=name,category_level='2' if parent else '1',parent_category_id=parent,category_description='人工测试字典',created_time=T,updated_time=T)
    products=[('在售教材','已通过','在售',10,900003,'20.00'),('待审核鼠标','待审核','已下架',3,900003,'30.00'),('驳回商品','已驳回','已下架',1,900003,'40.00'),('本人已下架','已通过','已下架',2,900003,'50.00'),('已售罄商品','已通过','已售出',0,900003,'60.00'),('仅一件库存','已通过','在售',1,900003,'70.00'),('停用卖方隐藏商品','已通过','在售',1,900012,'80.00'),('不同校区商品','已通过','在售',2,900010,'90.00'),('零元赠送','已通过','在售',1,900003,'0.00')]
    for i,(name,audit,shelf,stock,owner,price) in enumerate(products,900001):
        insert('product_info',product_id=i,user_id=owner,category_id=900011 if i%2 else 900013,product_name='人工测试-'+name,product_desc='独立测试数据，可用于浏览、收藏、审核和校园自提。',campus='测试分校区' if owner==900010 else '测试校区',location_label='合成交接点A',trade_mode='校园自提',original_price=price,discount_price=price,stock_quantity=stock,audit_status=audit,shelf_status=shelf,reviewer_id=900001 if audit!='待审核' else None,audit_reason='历史测试审核' if audit!='待审核' else None,audited_at=T if audit!='待审核' else None,create_time=T,updated_at=T)
        ref=file(owner,'PRODUCT_IMAGE','product-'+str(i),'PRODUCT',i,(88+i%3*30,140,195))
        insert('product_media',product_id=i,image_url=ref,sort_order=0)
    insert('product_favor',product_id=900001,user_id=900004,favor_time=T)
    for i,(state,anon) in enumerate([('已发布',0),('待审核',0),('已发布',1),('已驳回',0),('已下架',0),('已删除',0)]):
        pid='MANUAL_POST_'+str(i+1)
        insert('post_info',post_id=pid,publisher_id=900003,category_id=900012,post_title='人工测试-'+state+('匿名帖' if anon else '校园帖')+str(i+1),post_content='这是人工测试帖子，特殊文本 <script>literal</script> 应按文字显示。',is_anonymous=anon,post_status=state,publish_time=T,view_count=i)
    insert('post_collection',user_id=900004,post_id='MANUAL_POST_1',collection_time=T,collection_status='已收藏')
    insert('post_collection',user_id=900004,post_id='MANUAL_POST_5',collection_time=T,collection_status='失效')

def tasks():
    states=['待审核','待接取','进行中','待验收','已完成','未完成','已取消','已驳回','进行中','待验收']
    insert('reward_accounts',user_id=900003,reward_type='积分',available_amount='840.00',frozen_amount='140.00')
    insert('reward_accounts',user_id=900004,reward_type='积分',available_amount='20.00',frozen_amount='0.00')
    for owner in [900005,900010,900013]:insert('reward_accounts',user_id=owner,reward_type='积分',available_amount='0.00',frozen_amount='0.00')
    insert('reward_accounts',user_id=900003,reward_type='现金',available_amount='0.00',frozen_amount='10.00')
    for i,state in enumerate(states,900001):
        assigned=state in ('进行中','待验收','已完成','未完成')
        insert('RewardTasks',reward_task_id=i,publisher_id=900003,taker_id=900004 if assigned else None,task_name='人工测试-'+state+'任务'+str(i),task_description='代取合成测试资料，提交照片凭证。',campus='测试校区',reward_amount='20.00',reward_type='积分',task_status=state,publish_time=T,accepted_at=T if assigned else None,submitted_at=T if state in ('待验收','已完成','未完成') else None,completed_at=T if state=='已完成' else None,cancel_reason='合成取消历史' if state=='已取消' else None,version=0)
        insert('reward_ledger',event_key='FREEZE:'+str(i),reward_task_id=i,user_id=900003,reward_type='积分',event_type='冻结',available_delta='-20.00',frozen_delta='20.00',created_at=T)
        if state in ('已取消','已驳回'):insert('reward_ledger',event_key='RELEASE:'+str(i),reward_task_id=i,user_id=900003,reward_type='积分',event_type='解冻',available_delta='20.00',frozen_delta='-20.00',created_at=T)
        insert('task_audit_log',reward_task_id=i,operator_id=900001,action_name='合成状态快照',from_status=None,to_status=state,reason='人工测试初始状态，不代表实际履约',operated_at=T)
        if assigned:
            insert('task_applications',reward_task_id=i,applicant_id=900004,application_status='已通过',reviewer_id=900001,audit_reason='合成分配历史',applied_at=T,reviewed_at=T)
            insert('user_orders',order_id='ORDER260101'+str(91000000+i-900000),order_type='任务',user_id=900003,seller_id=900004,reward_task_id=i,item_name='人工测试-'+state+'任务'+str(i),quantity=1,unit_price='20.00',total_amount='20.00',actual_amount='20.00' if state=='已完成' else '0.00',order_status=state,payment_method='奖励账户' if state=='已完成' else None,order_time=T,completion_time=T if state=='已完成' else None)
    insert('RewardTasks',reward_task_id=900011,publisher_id=900003,task_name='人工测试-现金关闭边界',task_description='仅用于核对现金开关拒绝，非真实资金',campus='测试校区',reward_amount='10.00',reward_type='现金',task_status='待审核',publish_time=T)
    insert('reward_ledger',event_key='FREEZE:900011',reward_task_id=900011,user_id=900003,reward_type='现金',event_type='冻结',available_delta='-10.00',frozen_delta='10.00',created_at=T)
    for i,(applicant,state) in enumerate([(900004,'待审核'),(900005,'待审核'),(900010,'已取消')],900001):insert('task_applications',application_id=i,reward_task_id=900002,applicant_id=applicant,application_status=state,applied_at=T)
    for i,task,state,no in [(900001,900004,'待审核',1),(900002,900005,'已通过',1),(900003,900006,'未通过',1),(900004,900009,'补充凭证',1),(900005,900010,'补充凭证',1),(900006,900010,'待审核',2)]:
        ref=file(900004,'TASK_PROOF','proof-'+str(i),'TASK_PROOF',i)
        insert('task_proofs',proof_id=i,reward_task_id=task,submitter_id=900004,submission_no=no,proof_text='人工测试凭证第'+str(no)+'版',evidence_urls=[ref],audit_status=state,reviewer_id=900001 if state!='待审核' else None,audit_reason='合成历史验收' if state!='待审核' else None,submitted_at=T,reviewed_at=T if state!='待审核' else None)
    insert('task_settlement',reward_task_id=900005,proof_id=900002,payer_id=900003,payee_id=900004,reward_type='积分',amount='20.00',settlement_status='已记账',settled_at=T)
    insert('reward_ledger',event_key='PAY:900005',reward_task_id=900005,user_id=900003,reward_type='积分',event_type='支付',available_delta='0.00',frozen_delta='-20.00',created_at=T)
    insert('reward_ledger',event_key='INCOME:900005',reward_task_id=900005,user_id=900004,reward_type='积分',event_type='收入',available_delta='20.00',frozen_delta='0.00',created_at=T)

def orders_and_chat():
    for i,state in enumerate(['待支付','已取消','已支付','已发货','待确认收货','已完成','售后中','已退款'],1):
        insert('user_orders',order_id='ORDER260101'+str(90000000+i),order_type='商品',user_id=900004,seller_id=900003,product_id=900001,item_name='人工测试商品订单-'+state,quantity=1,unit_price='20.00',total_amount='20.00',actual_amount='0.00' if state in ('待支付','已取消') else '20.00',order_status=state,payment_method='合成历史快照' if state not in ('待支付','已取消') else None,shipping_address='合成自提交接点B',order_time=T,payment_time=T if state not in ('待支付','已取消') else None,completion_time=T if state in ('已完成','已退款') else None)
    for i,task,state in [(1,900003,'待审核'),(2,900005,'已完成'),(3,900004,'已取消'),(4,900006,'已驳回')]:
        aid='AFTER260101'+str(90000000+i);oid='ORDER260101'+str(91000000+task-900000)
        ref=file(900003,'AFTERSALE','aftersale-'+str(i),'AFTERSALE',aid)
        original={900003:'进行中',900005:'已完成',900004:'待验收',900006:'未完成'}[task]
        insert('order_aftersales',aftersale_id=aid,order_id=oid,user_id=900003,aftersale_status=state,apply_amount='0.00',approve_amount='0.00' if state=='已完成' else None,apply_reason='合成任务零金额争议-'+state,evidence_urls=[ref],reviewer_id=900001 if state in ('已完成','已驳回') else None,audit_reason='合成处理历史' if state in ('已完成','已驳回') else None,apply_time=T,audit_time=T if state in ('已完成','已驳回') else None,complete_time=T if state=='已完成' else None)
        insert('aftersale_order_context',aftersale_id=aid,order_id=oid,order_type='任务',original_order_status=original,captured_at=T)
    # New OFFLINE states, distinct from preserved historical payment snapshots above.
    for i,state in [(9,'待线下交易'),(10,'待确认收货'),(11,'已完成'),(12,'已取消')]:
        insert('user_orders',order_id='ORDER260101'+str(90000000+i),order_type='商品',user_id=900004,seller_id=900003,product_id=900001,item_name='人工测试线下订单-'+state,quantity=1,unit_price='20.00',total_amount='20.00',actual_amount='0.00',order_status=state,trade_channel='OFFLINE',shipping_address='合成线下交接点B',order_time=T,shipping_time=T if state in ('待确认收货','已完成') else None,completion_time=T if state=='已完成' else None)
    # Preserved historical product case is read-only; the platform never executes refunds.
    insert('order_aftersales',aftersale_id='AFTER26010190000005',order_id='ORDER26010190000007',user_id=900004,aftersale_status='待审核',apply_amount='20.00',apply_reason='合成商品退款待接入边界',evidence_urls=[],apply_time=T)
    insert('aftersale_order_context',aftersale_id='AFTER26010190000005',order_id='ORDER26010190000007',order_type='商品',original_order_status='已完成',captured_at=T)
    insert('t_chat_session',session_id=900001,sender_id=900003,receiver_id=900004,created_at=T)
    insert('t_chat_session',session_id=900002,sender_id=900003,receiver_id=900005,created_at=T)
    for i in range(1,13):
        sender=900003 if i%2==0 else 900004;receiver=900004 if sender==900003 else 900003
        insert('t_private_msg',msg_id=900000+i,session_id=900001,sender_id=sender,receiver_id=receiver,content_type='文本',content='人工测试消息 '+str(i)+' <script>按文本显示</script>',send_time=T,read_at=T if i<5 else None,sender_deleted=1 if i==2 else 0,receiver_deleted=1 if i==3 else 0)
    ref=file(900004,'CHAT_IMAGE','chat-image','MESSAGE',900013)
    insert('t_private_msg',msg_id=900013,session_id=900001,sender_id=900004,receiver_id=900003,content_type='图片',content=ref,send_time=T)
    for i,msg,state in [(900001,900001,'待审核'),(900002,900005,'成立'),(900003,900007,'不成立')]:
        insert('t_msg_report',report_id=i,msg_id=msg,reporter_id=900003,reason='人工测试举报-'+state,audit_status=state,reviewer_id=900001 if state!='待审核' else None,audit_reason='合成审核结果' if state!='待审核' else None,report_time=T,reviewed_at=T if state!='待审核' else None)

def notifications_and_batches():
    for id,state,typ in [('MANUAL_DRAFT','草稿','系统'),('MANUAL_NOTICE_SENT','已发送','系统'),('MANUAL_NOTICE_REVOKED','已撤回','安全'),('MANUAL_NOTICE_WAIT','待发送','审核')]:
        insert('system_notifications',notification_id=id,notification_title='人工测试-'+state+'通知',notification_content='仅用于人工测试的纯文本通知 <script>不得执行</script>',notification_type=typ,publisher_id=900001,target_scope='指定用户',target_filter={'user_ids':['900003','900004','900008','900009']},publish_status=state,publish_time=None if state=='草稿' else T)
        if state=='草稿':continue
        insert('notification_publish_jobs',job_id='manual-job-'+id,notification_id=id,recipient_count=3,job_status='已完成' if state=='已发送' else ('已取消' if state=='已撤回' else '待执行'),created_at=T,completed_at=T if state in ('已发送','已撤回') else None)
        for owner in [900003,900004,900009]:
            insert('notification_receipts',notification_id=id,user_id=owner,received_at=T,read_at=T if owner==900003 else None,clicked_at=T if owner==900003 else None,is_deleted=0)
    deliveries=[(900001,'MANUAL_NOTICE_SENT',900004,'成功',None,'已完成'),(900002,'MANUAL_NOTICE_SENT',900003,'失败','合成失败，供实际重试','已完成'),(900003,'MANUAL_NOTICE_WAIT',900009,'待发送',None,'待执行'),(900004,'MANUAL_NOTICE_REVOKED',900004,'失败','通知已撤回','已取消')]
    for i,id,owner,status,reason,work in deliveries:
        insert('notification_delivery',delivery_id=i,notification_id=id,user_id=owner,channel='站内',delivery_status=status,attempt_no=1,provider_reference='SYNTHETIC_FIXTURE' if status=='成功' else None,failure_reason=reason,sent_at=T if status=='成功' else None)
        insert('notification_outbox',delivery_id=i,notification_id=id,work_status=work,created_at=T,completed_at=T if work!='待执行' else None,available_at=Raw('DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY)') if work=='待执行' else T,preference_version=0)
    job=str(uuid.uuid5(uuid.NAMESPACE_URL,'campus-pi-manual-batch-v1'))
    insert('admin_batch_jobs',job_id=job,operator_id=900001,session_hash='0'*64,auth_version=0,request_key='manual-completed-history',request_digest='0'*64,operation_kind='products',action_body={'decision':'通过','reason':'合成已结束批次'},created_at=T)
    for no,target,status,code in [(1,'900001','SUCCEEDED',200),(2,'900003','FAILED',409)]:insert('admin_batch_items',job_id=job,item_no=no,target_id=target,item_status=status,http_status=code,error_code='STATE_CONFLICT' if code==409 else None,result_message='合成历史结果，实际新批次须从页面提交',completed_at=T)
    for i,(typ,obj) in enumerate([('USER','900012'),('商品','900001'),('帖子','MANUAL_POST_1'),('任务','900005'),('通知','MANUAL_NOTICE_SENT')],900001):
        insert('admin_audit_log',audit_id=i,operator_id=900001,object_type=typ,object_id=obj,action_name='合成历史记录',reason='人工测试预置，非真实人工操作',created_at=T)
    insert('business_number_sequences',number_type='ORDER:260101',counter_value=91000100)

def cases():
    spec=json.loads((ROOT/'docs/api/campus-pi-openapi.json').read_text(encoding='utf-8'))
    allocation=json.loads((ROOT/'docs/allocation/接口归属.json').read_text(encoding='utf-8'))
    owners={id:p['code'] for p in allocation['packages'] for id in p['ids']}
    blocked={'API004','API005'}
    parameters={'user_id':'900012','file_id':str(uuid.uuid5(uuid.NAMESPACE_URL,'campus-pi-manual-v1/product-900001')),'product_id':'900001','post_id':'MANUAL_POST_1','reward_task_id':'900003','application_id':'900001','proof_id':'900001','order_id':'ORDER26010190000001','aftersale_id':'AFTER26010190000001','session_id':'900001','msg_id':'900001','notification_id':'MANUAL_NOTICE_SENT','verification_id':'900001','category_id':'900099','report_id':'900001','delivery_id':'900002'}
    result=[]
    for route,entry in spec['paths'].items():
        for method,op in entry.items():
            if not isinstance(op,dict) or not op.get('operationId'):continue
            oid=op['operationId'];example=route
            override={}
            if oid in ('API049','API050','API097'):override['order_id']='ORDER26010190000009'
            if oid=='API051':override['order_id']='ORDER26010190000010'
            if oid=='API080':override['product_id']='900002'
            if oid=='API086':override['post_id']='MANUAL_POST_2'
            if oid in ('API089','API094','API037','API038'):override['reward_task_id']='900002' if oid!='API089' else '900001'
            if oid in ('API105','API106','API107'):override['notification_id']='MANUAL_DRAFT'
            if oid in ('API066','API067','API068','API069'):override['notification_id']='MANUAL_NOTICE_SENT'
            for key,val in {**parameters,**override}.items():example=example.replace('{'+key+'}',val)
            state='EXTERNAL_NOT_IMPLEMENTED' if oid in blocked else ('PARTIAL_TASK_ONLY' if oid in ('API053','API099') else 'MANUAL_CASE_READY')
            expected='501，且无副作用' if oid in blocked else ('任务0.00分支可用；商品线下协商，平台拒绝退款' if oid in ('API053','API099') else '/'.join(k for k in op['responses'] if k.startswith('2')))
            actor='admin（900001）' if '/admin/' in route else '按所属对象选择manual_seller/manual_buyer'
            note='使用对应合成对象，成功流程后检查数据库状态。权限负例用manual_peer或manual_admin_limited。修改类操作按手册填写请求体及新幂等键。'
            if method.lower()=='post' and 'files' in route:note='必须从页面实际上传新文件，预置扫描字段不代替真实扫描验收。'
            if oid in ('API018','API027','API036','API046','API057','API104'):note='创建新对象后记录返回ID，后续修改/审核使用新ID，不能反复消费同一历史夹具。'
            if oid=='API041':note='manual_buyer为任务900003实际接取者，先实际上传TASK_PROOF新文件再提交凭证。'
            if oid=='API008':note='使用无业务关联的manual_password测试，改密后旧令牌应失效，记录新口令。'
            row={'case_id':oid,'module':owners[oid],'method':method.upper(),'path':'/api/v1'+example,'purpose':op.get('summary',''),'actor':actor,'expected':expected,'readiness':state,'steps':note}
            insert('manual_test_cases',**row);result.append(row)
    for id,path,purpose in [('EXT-REVIEWS','/admin/task-audit-logs','全局任务审核记录筛选与权限'),('EXT-DELIVERIES','/admin/notification-deliveries','全局通知投递查询与职责'),('EXT-BATCH','/admin/batch-operations','四类批量逐条成功/失败结果及重试'),('EXT-WECHAT','/auth/wechat/config','微信能力边界，实际换码需私有配置'),('EXT-PROFILE','/me','已授权原生PUT资料兼容、头像权限和隐私')]:
        row={'case_id':id,'module':'EXT','method':'PUT' if id=='EXT-PROFILE' else 'GET','path':'/api/v1'+path,'purpose':purpose,'actor':'见人工测试手册','expected':'见手册，真实微信不预置成功','readiness':'MANUAL_CASE_READY' if id!='EXT-WECHAT' else 'EXTERNAL_DEPENDENCY','steps':'使用实际界面/接口，检查权限和持久状态。'};insert('manual_test_cases',**row);result.append(row)
    row={'case_id':'EXT-REMOVED-PAYMENT','module':'G','method':'POST','path':'/api/v1/orders/ORDER26010190000009/payment-intents','purpose':'已删除的支付接口不可访问','actor':'manual_buyer','expected':'404，无状态或账本变更','readiness':'REMOVED_ROUTE_NEGATIVE','steps':'携带有效登录令牌调用旧地址；不能出现支付凭证。'};insert('manual_test_cases',**row);result.append(row)
    return sorted(result,key=lambda x:x['case_id'])

def build():
    OUT.mkdir(parents=True,exist_ok=True)
    SQL.clear()
    users();content();tasks();orders_and_chat();notifications_and_batches();matrix=cases()
    header='''-- 校园Π全功能人工测试数据 v1
-- MySQL 8.0.16+；仅用于专用campus_pi_manual_test库，先执行原结构、事务及V001-V009迁移。
-- 推荐运行 scripts/prepare_manual_test.py 安装空库、导入本文件并还原图片。
-- 账号admin/123456仅在生成的私有测试配置中显式允许；其他账号统一CampusPi!2026。
-- 历史付款/退款、积分初始额、文件TEST_FIXTURE均是合成快照，不代表支付或杀毒验收。
-- 不包含真实微信标识、令牌或生产凭证。不关闭外键、不删除旧表、不覆盖现有用户。
SET NAMES utf8mb4;
SET time_zone='+00:00';
CREATE TABLE IF NOT EXISTS campus_pi_manual_test.manual_test_assets (
 file_id CHAR(36) PRIMARY KEY, filename VARCHAR(100) NOT NULL, content LONGBLOB NOT NULL, sha256 CHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS campus_pi_manual_test.manual_test_cases (
 case_id VARCHAR(40) PRIMARY KEY, module VARCHAR(10) NOT NULL, method VARCHAR(10) NOT NULL,
 path VARCHAR(255) NOT NULL, purpose VARCHAR(200) NOT NULL, actor VARCHAR(200) NOT NULL,
 expected VARCHAR(200) NOT NULL, readiness VARCHAR(40) NOT NULL, steps VARCHAR(1000) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS campus_pi_manual_test.campus_manual_seed_v1$$
CREATE PROCEDURE campus_pi_manual_test.campus_manual_seed_v1()
BEGIN
 DECLARE acquired INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  ROLLBACK;
  IF acquired=1 THEN DO RELEASE_LOCK('campus_pi_manual_seed_v1'); END IF;
  RESIGNAL;
 END;
 SELECT GET_LOCK('campus_pi_manual_seed_v1',0) INTO acquired;
 IF acquired<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Another manual seed is running'; END IF;
 IF DATABASE()<>'campus_pi_manual_test' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Dedicated manual schema required'; END IF;
 IF (SELECT COUNT(*) FROM campus_pi_manual_test.users)<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Refusing populated manual database - no overwrite'; END IF;
 IF (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='campus_pi_manual_test' AND TABLE_NAME='user_orders' AND COLUMN_NAME='trade_channel')<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Apply all V001-V009 migrations first'; END IF;
 START TRANSACTION;
'''
    footer='''
 COMMIT;
 DO RELEASE_LOCK('campus_pi_manual_seed_v1');
END$$
CALL campus_pi_manual_test.campus_manual_seed_v1()$$
DELIMITER ;
SELECT case_id,module,method,path,purpose,actor,expected,readiness,steps FROM campus_pi_manual_test.manual_test_cases ORDER BY case_id;
SELECT 'POINT_FROZEN_MATCHES_ACTIVE_TASKS' AS invariant,
 a.frozen_amount=(SELECT SUM(reward_amount) FROM campus_pi_manual_test.RewardTasks WHERE publisher_id=a.user_id AND reward_type=a.reward_type AND task_status IN ('待审核','待接取','进行中','待验收','未完成')) AS passed
 FROM campus_pi_manual_test.reward_accounts a WHERE a.user_id=900003 AND a.reward_type='积分';
SELECT 'OPT_OUT_HAS_NO_RECEIPTS' AS invariant,COUNT(*)=0 AS passed FROM campus_pi_manual_test.notification_receipts WHERE user_id=900008;
SELECT 'TASK_900005_SINGLE_SETTLEMENT' AS invariant,COUNT(*)=1 AS passed FROM campus_pi_manual_test.task_settlement WHERE reward_task_id=900005;
SELECT '111_ACTIVE_CASES_PRESENT' AS invariant,COUNT(*)=111 AS passed FROM campus_pi_manual_test.manual_test_cases WHERE case_id LIKE 'API%';
'''
    (OUT/'校园Π全功能人工测试.sql').write_text(header+'\n'.join(SQL)+footer,encoding='utf-8')
    (OUT/'接口人工测试矩阵.json').write_text(json.dumps(matrix,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    samples=OUT/'上传样例'
    samples.mkdir(exist_ok=True)
    for name,color in [('测试商品',(88,140,195)),('测试头像',(112,103,222)),('测试凭证',(80,160,120)),('测试认证材料',(130,150,180))]:
        (samples/(name+'.png')).write_bytes(png(color))
    print(json.dumps({'sql':(OUT/'校园Π全功能人工测试.sql').relative_to(ROOT).as_posix(),'baseline_api_cases':sum(x['case_id'].startswith('API') for x in matrix),'extension_cases':sum(x['case_id'].startswith('EXT') for x in matrix),'insert_statements':sum(s.startswith('INSERT') for s in SQL),'upload_samples':4,'git_tracked_output':False}))

if __name__=='__main__':build()
