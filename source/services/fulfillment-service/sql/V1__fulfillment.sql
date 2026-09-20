CREATE TABLE delivery (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  branch_id uuid NOT NULL,
  address text NOT NULL,
  status text NOT NULL CHECK(status IN ('NEW','ASSIGNED','DELIVERED','FAILED')),
  driver_id uuid,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE delivery_attempt (
  id uuid PRIMARY KEY,
  delivery_id uuid NOT NULL REFERENCES delivery(id),
  result text NOT NULL CHECK(result IN ('DELIVERED','FAILED')),
  actor_id uuid NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE command_result (idempotency_key text PRIMARY KEY,request_hash text NOT NULL,result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz);
