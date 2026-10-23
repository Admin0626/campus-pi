-- Register V008 with G before shared deployment. Apply after V007; never reseed users.
-- Identity comes only from a backend code2Session exchange, never a submitted openid.
CREATE TABLE account_wechat_bindings (
  app_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  open_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  user_id BIGINT NOT NULL,
  bound_at DATETIME(3) NOT NULL,
  PRIMARY KEY (app_id,open_id),
  UNIQUE KEY uq_wechat_account(app_id,user_id),
  FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
