-- 在原 25 表结构已就绪的库上显式执行一次；不覆盖历史 schema/seed。
-- 由迁移负责人登记编号后再用于共享环境，当前供独立本地开发库验证。
ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE auth_sessions (
  token_hash CHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  auth_version BIGINT NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3),
  FOREIGN KEY(user_id) REFERENCES users(user_id),
  INDEX idx_auth_user(user_id), INDEX idx_auth_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_login_throttle (
  throttle_key CHAR(64) PRIMARY KEY,
  failure_count INT NOT NULL DEFAULT 0,
  window_start DATETIME(3) NOT NULL,
  locked_until DATETIME(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_idempotency (
  scope_key VARCHAR(100) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  response_json JSON,
  expires_at DATETIME(3) NOT NULL,
  PRIMARY KEY(scope_key,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_admin_guard (guard_id INT PRIMARY KEY);
INSERT INTO auth_admin_guard VALUES(1);
