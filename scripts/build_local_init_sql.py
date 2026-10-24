"""Assemble the single-file fresh LOCAL database installer from existing SQL sources.

No database connection. The fixed admin credential and deterministic hash are public
local-test fixtures only; application code still hashes ordinary passwords randomly.
"""
from pathlib import Path
import argparse
import hashlib
from argon2.low_level import Type, hash_secret

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'database/init.sql'


def demo_seed(password_hash):
    """Small synthetic demo only: no copied historical tests, files or WeChat identity."""
    return """
-- Small LOCAL demo. All identities, points and history below are synthetic samples.
-- student01/student02 are pre-verified examples, not a real identity-review result.
-- No identity documents, scan attestations, cash funds or external payment records.
SET @demo_now=UTC_TIMESTAMP(3);
INSERT INTO users(user_id,username,password_hash,email,role,campus,student_no,real_name,
                  identity_verification,campus_verification,personal_bio,privacy_settings) VALUES
 (2,'student01','{{MEMBER_HASH}}','student01@example.invalid','USER','本地测试校区','DEMO0002','示例同学甲','已通过','已通过','本地演示：卖方及任务发布者',JSON_OBJECT('show_campus',TRUE,'show_personal_bio',TRUE)),
 (3,'student02','{{MEMBER_HASH}}','student02@example.invalid','USER','本地测试校区','DEMO0003','示例同学乙','已通过','已通过','本地演示：买方及任务申请者',JSON_OBJECT('show_campus',TRUE,'show_personal_bio',TRUE)),
 (4,'visitor01','{{MEMBER_HASH}}','visitor01@example.invalid','USER',NULL,NULL,NULL,'未认证','未认证','本地演示：未认证账号',JSON_OBJECT('show_campus',FALSE,'show_personal_bio',FALSE));
INSERT INTO approved_campus_registry(campus,student_no,user_id) VALUES
 ('本地测试校区','DEMO0002',2),('本地测试校区','DEMO0003',3);
-- Synthetic opening point balances: student01 starts at 100 with 25 frozen for two tasks.
INSERT INTO reward_accounts(user_id,reward_type,available_amount,frozen_amount) VALUES
 (2,'积分',75,25),(2,'现金',0,0),(3,'积分',100,0),(3,'现金',0,0),
 (4,'积分',0,0),(4,'现金',0,0);
INSERT INTO notification_preferences(user_id) VALUES(2),(3),(4);

INSERT INTO product_info(product_id,user_id,category_id,product_name,product_desc,campus,location_label,
                         original_price,discount_price,stock_quantity,trade_mode,audit_status,shelf_status,reviewer_id,audit_reason) VALUES
 (1,2,3,'二手教材','少量笔记，可在图书馆当面查看。','本地测试校区','图书馆一楼',60,25,4,'校园自提','已通过','在售',1,'本地演示预置'),
 (2,2,4,'宿舍台灯','功能正常，线下自提。','本地测试校区','宿舍楼下',80,35,2,'校园自提','已通过','在售',1,'本地演示预置'),
 (3,3,2,'待审核鼠标','供管理端审核操作演示。','本地测试校区','教学楼门口',90,40,1,'校园自提','待审核','已下架',NULL,NULL);
INSERT INTO product_favor(product_id,user_id) VALUES(1,3);
INSERT INTO post_info(post_id,publisher_id,category_id,post_title,post_content,post_status) VALUES
 ('DEMO_POST_1',2,5,'校园交流示例','欢迎交流学习资料，交易请在线下当面确认。','已发布'),
 ('DEMO_POST_2',3,5,'待审核帖子示例','供管理员审核操作演示。','待审核');
INSERT INTO post_collection(user_id,post_id) VALUES(3,'DEMO_POST_1');

INSERT INTO RewardTasks(reward_task_id,publisher_id,task_name,task_description,campus,reward_amount,reward_type,task_status) VALUES
 (1,2,'图书馆取书','帮忙到图书馆取一本预约图书。','本地测试校区',10,'积分','待接取'),
 (2,2,'待审核互助任务','供管理端任务审核演示。','本地测试校区',15,'积分','待审核');
INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES
 ('FREEZE:1',1,2,'积分','冻结',-10,10),('FREEZE:2',2,2,'积分','冻结',-15,15);
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES
 (1,1,'发布审核','待审核','待接取','本地演示合成预置');
INSERT INTO task_applications(reward_task_id,applicant_id,application_status) VALUES(1,3,'待审核');

-- Stocks above already exclude these two reservations/sales. OFFLINE actual_amount stays zero.
INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,
                        total_amount,actual_amount,order_status,trade_channel,shipping_address,order_time,shipping_time,completion_time) VALUES
 (CONCAT('ORDER',DATE_FORMAT(@demo_now,'%y%m%d'),'00000001'),'商品',3,2,1,'二手教材',1,25,25,0,'待线下交易','OFFLINE','图书馆一楼',@demo_now,NULL,NULL),
 (CONCAT('ORDER',DATE_FORMAT(@demo_now,'%y%m%d'),'00000002'),'商品',3,2,2,'宿舍台灯',1,35,35,0,'已完成','OFFLINE','宿舍楼下',@demo_now,@demo_now,@demo_now);
INSERT INTO business_number_sequences(number_type,counter_value) VALUES
 (CONCAT('ORDER:',DATE_FORMAT(@demo_now,'%y%m%d')),2);
INSERT INTO t_chat_session(session_id,sender_id,receiver_id) VALUES(1,2,3);
INSERT INTO t_private_msg(session_id,sender_id,receiver_id,content_type,content,send_time) VALUES
 (1,3,2,'文本','你好，教材可以在图书馆交接吗？',@demo_now),
 (1,2,3,'文本','可以，请先在订单里确认交接地点。',@demo_now);

-- One preloaded in-app welcome notice. No queued background work or external delivery.
INSERT INTO system_notifications(notification_id,notification_title,notification_content,notification_type,
                                  publisher_id,target_scope,publish_status,publish_time) VALUES
 ('DEMO_WELCOME','欢迎使用校园Π','本库仅含少量合成演示数据。商品采用线下自提，平台不代收款。','系统',1,'全体','已发送',@demo_now);
INSERT INTO notification_publish_jobs(job_id,notification_id,recipient_count,job_status,created_at,completed_at) VALUES
 ('DEMO_WELCOME_JOB','DEMO_WELCOME',3,'已完成',@demo_now,@demo_now);
INSERT INTO notification_receipts(notification_id,user_id,received_at) VALUES
 ('DEMO_WELCOME',2,@demo_now),('DEMO_WELCOME',3,@demo_now),('DEMO_WELCOME',4,@demo_now);
INSERT INTO notification_delivery(delivery_id,notification_id,user_id,channel,delivery_status,provider_reference,sent_at) VALUES
 (1,'DEMO_WELCOME',2,'站内','成功','LOCAL_DEMO_SEED',@demo_now),
 (2,'DEMO_WELCOME',3,'站内','成功','LOCAL_DEMO_SEED',@demo_now),
 (3,'DEMO_WELCOME',4,'站内','成功','LOCAL_DEMO_SEED',@demo_now);
INSERT INTO notification_outbox(delivery_id,notification_id,work_status,created_at,completed_at,available_at) VALUES
 (1,'DEMO_WELCOME','已完成',@demo_now,@demo_now,@demo_now),
 (2,'DEMO_WELCOME','已完成',@demo_now,@demo_now,@demo_now),
 (3,'DEMO_WELCOME','已完成',@demo_now,@demo_now,@demo_now);
""".replace('{{MEMBER_HASH}}', password_hash)


