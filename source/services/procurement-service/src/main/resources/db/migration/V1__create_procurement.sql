CREATE TABLE supplier (id uuid PRIMARY KEY,code text NOT NULL UNIQUE,name text NOT NULL,status text NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE purchase_order (
  id uuid PRIMARY KEY,
  branch_id uuid NOT NULL,
  supplier_id uuid NOT NULL REFERENCES supplier(id),
  ingredient_id uuid NOT NULL,
  quantity bigint NOT NULL CHECK(quantity>0),
  unit_cost_vnd bigint NOT NULL CHECK(unit_cost_vnd>=0),
  status text NOT NULL CHECK(status IN ('PENDING','APPROVED','CANCELLED')),
  created_by uuid NOT NULL,
  approved_by uuid,
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE command_result (idempotency_key text PRIMARY KEY,request_hash text NOT NULL,result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz);
