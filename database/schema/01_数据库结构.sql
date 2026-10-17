-- 校园Π MySQL 数据库设计修订版 2026-10-17
-- 目标 MySQL 8.0.16 及以上 本次在 8.0.44 验证
-- 仅在新建空库中运行 本脚本不删除或覆盖现有表
-- 先 CREATE DATABASE 自选新库 CHARACTER SET utf8mb4 后 USE 该新库
-- 应用数据库账号只授予必要权限 不应授予 DDL 或直接修改账本的权限
SET NAMES utf8mb4;
SET SESSION sql_mode='STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';
CREATE TABLE `users` (
  user_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(25) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  phone VARCHAR(20) UNIQUE,
  email VARCHAR(100) UNIQUE,
  role VARCHAR(10) NOT NULL DEFAULT 'USER',
  account_status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  avatar_url VARCHAR(500),
  campus VARCHAR(50),
  student_no VARCHAR(30),
  real_name VARCHAR(50),
  identity_verification VARCHAR(10) NOT NULL DEFAULT '未认证',
  campus_verification VARCHAR(10) NOT NULL DEFAULT '未认证',
  registration_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  address VARCHAR(500),
  personal_bio VARCHAR(500),
  privacy_settings JSON,
  CHECK (role IN ('USER','ADMIN')),
  CHECK (account_status IN ('ACTIVE','DISABLED')),
  CHECK (identity_verification IN ('未认证','待审核','已通过','已驳回')),
  CHECK (campus_verification IN ('未认证','待审核','已通过','已驳回')),
  CHECK (password_hash REGEXP '^[$]argon2id[$]'),
  CHECK (campus_verification <> '已通过' OR (campus IS NOT NULL AND student_no IS NOT NULL AND identity_verification='已通过'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户信息';

CREATE TABLE `user_verification` (
  verification_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  verification_type VARCHAR(10) NOT NULL,
  campus VARCHAR(50),
  student_no VARCHAR(30),
  real_name VARCHAR(50) NOT NULL,
  evidence_urls JSON NOT NULL,
  audit_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  CHECK (verification_type IN ('实名','校园')),
  CHECK (audit_status IN ('待审核','已通过','已驳回')),
  CHECK (JSON_TYPE(evidence_urls)='ARRAY' AND JSON_LENGTH(evidence_urls)>0),
  CHECK (verification_type<>'校园' OR (campus IS NOT NULL AND student_no IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实名与校园认证记录';

CREATE TABLE `category` (
  category_id INT PRIMARY KEY AUTO_INCREMENT,
  category_name VARCHAR(50) NOT NULL,
  category_level CHAR(1) NOT NULL,
  parent_category_id INT,
  category_description VARCHAR(500),
  created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (parent_category_id) REFERENCES category(category_id),
  CHECK ((category_level='1' AND parent_category_id IS NULL) OR (category_level='2' AND parent_category_id IS NOT NULL)),
  UNIQUE (parent_category_id,category_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分类信息';

CREATE TABLE `product_info` (
  product_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  category_id INT NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  product_desc TEXT,
  campus VARCHAR(50) NOT NULL,
  location_label VARCHAR(100),
  trade_mode VARCHAR(10) NOT NULL DEFAULT '校园自提',
  original_price DECIMAL(12,2) NOT NULL,
  discount_price DECIMAL(12,2) NOT NULL,
  stock_quantity INT NOT NULL DEFAULT 1,
  audit_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  shelf_status VARCHAR(10) NOT NULL DEFAULT '已下架',
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  audited_at DATETIME(3),
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (category_id) REFERENCES category(category_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  CHECK (trade_mode IN ('校园自提','快递配送')),
  CHECK (original_price>=0 AND discount_price>=0 AND stock_quantity>=0),
  CHECK (audit_status IN ('待审核','已通过','已驳回')),
  CHECK (shelf_status IN ('在售','已下架','已售出')),
  CHECK (shelf_status<>'在售' OR (audit_status='已通过' AND stock_quantity>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='二手商品';

CREATE TABLE `product_media` (
  media_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  FOREIGN KEY (product_id) REFERENCES product_info(product_id),
  CHECK (sort_order>=0),
  UNIQUE (product_id,sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品图片';

CREATE TABLE `product_favor` (
  product_favor_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  favor_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (product_id) REFERENCES product_info(product_id),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  UNIQUE (user_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品收藏';

CREATE TABLE `post_info` (
  post_id VARCHAR(50) PRIMARY KEY,
  publisher_id BIGINT NOT NULL,
  category_id INT NOT NULL,
  post_title VARCHAR(200) NOT NULL,
  post_content TEXT NOT NULL,
  is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
  post_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  publish_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  view_count BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (publisher_id) REFERENCES users(user_id),
  FOREIGN KEY (category_id) REFERENCES category(category_id),
  CHECK (post_status IN ('待审核','已发布','已驳回','已下架','已删除')),
  CHECK (view_count>=0 AND is_anonymous IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子信息';

CREATE TABLE `post_collection` (
  collection_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  post_id VARCHAR(50) NOT NULL,
  collection_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  collection_status VARCHAR(10) NOT NULL DEFAULT '已收藏',
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (post_id) REFERENCES post_info(post_id),
  UNIQUE (user_id,post_id),
  CHECK (collection_status IN ('已收藏','已取消','失效'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子收藏';

CREATE TABLE `RewardTasks` (
  reward_task_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  publisher_id BIGINT NOT NULL,
  taker_id BIGINT,
  task_name VARCHAR(100) NOT NULL,
  task_description TEXT NOT NULL,
  campus VARCHAR(50) NOT NULL,
  reward_amount DECIMAL(12,2) NOT NULL,
  reward_type VARCHAR(10) NOT NULL,
  task_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  publish_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  accepted_at DATETIME(3),
  submitted_at DATETIME(3),
  completed_at DATETIME(3),
  cancel_reason VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  FOREIGN KEY (publisher_id) REFERENCES users(user_id),
  FOREIGN KEY (taker_id) REFERENCES users(user_id),
  CHECK (reward_type IN ('积分','现金') AND reward_amount>0),
  CHECK (reward_type<>'积分' OR reward_amount=FLOOR(reward_amount)),
  CHECK (task_status IN ('待审核','待接取','进行中','待验收','已完成','未完成','已驳回','已取消')),
  CHECK (taker_id IS NULL OR taker_id<>publisher_id),
  CHECK (task_status NOT IN ('进行中','待验收','已完成','未完成') OR taker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='悬赏任务';

CREATE TABLE `task_applications` (
  application_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reward_task_id BIGINT NOT NULL,
  applicant_id BIGINT NOT NULL,
  application_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  FOREIGN KEY (applicant_id) REFERENCES users(user_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  UNIQUE (reward_task_id,applicant_id),
  CHECK (application_status IN ('待审核','已通过','已驳回','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务接取申请';

CREATE TABLE `task_proofs` (
  proof_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reward_task_id BIGINT NOT NULL,
  submitter_id BIGINT NOT NULL,
  submission_no INT NOT NULL,
  proof_text VARCHAR(1000) NOT NULL,
  evidence_urls JSON NOT NULL,
  audit_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  submitted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  FOREIGN KEY (submitter_id) REFERENCES users(user_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  UNIQUE (reward_task_id,submission_no),
  CHECK (submission_no>0 AND CHAR_LENGTH(TRIM(proof_text))>0),
  CHECK (JSON_TYPE(evidence_urls)='ARRAY' AND JSON_LENGTH(evidence_urls)>0),
  CHECK (audit_status IN ('待审核','已通过','补充凭证','未通过'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务完成凭证';

CREATE TABLE `task_audit_log` (
  log_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reward_task_id BIGINT NOT NULL,
  operator_id BIGINT NOT NULL,
  action_name VARCHAR(30) NOT NULL,
  from_status VARCHAR(10),
  to_status VARCHAR(10) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  operated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  FOREIGN KEY (operator_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务状态与审核日志';

CREATE TABLE `reward_accounts` (
  user_id BIGINT NOT NULL,
  reward_type VARCHAR(10) NOT NULL,
  available_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  frozen_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id,reward_type),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  CHECK (reward_type IN ('积分','现金') AND available_amount>=0 AND frozen_amount>=0),
  CHECK (reward_type<>'积分' OR (available_amount=FLOOR(available_amount) AND frozen_amount=FLOOR(frozen_amount)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='奖励账户';

CREATE TABLE `reward_ledger` (
  ledger_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_key VARCHAR(100) NOT NULL UNIQUE,
  reward_task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reward_type VARCHAR(10) NOT NULL,
  event_type VARCHAR(10) NOT NULL,
  available_delta DECIMAL(14,2) NOT NULL,
  frozen_delta DECIMAL(14,2) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  FOREIGN KEY (user_id,reward_type) REFERENCES reward_accounts(user_id,reward_type),
  CHECK (event_type IN ('冻结','解冻','支付','收入'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='奖励流水';

CREATE TABLE `task_settlement` (
  settlement_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reward_task_id BIGINT NOT NULL UNIQUE,
  proof_id BIGINT NOT NULL UNIQUE,
  payer_id BIGINT NOT NULL,
  payee_id BIGINT NOT NULL,
  reward_type VARCHAR(10) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  settlement_status VARCHAR(10) NOT NULL DEFAULT '已记账',
  settled_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  FOREIGN KEY (proof_id) REFERENCES task_proofs(proof_id),
  FOREIGN KEY (payer_id,reward_type) REFERENCES reward_accounts(user_id,reward_type),
  FOREIGN KEY (payee_id,reward_type) REFERENCES reward_accounts(user_id,reward_type),
  CHECK (amount>0 AND payer_id<>payee_id AND reward_type IN ('积分','现金')),
  CHECK (settlement_status='已记账')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务结算';

CREATE TABLE `user_orders` (
  order_id CHAR(19) PRIMARY KEY,
  order_type VARCHAR(10) NOT NULL,
  user_id BIGINT NOT NULL,
  seller_id BIGINT NOT NULL,
  product_id BIGINT,
  reward_task_id BIGINT UNIQUE,
  item_name VARCHAR(255) NOT NULL,
  quantity INT NOT NULL DEFAULT 1,
  unit_price DECIMAL(12,2) NOT NULL,
  total_amount DECIMAL(12,2) NOT NULL,
  shipping_fee DECIMAL(12,2) NOT NULL DEFAULT 0,
  actual_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
  order_status VARCHAR(10) NOT NULL,
  payment_method VARCHAR(20),
  payment_transaction_no VARCHAR(100) UNIQUE,
  shipping_address VARCHAR(500),
  order_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  payment_time DATETIME(3),
  shipping_time DATETIME(3),
  completion_time DATETIME(3),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (seller_id) REFERENCES users(user_id),
  FOREIGN KEY (product_id) REFERENCES product_info(product_id),
  FOREIGN KEY (reward_task_id) REFERENCES RewardTasks(reward_task_id),
  CHECK (order_id REGEXP '^ORDER[0-9]{14}$'),
  CHECK (user_id<>seller_id AND quantity>0 AND unit_price>=0),
  CHECK (total_amount=unit_price*quantity AND shipping_fee>=0 AND actual_amount>=0 AND actual_amount<=total_amount+shipping_fee),
  CHECK ((order_type='商品' AND product_id IS NOT NULL AND reward_task_id IS NULL AND order_status IN ('待支付','已支付','已发货','待确认收货','已完成','已取消','售后中','已退款')) OR (order_type='任务' AND product_id IS NULL AND reward_task_id IS NOT NULL AND quantity=1 AND shipping_fee=0 AND order_status IN ('进行中','待验收','已完成','未完成','已取消','售后中','已退款')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品与任务订单';

CREATE TABLE `order_aftersales` (
  aftersale_id CHAR(19) PRIMARY KEY,
  order_id CHAR(19) NOT NULL,
  user_id BIGINT NOT NULL,
  aftersale_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  apply_amount DECIMAL(12,2) NOT NULL,
  approve_amount DECIMAL(12,2),
  apply_reason VARCHAR(500) NOT NULL,
  evidence_urls JSON,
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  refund_transaction_no VARCHAR(100) UNIQUE,
  apply_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  audit_time DATETIME(3),
  complete_time DATETIME(3),
  FOREIGN KEY (order_id) REFERENCES user_orders(order_id),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  CHECK (aftersale_id REGEXP '^AFTER[0-9]{14}$'),
  CHECK (apply_amount>=0 AND (approve_amount IS NULL OR (approve_amount>=0 AND approve_amount<=apply_amount))),
  CHECK (aftersale_status IN ('待审核','已驳回','退款中','已完成','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单售后';

CREATE TABLE `t_chat_session` (
  session_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sender_id BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (sender_id) REFERENCES users(user_id),
  FOREIGN KEY (receiver_id) REFERENCES users(user_id),
  CHECK (sender_id<receiver_id),
  UNIQUE (sender_id,receiver_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信会话';

CREATE TABLE `t_private_msg` (
  msg_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  content_type VARCHAR(10) NOT NULL DEFAULT '文本',
  content TEXT NOT NULL,
  send_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  read_at DATETIME(3),
  sender_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  receiver_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  FOREIGN KEY (session_id) REFERENCES t_chat_session(session_id),
  FOREIGN KEY (sender_id) REFERENCES users(user_id),
  FOREIGN KEY (receiver_id) REFERENCES users(user_id),
  CHECK (sender_id<>receiver_id AND content_type IN ('文本','图片')),
  CHECK (read_at IS NULL OR read_at>=send_time),
  INDEX idx_receiver_read (receiver_id,read_at),
  INDEX idx_session_time (session_id,send_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信内容';

CREATE TABLE `t_msg_report` (
  report_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  msg_id BIGINT NOT NULL,
  reporter_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  audit_status VARCHAR(10) NOT NULL DEFAULT '待审核',
  reviewer_id BIGINT,
  audit_reason VARCHAR(500),
  report_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_at DATETIME(3),
  FOREIGN KEY (msg_id) REFERENCES t_private_msg(msg_id),
  FOREIGN KEY (reporter_id) REFERENCES users(user_id),
  FOREIGN KEY (reviewer_id) REFERENCES users(user_id),
  UNIQUE (msg_id,reporter_id),
  CHECK (audit_status IN ('待审核','成立','不成立'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私信举报';

CREATE TABLE `system_notifications` (
  notification_id VARCHAR(50) PRIMARY KEY,
  notification_title VARCHAR(200) NOT NULL,
  notification_content TEXT NOT NULL,
  notification_type VARCHAR(10) NOT NULL DEFAULT '系统',
  publisher_id BIGINT NOT NULL,
  target_scope VARCHAR(10) NOT NULL DEFAULT '全体',
  target_filter JSON,
  publish_status VARCHAR(10) NOT NULL DEFAULT '草稿',
  scheduled_at DATETIME(3),
  publish_time DATETIME(3),
  FOREIGN KEY (publisher_id) REFERENCES users(user_id),
  CHECK (notification_type IN ('系统','互动','交易','审核','安全')),
  CHECK (target_scope IN ('全体','校区','指定用户')),
  CHECK (publish_status IN ('草稿','待发送','已发送','已撤回'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统通知';

CREATE TABLE `notification_receipts` (
  notification_id VARCHAR(50) NOT NULL,
  user_id BIGINT NOT NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  read_at DATETIME(3),
  clicked_at DATETIME(3),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (notification_id,user_id),
  FOREIGN KEY (notification_id) REFERENCES system_notifications(notification_id),
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  CHECK (read_at IS NULL OR read_at>=received_at),
  CHECK (clicked_at IS NULL OR (read_at IS NOT NULL AND clicked_at>=read_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知接收与阅读';

CREATE TABLE `notification_preferences` (
  user_id BIGINT PRIMARY KEY,
  in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  sms_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  dnd_start TIME,
  dnd_end TIME,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知偏好';

CREATE TABLE `notification_delivery` (
  delivery_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  notification_id VARCHAR(50) NOT NULL,
  user_id BIGINT NOT NULL,
  channel VARCHAR(10) NOT NULL,
  delivery_status VARCHAR(10) NOT NULL DEFAULT '待发送',
  attempt_no INT NOT NULL DEFAULT 1,
  provider_reference VARCHAR(100),
  failure_reason VARCHAR(500),
  sent_at DATETIME(3),
  FOREIGN KEY (notification_id,user_id) REFERENCES notification_receipts(notification_id,user_id),
  UNIQUE (notification_id,user_id,channel,attempt_no),
  CHECK (channel IN ('站内','短信','邮件') AND attempt_no>0),
  CHECK (delivery_status IN ('待发送','成功','失败'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知发送记录';

CREATE TABLE `admin_audit_log` (
  audit_id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operator_id BIGINT NOT NULL,
  object_type VARCHAR(30) NOT NULL,
  object_id VARCHAR(50) NOT NULL,
  action_name VARCHAR(30) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  before_data JSON,
  after_data JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (operator_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理操作日志';
