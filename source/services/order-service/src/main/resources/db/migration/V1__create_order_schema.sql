CREATE TABLE customer_order (
  id uuid PRIMARY KEY,
  branch_id uuid NOT NULL,
  channel text NOT NULL CHECK (channel IN ('POS','PICKUP','DELIVERY')),
  status text NOT NULL CHECK (status IN ('AWAITING_CASH','CONFIRMED','CANCELLED')),
  payment_status text NOT NULL CHECK (payment_status IN ('PENDING_CASH','PAID')),
  total_vnd bigint NOT NULL CHECK (total_vnd > 0),
  payment_id uuid UNIQUE,
  actor_id uuid NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  request_hash text NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE order_item (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES customer_order(id),
  variant_id uuid NOT NULL,
  quantity integer NOT NULL CHECK (quantity > 0),
  unit_price_vnd bigint NOT NULL CHECK (unit_price_vnd >= 0),
  price_version bigint NOT NULL CHECK (price_version > 0),
  line_total_vnd bigint NOT NULL CHECK (line_total_vnd = quantity * unit_price_vnd)
);
CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
