CREATE TABLE product (
  id uuid PRIMARY KEY,
  sku text NOT NULL UNIQUE,
  name text NOT NULL,
  category text NOT NULL,
  status text NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  actor_id uuid NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  request_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE variant (
  id uuid PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES product(id),
  code text NOT NULL,
  name text NOT NULL,
  status text NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  UNIQUE(product_id,code)
);
CREATE TABLE price (
  id uuid PRIMARY KEY,
  variant_id uuid NOT NULL REFERENCES variant(id),
  branch_id uuid NOT NULL,
  channel text NOT NULL CHECK (channel IN ('POS','PICKUP','DELIVERY')),
  unit_price_vnd bigint NOT NULL CHECK (unit_price_vnd >= 0),
  version bigint NOT NULL CHECK (version > 0),
  active boolean NOT NULL DEFAULT true,
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(variant_id,branch_id,channel,version)
);
CREATE UNIQUE INDEX one_active_price ON price(variant_id,branch_id,channel) WHERE active;
CREATE TABLE command_result (
  idempotency_key text PRIMARY KEY,
  request_hash text NOT NULL,
  result_id uuid NOT NULL
);
CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
