ALTER TABLE customer_order DROP CONSTRAINT customer_order_status_check;
ALTER TABLE customer_order ADD CONSTRAINT customer_order_status_check CHECK (status IN ('AWAITING_CASH','CONFIRMED','CANCELLED','REFUND_PENDING','REFUNDED'));
ALTER TABLE customer_order DROP CONSTRAINT customer_order_payment_status_check;
ALTER TABLE customer_order ADD CONSTRAINT customer_order_payment_status_check CHECK (payment_status IN ('PENDING_CASH','PAID','REFUNDED'));
ALTER TABLE customer_order ADD COLUMN cancel_reason text;
ALTER TABLE customer_order ADD COLUMN approved_by uuid;
ALTER TABLE customer_order ADD COLUMN refund_id uuid UNIQUE;
