-- 校园Π 核心业务事务示例 仅供受信任的服务端调用
-- p_actor 必须取自经过认证的服务端会话 不得直接相信请求中的用户ID
-- 奖励账本验证使用合成余额 不代表银行 微信或支付宝真实收付款
-- 存储过程内含事务 请勿嵌入调用方尚未提交的事务
SET NAMES utf8mb4;
DELIMITER $$
CREATE PROCEDURE sp_task_publish(IN p_actor BIGINT, IN p_title VARCHAR(100), IN p_desc TEXT, IN p_campus VARCHAR(50), IN p_amount DECIMAL(12,2), IN p_type VARCHAR(10), OUT p_task BIGINT)
BEGIN
DECLARE v_balance DECIMAL(14,2);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=p_campus) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Verified campus user required'; END IF;
IF p_amount IS NULL OR p_amount<=0 OR p_type IS NULL OR p_type NOT IN ('积分','现金') OR (p_type='积分' AND p_amount<>FLOOR(p_amount)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid reward'; END IF;
SET v_balance=NULL;
SELECT available_amount INTO v_balance FROM reward_accounts WHERE user_id=p_actor AND reward_type=p_type FOR UPDATE;
IF v_balance IS NULL OR v_balance<p_amount THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Insufficient reward balance'; END IF;
INSERT INTO RewardTasks(publisher_id,task_name,task_description,campus,reward_amount,reward_type) VALUES(p_actor,p_title,p_desc,p_campus,p_amount,p_type);
SET p_task=LAST_INSERT_ID();
UPDATE reward_accounts SET available_amount=available_amount-p_amount,frozen_amount=frozen_amount+p_amount WHERE user_id=p_actor AND reward_type=p_type;
INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(CONCAT('HOLD:',p_task),p_task,p_actor,p_type,'冻结',-p_amount,p_amount);
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,to_status,reason) VALUES(p_task,p_actor,'发布','待审核','发布任务并冻结奖励');
COMMIT;
END$$

CREATE PROCEDURE sp_task_publish_review(IN p_actor BIGINT, IN p_task BIGINT, IN p_pass BOOLEAN, IN p_reason VARCHAR(500))
BEGIN
DECLARE v_status VARCHAR(10);
DECLARE v_user BIGINT;
DECLARE v_amount DECIMAL(12,2);
DECLARE v_type VARCHAR(10);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND role='ADMIN' AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Administrator required'; END IF;
IF p_pass IS NULL OR p_pass NOT IN (0,1) OR p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Decision and reason required'; END IF;
SELECT task_status,publisher_id,reward_amount,reward_type INTO v_status,v_user,v_amount,v_type FROM RewardTasks WHERE reward_task_id=p_task FOR UPDATE;
IF v_status IS NULL OR v_status<>'待审核' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task not pending publication review'; END IF;
IF p_pass=0 THEN
 UPDATE reward_accounts SET available_amount=available_amount+v_amount,frozen_amount=frozen_amount-v_amount WHERE user_id=v_user AND reward_type=v_type;
 INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(CONCAT('RELEASE:',p_task),p_task,v_user,v_type,'解冻',v_amount,-v_amount);
END IF;
UPDATE RewardTasks SET task_status=IF(p_pass=1,'待接取','已驳回'),version=version+1 WHERE reward_task_id=p_task;
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(p_task,p_actor,'发布审核',v_status,IF(p_pass=1,'待接取','已驳回'),p_reason);
COMMIT;
END$$

CREATE PROCEDURE sp_task_apply(IN p_actor BIGINT, IN p_task BIGINT)
BEGIN
DECLARE v_status VARCHAR(10);
DECLARE v_owner BIGINT;
DECLARE v_campus VARCHAR(50);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
SELECT task_status,publisher_id,campus INTO v_status,v_owner,v_campus FROM RewardTasks WHERE reward_task_id=p_task FOR UPDATE;
IF v_status IS NULL OR v_status<>'待接取' OR v_owner=p_actor THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task cannot be applied for'; END IF;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND role='USER' AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=v_campus) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Verified campus user required'; END IF;
INSERT INTO task_applications(reward_task_id,applicant_id) VALUES(p_task,p_actor);
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(p_task,p_actor,'申请接取',v_status,v_status,'提交接取申请');
COMMIT;
END$$

