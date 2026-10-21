-- Shared numbering service. Apply after V002; G registers the final shared migration order.
-- The counter participates in the same transaction as order creation, not a timestamp/random guess.
CREATE TABLE business_number_sequences (
  number_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  counter_value BIGINT NOT NULL,
  CHECK(counter_value>=0 AND counter_value<=99999999999999)
) ENGINE=InnoDB;
INSERT INTO business_number_sequences(number_type,counter_value)
SELECT 'ORDER',COALESCE(MAX(CAST(SUBSTRING(order_id,6) AS UNSIGNED)),0) FROM user_orders
WHERE order_id REGEXP '^ORDER[0-9]{14}$';
