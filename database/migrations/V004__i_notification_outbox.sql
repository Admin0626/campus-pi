-- G must register this version before applying it to a shared database.
-- Apply after V003. Do not rerun the initial 25-table schema.
-- Existing notification IDs, receipts and attempts remain unchanged.
CREATE TABLE notification_publish_jobs (
  job_id VARCHAR(64) PRIMARY KEY,
  notification_id VARCHAR(50) NOT NULL UNIQUE,
  recipient_count INT NOT NULL,
  job_status VARCHAR(10) NOT NULL DEFAULT '待执行',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3),
  FOREIGN KEY (notification_id) REFERENCES system_notifications(notification_id),
  CHECK (recipient_count>=0),
  CHECK (job_status IN ('待执行','已完成','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_outbox (
  delivery_id BIGINT PRIMARY KEY,
  notification_id VARCHAR(50) NOT NULL,
  work_status VARCHAR(10) NOT NULL DEFAULT '待执行',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at DATETIME(3),
  FOREIGN KEY (delivery_id) REFERENCES notification_delivery(delivery_id),
  FOREIGN KEY (notification_id) REFERENCES notification_publish_jobs(notification_id),
  INDEX ix_notification_outbox_pending(work_status,delivery_id),
  CHECK (work_status IN ('待执行','已完成','已取消'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
