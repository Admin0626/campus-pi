-- Apply only after G registers the shared number. No legacy context is guessed/backfilled.
-- This records application-time state; it does not implement payment verification or refunds.
CREATE TABLE aftersale_order_context (
  aftersale_id CHAR(19) PRIMARY KEY,
  order_id CHAR(19) NOT NULL,
  order_type VARCHAR(10) NOT NULL,
  original_order_status VARCHAR(10) NOT NULL,
  captured_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  FOREIGN KEY (aftersale_id) REFERENCES order_aftersales(aftersale_id),
  FOREIGN KEY (order_id) REFERENCES user_orders(order_id),
  CHECK ((order_type='商品' AND original_order_status IN ('已支付','已发货','待确认收货','已完成'))
      OR (order_type='任务' AND original_order_status IN ('进行中','待验收','已完成','未完成','已取消'))),
  INDEX idx_aftersale_context_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
