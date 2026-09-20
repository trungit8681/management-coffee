CREATE TABLE customer (id uuid PRIMARY KEY,identity_user_id uuid UNIQUE,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE point_wallet (
  customer_id uuid PRIMARY KEY REFERENCES customer(id),
  available_points bigint NOT NULL DEFAULT 0 CHECK(available_points>=0),
  reserved_points bigint NOT NULL DEFAULT 0 CHECK(reserved_points>=0),
  version bigint NOT NULL DEFAULT 0
);
CREATE TABLE point_reservation (
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL REFERENCES customer(id),
  order_id uuid NOT NULL UNIQUE,
  points bigint NOT NULL CHECK(points>0),
  status text NOT NULL CHECK(status IN ('RESERVED','COMMITTED','RELEASED')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE point_ledger (
  id uuid PRIMARY KEY,
  customer_id uuid NOT NULL REFERENCES customer(id),
  points_delta bigint NOT NULL CHECK(points_delta<>0),
  kind text NOT NULL CHECK(kind IN ('CREDIT','REDEEM')),
  reference_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(kind,reference_id)
);
CREATE TABLE command_result (idempotency_key text PRIMARY KEY,request_hash text NOT NULL,result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz);