CREATE PROCEDURE sp_task_accept(IN p_actor BIGINT, IN p_application BIGINT, IN p_order CHAR(19), IN p_reason VARCHAR(500))
BEGIN
DECLARE v_task BIGINT;
DECLARE v_taker BIGINT;
DECLARE v_owner BIGINT;
DECLARE v_status VARCHAR(10);
DECLARE v_app VARCHAR(10);
DECLARE v_campus VARCHAR(50);
DECLARE v_title VARCHAR(100);
DECLARE v_amount DECIMAL(12,2);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND role='ADMIN' AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Administrator required'; END IF;
SELECT reward_task_id INTO v_task FROM task_applications WHERE application_id=p_application;
SELECT task_status,publisher_id,campus,task_name,reward_amount INTO v_status,v_owner,v_campus,v_title,v_amount FROM RewardTasks WHERE reward_task_id=v_task FOR UPDATE;
SELECT applicant_id,application_status INTO v_taker,v_app FROM task_applications WHERE application_id=p_application FOR UPDATE;
IF v_status IS NULL OR v_status<>'待接取' OR v_app<>'待审核' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task already assigned or application invalid'; END IF;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=v_taker AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=v_campus) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Applicant no longer eligible'; END IF;
IF p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Reason required'; END IF;
UPDATE task_applications SET application_status=IF(application_id=p_application,'已通过','已驳回'),reviewer_id=p_actor,audit_reason=IF(application_id=p_application,p_reason,'任务已分配给其他申请者'),reviewed_at=CURRENT_TIMESTAMP(3) WHERE reward_task_id=v_task AND application_status='待审核';
UPDATE RewardTasks SET taker_id=v_taker,task_status='进行中',accepted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE reward_task_id=v_task;
INSERT INTO user_orders(order_id,order_type,user_id,seller_id,reward_task_id,item_name,quantity,unit_price,total_amount,order_status) VALUES(p_order,'任务',v_owner,v_taker,v_task,v_title,1,v_amount,v_amount,'进行中');
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(v_task,p_actor,'接取审核','待接取','进行中',p_reason);
COMMIT;
END$$

CREATE PROCEDURE sp_task_proof(IN p_actor BIGINT, IN p_task BIGINT, IN p_text VARCHAR(1000), IN p_urls JSON, OUT p_proof BIGINT)
BEGIN
DECLARE v_status VARCHAR(10);
DECLARE v_taker BIGINT;
DECLARE v_no INT;
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
SELECT task_status,taker_id INTO v_status,v_taker FROM RewardTasks WHERE reward_task_id=p_task FOR UPDATE;
IF v_status IS NULL OR v_status<>'进行中' OR v_taker<>p_actor OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only active assigned taker may submit'; END IF;
IF p_text IS NULL OR TRIM(p_text)='' OR p_urls IS NULL OR JSON_TYPE(p_urls)<>'ARRAY' OR JSON_LENGTH(p_urls)=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Proof text and attachment required'; END IF;
SELECT COALESCE(MAX(submission_no),0)+1 INTO v_no FROM task_proofs WHERE reward_task_id=p_task;
INSERT INTO task_proofs(reward_task_id,submitter_id,submission_no,proof_text,evidence_urls) VALUES(p_task,p_actor,v_no,p_text,p_urls);
SET p_proof=LAST_INSERT_ID();
UPDATE RewardTasks SET task_status='待验收',submitted_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE reward_task_id=p_task;
UPDATE user_orders SET order_status='待验收' WHERE reward_task_id=p_task;
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(p_task,p_actor,'提交凭证','进行中','待验收','凭证版本已入库');
COMMIT;
END$$

