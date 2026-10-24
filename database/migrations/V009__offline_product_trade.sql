-- One-time upgrade after V008, with the application stopped. Retain historical money records.
-- OFFLINE records are progress acknowledgements only; no platform payment or refund is made.
-- The unnamed baseline status CHECK has a server-generated name, resolved on this table only.
DELIMITER $$
CREATE PROCEDURE campus_upgrade_offline_order_status()
BEGIN
  DECLARE old_check VARCHAR(64);
  DECLARE match_count INT;
  SELECT COUNT(*), MIN(tc.CONSTRAINT_NAME) INTO match_count,old_check
  FROM information_schema.TABLE_CONSTRAINTS tc
  JOIN information_schema.CHECK_CONSTRAINTS cc
    ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
  WHERE tc.TABLE_SCHEMA=DATABASE() AND tc.TABLE_NAME='user_orders' AND tc.CONSTRAINT_TYPE='CHECK'
    AND cc.CHECK_CLAUSE LIKE '%order_status%' AND cc.CHECK_CLAUSE LIKE '%order_type%';
  IF match_count<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Expected exactly one original user_orders status constraint';
  END IF;
  SET @offline_status_ddl=CONCAT('ALTER TABLE user_orders DROP CHECK `',REPLACE(old_check,'`','``'),
    '`, ADD COLUMN trade_channel VARCHAR(20) NULL AFTER order_status, ',
    'ADD CONSTRAINT ck_order_type_status CHECK ((order_type=''商品'' AND product_id IS NOT NULL AND reward_task_id IS NULL AND order_status IN (''待线下交易'',''待支付'',''已支付'',''已发货'',''待确认收货'',''已完成'',''已取消'',''售后中'',''已退款'')) OR (order_type=''任务'' AND product_id IS NULL AND reward_task_id IS NOT NULL AND quantity=1 AND shipping_fee=0 AND order_status IN (''进行中'',''待验收'',''已完成'',''未完成'',''已取消'',''售后中'',''已退款''))), ',
    'ADD CONSTRAINT ck_order_offline_channel CHECK ((trade_channel IS NULL AND order_status<>''待线下交易'') OR (trade_channel IS NOT NULL AND trade_channel=''OFFLINE'' AND order_type=''商品'' AND actual_amount=0 AND shipping_fee=0 AND payment_method IS NULL AND payment_transaction_no IS NULL AND payment_time IS NULL AND ((order_status IN (''待线下交易'',''已取消'') AND shipping_time IS NULL AND completion_time IS NULL) OR (order_status=''待确认收货'' AND shipping_time IS NOT NULL AND completion_time IS NULL) OR (order_status=''已完成'' AND shipping_time IS NOT NULL AND completion_time IS NOT NULL))))');
  PREPARE offline_status_stmt FROM @offline_status_ddl;
  EXECUTE offline_status_stmt;
  DEALLOCATE PREPARE offline_status_stmt;
END$$
CALL campus_upgrade_offline_order_status()$$
DROP PROCEDURE campus_upgrade_offline_order_status$$
DELIMITER ;

-- Convert only unambiguously unpaid, unfulfilled and undisputed reservations. Do not invent payments.
UPDATE user_orders o SET o.order_status='待线下交易',o.trade_channel='OFFLINE'
WHERE o.order_type='商品' AND o.order_status='待支付' AND o.actual_amount=0 AND o.shipping_fee=0
  AND o.payment_method IS NULL AND o.payment_transaction_no IS NULL AND o.payment_time IS NULL
  AND o.shipping_time IS NULL AND o.completion_time IS NULL
  AND NOT EXISTS (SELECT 1 FROM order_aftersales a WHERE a.order_id=o.order_id);

