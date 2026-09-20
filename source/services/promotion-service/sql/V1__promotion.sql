CREATE TABLE voucher (
  id uuid PRIMARY KEY,
  code text NOT NULL UNIQUE,
  branch_id uuid NOT NULL,
  discount_vnd bigint NOT NULL CHECK(discount_vnd>0),
  min_total_vnd bigint NOT NULL CHECK(min_total_vnd>=0),
  usage_limit integer NOT NULL CHECK(usage_limit>0),
  used_count integer NOT NULL DEFAULT 0 CHECK(used_count>=0),
  reserved_count integer NOT NULL DEFAULT 0 CHECK(reserved_count>=0),
  expires_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK(used_count+reserved_count<=usage_limit)
);
CREATE TABLE voucher_reservation (
  id uuid PRIMARY KEY,
  voucher_id uuid NOT NULL REFERENCES voucher(id),
  order_id uuid NOT NULL UNIQUE,
  total_vnd bigint NOT NULL CHECK(total_vnd>0),
  discount_vnd bigint NOT NULL CHECK(discount_vnd>0),
  status text NOT NULL CHECK(status IN ('RESERVED','COMMITTED','RELEASED')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE command_result (idempotency_key text PRIMARY KEY,request_hash text NOT NULL,result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz);