CREATE PROCEDURE sp_task_review(IN p_actor BIGINT, IN p_proof BIGINT, IN p_decision VARCHAR(10), IN p_reason VARCHAR(500))
BEGIN
DECLARE v_task BIGINT;
DECLARE v_owner BIGINT;
DECLARE v_taker BIGINT;
DECLARE v_status VARCHAR(10);
DECLARE v_proof_status VARCHAR(10);
DECLARE v_type VARCHAR(10);
DECLARE v_amount DECIMAL(12,2);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND role='ADMIN' AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Administrator required'; END IF;
SELECT reward_task_id INTO v_task FROM task_proofs WHERE proof_id=p_proof;
SELECT task_status,publisher_id,taker_id,reward_type,reward_amount INTO v_status,v_owner,v_taker,v_type,v_amount FROM RewardTasks WHERE reward_task_id=v_task FOR UPDATE;
SELECT audit_status INTO v_proof_status FROM task_proofs WHERE proof_id=p_proof FOR UPDATE;
IF v_status IS NULL OR v_status<>'待验收' OR v_proof_status<>'待审核' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Proof already reviewed or task not pending acceptance'; END IF;
IF p_decision IS NULL OR p_decision NOT IN ('通过','补充凭证','未通过') OR p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Decision and reason required'; END IF;
UPDATE task_proofs SET audit_status=IF(p_decision='通过','已通过',p_decision),reviewer_id=p_actor,audit_reason=p_reason,reviewed_at=CURRENT_TIMESTAMP(3) WHERE proof_id=p_proof;
IF p_decision='通过' THEN
 INSERT INTO reward_accounts(user_id,reward_type) VALUES(v_taker,v_type) ON DUPLICATE KEY UPDATE user_id=v_taker;
 UPDATE reward_accounts SET frozen_amount=frozen_amount-v_amount WHERE user_id=v_owner AND reward_type=v_type;
 UPDATE reward_accounts SET available_amount=available_amount+v_amount WHERE user_id=v_taker AND reward_type=v_type;
 INSERT INTO task_settlement(reward_task_id,proof_id,payer_id,payee_id,reward_type,amount) VALUES(v_task,p_proof,v_owner,v_taker,v_type,v_amount);
 INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(CONCAT('PAY:',v_task),v_task,v_owner,v_type,'支付',0,-v_amount),(CONCAT('INCOME:',v_task),v_task,v_taker,v_type,'收入',v_amount,0);
 UPDATE user_orders SET order_status='已完成',actual_amount=v_amount,payment_method='奖励账户',completion_time=CURRENT_TIMESTAMP(3),payment_time=CURRENT_TIMESTAMP(3) WHERE reward_task_id=v_task;
 SET v_status='已完成';
ELSEIF p_decision='补充凭证' THEN
 SET v_status='进行中';
 UPDATE user_orders SET order_status='进行中' WHERE reward_task_id=v_task;
ELSE
 SET v_status='未完成';
 UPDATE user_orders SET order_status='未完成' WHERE reward_task_id=v_task;
END IF;
UPDATE RewardTasks SET task_status=v_status,completed_at=IF(v_status='已完成',CURRENT_TIMESTAMP(3),NULL),version=version+1 WHERE reward_task_id=v_task;
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(v_task,p_actor,'完成验收','待验收',v_status,p_reason);
COMMIT;
END$$