-- Replace the two old direct-SQL entry points too, so they cannot create new payment orders.
DROP PROCEDURE IF EXISTS sp_product_order;
DROP PROCEDURE IF EXISTS sp_product_cancel;
DELIMITER $$
CREATE PROCEDURE sp_product_order(IN p_actor BIGINT, IN p_product BIGINT, IN p_quantity INT, IN p_order CHAR(19), IN p_address VARCHAR(500))
BEGIN
  DECLARE v_owner BIGINT;
  DECLARE v_stock INT;
  DECLARE v_price DECIMAL(12,2);
  DECLARE v_name VARCHAR(255);
  DECLARE v_campus VARCHAR(50);
  DECLARE v_audit VARCHAR(10);
  DECLARE v_shelf VARCHAR(10);
  DECLARE v_mode VARCHAR(20);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  SELECT user_id,stock_quantity,discount_price,product_name,campus,audit_status,shelf_status,trade_mode
    INTO v_owner,v_stock,v_price,v_name,v_campus,v_audit,v_shelf,v_mode FROM product_info WHERE product_id=p_product FOR UPDATE;
  IF v_owner IS NULL OR v_owner=p_actor OR v_audit<>'已通过' OR v_shelf<>'在售' OR v_mode<>'校园自提'
     OR p_quantity IS NULL OR p_quantity<=0 OR p_quantity>100000 OR v_stock<p_quantity THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Offline product unavailable or insufficient stock';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=v_campus)
     OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=v_owner AND account_status='ACTIVE' AND identity_verification='已通过' AND campus_verification='已通过' AND campus=v_campus) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Verified active campus buyer and seller required';
  END IF;
  IF p_address IS NULL OR TRIM(p_address)='' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Offline meeting location required'; END IF;
  INSERT INTO user_orders(order_id,order_type,user_id,seller_id,product_id,item_name,quantity,unit_price,total_amount,order_status,trade_channel,shipping_address)
    VALUES(p_order,'商品',p_actor,v_owner,p_product,v_name,p_quantity,v_price,v_price*p_quantity,'待线下交易','OFFLINE',p_address);
  UPDATE product_info SET stock_quantity=stock_quantity-p_quantity,shelf_status=IF(stock_quantity=0,'已售出',shelf_status) WHERE product_id=p_product;
  COMMIT;
END$$
CREATE PROCEDURE sp_product_cancel(IN p_actor BIGINT, IN p_order CHAR(19))
BEGIN
  DECLARE v_product BIGINT;
  DECLARE v_seller BIGINT;
  DECLARE v_user BIGINT;
  DECLARE v_quantity INT;
  DECLARE v_stock BIGINT;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  START TRANSACTION;
  SELECT product_id,user_id,seller_id,quantity INTO v_product,v_user,v_seller,v_quantity FROM user_orders
    WHERE order_id=p_order AND order_type='商品' AND trade_channel='OFFLINE' AND order_status='待线下交易'
      AND actual_amount=0 AND shipping_time IS NULL AND completion_time IS NULL FOR UPDATE;
  IF v_product IS NULL OR NOT EXISTS(SELECT 1 FROM users WHERE user_id=p_actor AND account_status='ACTIVE' AND (user_id=v_user OR role='ADMIN')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only pending offline product order may be cancelled';
  END IF;
  SELECT stock_quantity INTO v_stock FROM product_info WHERE product_id=v_product AND user_id=v_seller FOR UPDATE;
  IF v_stock IS NULL OR v_stock+v_quantity>2147483647 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid product ownership or stock overflow'; END IF;
  UPDATE user_orders SET order_status='已取消' WHERE order_id=p_order;
  UPDATE product_info SET stock_quantity=stock_quantity+v_quantity,shelf_status=IF(shelf_status='已售出' AND audit_status='已通过','在售',shelf_status) WHERE product_id=v_product;
  INSERT INTO admin_audit_log(operator_id,object_type,object_id,action_name,reason) VALUES(p_actor,'订单',p_order,'取消','取消待线下交易订单并释放库存');
  COMMIT;
END$$
DELIMITER ;
