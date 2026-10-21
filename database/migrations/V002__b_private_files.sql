-- B: apply once after V001; keep private objects outside the static web root.
-- Shared deployment version/order still requires G registration. No baseline tables are replaced.
CREATE TABLE business_files (
  file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  purpose VARCHAR(20) NOT NULL,
  mime_type VARCHAR(40) NOT NULL,
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
  scan_status VARCHAR(10) NOT NULL,
  scanner_name VARCHAR(20) NOT NULL,
  scanned_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  unbound_expires_at DATETIME(3) NOT NULL,
  FOREIGN KEY(owner_id) REFERENCES users(user_id),
  CHECK(purpose IN ('AVATAR','PRODUCT_IMAGE','VERIFICATION','TASK_PROOF','CHAT_IMAGE','AFTERSALE')),
  CHECK(mime_type IN ('image/png','image/jpeg','image/webp','application/pdf')),
  CHECK(size_bytes>0 AND size_bytes<=10485760),
  CHECK(scan_status='CLEAN'),
  INDEX idx_file_unbound_expiry(unbound_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE business_file_bindings (
  file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  object_type VARCHAR(20) NOT NULL,
  object_id VARCHAR(50) NOT NULL,
  bound_at DATETIME(3) NOT NULL,
  PRIMARY KEY(file_id,object_type,object_id),
  FOREIGN KEY(file_id) REFERENCES business_files(file_id),
  CHECK(object_type IN ('AVATAR','PRODUCT','VERIFICATION','TASK_PROOF','MESSAGE','AFTERSALE')),
  INDEX idx_file_binding_object(object_type,object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE business_file_grants (
  token_hash CHAR(64) CHARACTER SET ascii PRIMARY KEY,
  file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_hash CHAR(64) CHARACTER SET ascii,
  expires_at DATETIME(3) NOT NULL,
  FOREIGN KEY(file_id) REFERENCES business_files(file_id),
  INDEX idx_file_grant_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE approved_campus_registry (
  campus VARCHAR(50) NOT NULL,
  student_no VARCHAR(30) NOT NULL,
  user_id BIGINT NOT NULL UNIQUE,
  PRIMARY KEY(campus,student_no),
  FOREIGN KEY(user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Existing duplicate approved campus/student pairs must be resolved before applying this migration.
INSERT INTO approved_campus_registry(campus,student_no,user_id)
SELECT campus,student_no,user_id FROM users WHERE campus_verification='已通过';