CREATE PROCEDURE sp_task_cancel(IN p_actor BIGINT, IN p_task BIGINT, IN p_reason VARCHAR(500))
BEGIN
DECLARE v_status VARCHAR(10);
DECLARE v_owner BIGINT;
DECLARE v_amount DECIMAL(12,2);
DECLARE v_type VARCHAR(10);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
SELECT task_status,publisher_id,reward_amount,reward_type INTO v_status,v_owner,v_amount,v_type FROM RewardTasks WHERE reward_task_id=p_task FOR UPDATE;
IF NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND (role='ADMIN' OR user_id=v_owner)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task owner or administrator required'; END IF;
IF v_status IS NULL OR v_status NOT IN ('待审核','待接取','未完成') OR (v_status='未完成' AND NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND role='ADMIN')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task cannot be cancelled in this state'; END IF;
IF p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Cancellation reason required'; END IF;
UPDATE reward_accounts SET available_amount=available_amount+v_amount,frozen_amount=frozen_amount-v_amount WHERE user_id=v_owner AND reward_type=v_type;
INSERT INTO reward_ledger(event_key,reward_task_id,user_id,reward_type,event_type,available_delta,frozen_delta) VALUES(CONCAT('RELEASE:',p_task),p_task,v_owner,v_type,'解冻',v_amount,-v_amount);
UPDATE RewardTasks SET task_status='已取消',cancel_reason=p_reason,version=version+1 WHERE reward_task_id=p_task;
UPDATE task_applications SET application_status='已取消' WHERE reward_task_id=p_task AND application_status='待审核';
UPDATE user_orders SET order_status='已取消' WHERE reward_task_id=p_task;
INSERT INTO task_audit_log(reward_task_id,operator_id,action_name,from_status,to_status,reason) VALUES(p_task,p_actor,'取消',v_status,'已取消',p_reason);
COMMIT;
END$$

CREATE PROCEDURE sp_product_review(IN p_actor BIGINT, IN p_product BIGINT, IN p_pass BOOLEAN, IN p_reason VARCHAR(500))
BEGIN
DECLARE v_id BIGINT;
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS (SELECT 1 FROM users WHERE user_id=p_actor AND role='ADMIN' AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Administrator required'; END IF;
SELECT product_id INTO v_id FROM product_info WHERE product_id=p_product AND audit_status='待审核' FOR UPDATE;
IF v_id IS NULL OR p_pass IS NULL OR p_pass NOT IN (0,1) OR p_reason IS NULL OR TRIM(p_reason)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Pending product and decision required'; END IF;
UPDATE product_info SET audit_status=IF(p_pass=1,'已通过','已驳回'),shelf_status=IF(p_pass=1 AND stock_quantity>0,'在售','已下架'),reviewer_id=p_actor,audit_reason=p_reason,audited_at=CURRENT_TIMESTAMP(3) WHERE product_id=p_product;
INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(p_actor,'商品',p_product,'审核',p_reason);
COMMIT;
END$$

CREATE PROCEDURE sp_product_order(IN p_actor BIGINT, IN p_product BIGINT, IN p_quantity INT, IN p_order CHAR(19), IN p_address VARCHAR(500))
BEGIN
DECLARE v_owner BIGINT;
DECLARE v_stock INT;
DECLARE v_price DECIMAL(12,2);
DECLARE v_name VARCHAR(255);
DECLARE v_campus VARCHAR(50);
DECLARE v_audit VARCHAR(10);
DECLARE v_shelf VARCHAR(10);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
SELECT user_id,stock_quantity,discount_price,product_name,campus,audit_status,shelf_status INTO v_owner,v_stock,v_price,v_name,v_campus,v_audit,v_shelf FROM product_info WHERE product_id=p_product FOR UPDATE;
IF v_owner IS NULL OR v_owner=p_actor OR v_audit<>'已通过' OR v_shelf<>'在售' OR p_quantity IS NULL OR p_quantity<=0 OR v_stock<p_quantity THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Product not purchasable or insufficient stock'; END IF;
IF NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=v_campus) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Verified campus buyer required'; END IF;
IF p_address IS NULL OR TRIM(p_address)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Delivery or pickup address required'; END IF;
INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,order_status,shipping_address) VALUES(p_order,'商品',p_actor,v_owner,p_product,v_name,p_quantity,v_price,v_price*p_quantity,'待支付',p_address);
UPDATE product_info SET stock_quantity=stock_quantity-p_quantity,shelf_status=IF(stock_quantity=0,'已售出',shelf_status) WHERE product_id=p_product;
COMMIT;
END$$

