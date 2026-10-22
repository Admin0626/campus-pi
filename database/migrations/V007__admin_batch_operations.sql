-- Register V007 with G before shared deployment. Apply after V006. Never reseed existing data.
CREATE TABLE admin_batch_jobs (
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  operator_id BIGINT NOT NULL,
  session_hash CHAR(64) CHARACTER SET ascii NOT NULL,
  auth_version BIGINT NOT NULL,
  request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_digest CHAR(64) CHARACTER SET ascii NOT NULL,
  operation_kind VARCHAR(30) NOT NULL,
  action_body JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (operator_id) REFERENCES users(user_id),
  UNIQUE KEY uq_admin_batch_replay(operator_id, request_key),
  INDEX ix_admin_batch_owner(operator_id, created_at),
  CHECK (operation_kind IN ('users','products','posts','tasks'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE admin_batch_items (
  job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  item_no INT NOT NULL,
  target_id VARCHAR(50) COLLATE utf8mb4_bin NOT NULL,
  item_status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
  http_status INT,
  error_code VARCHAR(40),
  result_message VARCHAR(500),
  completed_at DATETIME(3),
  PRIMARY KEY (job_id, item_no),
  UNIQUE KEY uq_admin_batch_target(job_id, target_id),
  FOREIGN KEY (job_id) REFERENCES admin_batch_jobs(job_id),
  INDEX ix_admin_batch_pending(item_status, job_id, item_no),
  CHECK (item_no BETWEEN 1 AND 50),
  CHECK (item_status IN ('PENDING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