def build():
    sources = [ROOT/'database/schema.sql',
               *sorted((ROOT/'database/migrations').glob('V*.sql'))]
    parts = ["""-- Campus Pi LOCAL installation only. Generated by scripts/build_local_init_sql.py.
-- Run from mysql batch stdin WITHOUT --force. Stop on the first error.
-- CREATE DATABASE deliberately has no IF NOT EXISTS: an existing database is refused.
-- Never use this installer to upgrade a database. It does not DROP or reset databases.
-- Small LOCAL demo: 4 users, 3 products, 2 posts, 2 tasks, 2 offline orders, 1 notice.
-- Admin: admin / 123456. Other demo accounts: student01, student02, visitor01 / CampusPi!2026.
-- Demo identities, point balances and histories are synthetic. All cash balances are zero.
-- Enable the private local-short-password-admin-id: 1 setting for this local account.
SET NAMES utf8mb4;
CREATE DATABASE `campus_pi_local` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `campus_pi_local`;
SET time_zone = '+00:00';
"""]
    for path in sources:
        text = path.read_text(encoding='utf-8').replace('\r\n','\n')
        digest = hashlib.sha256(text.encode('utf-8')).hexdigest()
        parts.append(f'\n-- Source: {path.relative_to(ROOT).as_posix()}\n-- SHA256: {digest}\n{text.rstrip()}\n')
    password_hash = hash_secret(b'123456', b'CampusPiLocalV1!', time_cost=3,
                               memory_cost=65536, parallelism=1, hash_len=32,
                               type=Type.ID).decode('ascii')
    member_hash = hash_secret(b'CampusPi!2026', b'CampusPiDemoV1!', time_cost=3,
                             memory_cost=65536, parallelism=1, hash_len=32,
                             type=Type.ID).decode('ascii')
    parts.append("""
-- Minimal, explicit local bootstrap. These are not real verified identities.
START TRANSACTION;
INSERT INTO users(user_id,username,password_hash,email,role,privacy_settings)
VALUES(1,'admin','""" + password_hash + """','local-admin@example.invalid','ADMIN',
       JSON_OBJECT('show_campus',FALSE,'show_personal_bio',FALSE));
INSERT INTO reward_accounts(user_id,reward_type) VALUES(1,'积分'),(1,'现金');
INSERT INTO notification_preferences(user_id) VALUES(1);
INSERT INTO category(category_id,category_name,category_level,parent_category_id,category_description) VALUES
 (1,'校园分类','1',NULL,'本地初始化基础分类'),
 (2,'数码电子','2',1,'本地基础分类'),
 (3,'图书教材','2',1,'本地基础分类'),
 (4,'生活用品','2',1,'本地基础分类'),
 (5,'校园交流','2',1,'本地基础分类');
INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason,after_data)
VALUES(1,'USER','1','LOCAL_BOOTSTRAP_SQL','仅本机部署初始化，不代表正式人员授权',JSON_OBJECT('role','ADMIN'));
""")
    parts.append(demo_seed(member_hash))
    parts.append("""
COMMIT;
SELECT DATABASE() AS initialized_database;
SELECT COUNT(*) AS table_count FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();
SELECT user_id,username,role,campus_verification FROM users;
SELECT (SELECT COUNT(*) FROM users) AS users,
       (SELECT COUNT(*) FROM product_info) AS products,
       (SELECT COUNT(*) FROM post_info) AS posts,
       (SELECT COUNT(*) FROM RewardTasks) AS tasks,
       (SELECT COUNT(*) FROM user_orders) AS orders,
       (SELECT COUNT(*) FROM system_notifications) AS notifications;
""")
    return ''.join(parts)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Fail if generated SQL differs from current sources')
    args = parser.parse_args()
    content = build()
    if args.check:
        if not TARGET.exists() or TARGET.read_text(encoding='utf-8') != content:
            raise SystemExit('Local init SQL is stale. Run python scripts/build_local_init_sql.py')
        print('PASS: local init SQL matches schema, migrations and the small 4-account demo.')
    else:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(content, encoding='utf-8', newline='\n')
        print('Generated database/init.sql; no database was contacted.')


if __name__ == '__main__':
    main()
