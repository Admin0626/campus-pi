-- User-confirmed rule: opt-out prevents receiving; DND delays delivery; security has no bypass.
-- Apply after V004. Do not rewrite the already-applied V004 or recreate baseline tables.
ALTER TABLE notification_preferences
  ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_notification_preference_version CHECK (config_version>=0);

ALTER TABLE notification_outbox
  ADD COLUMN available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN preference_version BIGINT NOT NULL DEFAULT 0,
  ADD INDEX ix_notification_outbox_due(work_status,available_at,delivery_id),
  ADD CONSTRAINT ck_notification_outbox_preference_version CHECK (preference_version>=0);