CREATE PROCEDURE sp_product_cancel(IN p_actor BIGINT, IN p_order CHAR(19))
BEGIN
DECLARE v_product BIGINT;
DECLARE v_user BIGINT;
DECLARE v_quantity INT;
DECLARE v_status VARCHAR(10);
DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
SELECT product_id,user_id,quantity,order_status INTO v_product,v_user,v_quantity,v_status FROM user_orders WHERE order_id=p_order AND order_type='商品' FOR UPDATE;
IF v_product IS NULL OR v_status<>'待支付' OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND (user_id=v_user OR role='ADMIN')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only unpaid product order may be cancelled'; END IF;
UPDATE user_orders SET order_status='已取消' WHERE order_id=p_order;
UPDATE product_info SET stock_quantity=stock_quantity+v_quantity,shelf_status=IF(shelf_status='已售出' AND audit_status='已通过','在售',shelf_status) WHERE product_id=v_product;
INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(p_actor,'订单',p_order,'取消','取消待支付订单并释放库存');
COMMIT;
END$$

CREATE PROCEDURE sp_send_message(IN p_actor BIGINT, IN p_session BIGINT, IN p_receiver BIGINT, IN p_type VARCHAR(10), IN p_content TEXT)
BEGIN

DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE') OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_receiver AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Active message participants required'; END IF;
IF NOT EXISTS(SELECT 1 FROM t_chat_session WHERE session_id=p_session AND sender_id=LEAST(p_actor,p_receiver) AND receiver_id=GREATEST(p_actor,p_receiver)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Session membership mismatch'; END IF;
IF p_content IS NULL OR TRIM(p_content)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Message content required'; END IF;
INSERT INTO t_private_msg(session_id,sender_id,receiver_id,content_type,content) VALUES(p_session,p_actor,p_receiver,p_type,p_content);
COMMIT;
END$$

CREATE PROCEDURE sp_read_message(IN p_actor BIGINT, IN p_msg BIGINT)
BEGIN

DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
START TRANSACTION;
IF NOT EXISTS(SELECT 1 FROM t_private_msg WHERE msg_id=p_msg AND receiver_id=p_actor) OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Message receiver required'; END IF;
UPDATE t_private_msg SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP(3)) WHERE msg_id=p_msg;
COMMIT;
END$$

CREATE TRIGGER tr_message_participants BEFORE INSERT ON t_private_msg FOR EACH ROW
BEGIN
 IF NOT EXISTS(SELECT 1 FROM t_chat_session WHERE session_id=NEW.session_id AND sender_id=LEAST(NEW.sender_id,NEW.receiver_id) AND receiver_id=GREATEST(NEW.sender_id,NEW.receiver_id)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Message participants mismatch'; END IF;
END$$

CREATE TRIGGER tr_message_report BEFORE INSERT ON t_msg_report FOR EACH ROW
BEGIN
 IF NOT EXISTS(SELECT 1 FROM t_private_msg WHERE msg_id=NEW.msg_id AND NEW.reporter_id IN (sender_id,receiver_id)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only message participant may report'; END IF;
END$$

CREATE TRIGGER tr_notification_channel BEFORE INSERT ON notification_delivery FOR EACH ROW
BEGIN
 IF NEW.channel IN ('短信','邮件') AND NOT EXISTS(SELECT 1 FROM system_notifications WHERE notification_id=NEW.notification_id AND notification_type='安全') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='External channel only for security notifications'; END IF;
END$$

CREATE TRIGGER tr_task_transition BEFORE UPDATE ON RewardTasks FOR EACH ROW
BEGIN
 IF OLD.task_status<>NEW.task_status AND NOT (
 (OLD.task_status='待审核' AND NEW.task_status IN ('待接取','已驳回','已取消')) OR
 (OLD.task_status='待接取' AND NEW.task_status IN ('进行中','已取消')) OR
 (OLD.task_status='进行中' AND NEW.task_status='待验收') OR
 (OLD.task_status='待验收' AND NEW.task_status IN ('进行中','已完成','未完成')) OR
 (OLD.task_status='未完成' AND NEW.task_status='已取消')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Illegal task status transition'; END IF;
END$$
DELIMITER ;
