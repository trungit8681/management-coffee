ALTER TABLE customer_order ADD COLUMN subtotal_vnd bigint;
UPDATE customer_order SET subtotal_vnd=total_vnd WHERE subtotal_vnd IS NULL;
ALTER TABLE customer_order ALTER COLUMN subtotal_vnd SET NOT NULL;
ALTER TABLE customer_order ADD COLUMN voucher_discount_vnd bigint NOT NULL DEFAULT 0 CHECK (voucher_discount_vnd >= 0);
ALTER TABLE customer_order ADD COLUMN points_discount_vnd bigint NOT NULL DEFAULT 0 CHECK (points_discount_vnd >= 0);

CREATE TABLE checkout_saga (
  order_id uuid PRIMARY KEY REFERENCES customer_order(id),
  idempotency_key text NOT NULL UNIQUE,
  request_hash text NOT NULL,
  voucher_code text,
  customer_id uuid,
  points bigint NOT NULL DEFAULT 0 CHECK (points >= 0),
  cash_received_vnd bigint NOT NULL CHECK (cash_received_vnd > 0),
  status text NOT NULL CHECK (status IN ('STARTED','BENEFITS_RESERVED','STOCK_DEDUCTED','PAID_PENDING','COMPLETED','COMPENSATING','FAILED')),
  payment_id uuid,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE checkout_saga_step (
  order_id uuid NOT NULL REFERENCES checkout_saga(order_id),
  step_type text NOT NULL,
  resource_id uuid NOT NULL,
  quantity bigint,
  status text NOT NULL CHECK (status IN ('DONE','COMPENSATED')),
  PRIMARY KEY(order_id,step_type,resource_id)
);

CREATE INDEX ix_checkout_saga_recovery ON checkout_saga(updated_at) WHERE status IN ('STARTED','BENEFITS_RESERVED','STOCK_DEDUCTED','PAID_PENDING','COMPENSATING');

