CREATE TABLE ingredient (
  id uuid PRIMARY KEY,
  code text NOT NULL UNIQUE,
  name text NOT NULL,
  unit text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE stock_balance (
  branch_id uuid NOT NULL,
  ingredient_id uuid NOT NULL REFERENCES ingredient(id),
  quantity bigint NOT NULL CHECK (quantity >= 0),
  version bigint NOT NULL DEFAULT 0,
  PRIMARY KEY(branch_id,ingredient_id)
);
CREATE TABLE stock_ledger (
  id uuid PRIMARY KEY,
  branch_id uuid NOT NULL,
  ingredient_id uuid NOT NULL REFERENCES ingredient(id),
  quantity_delta bigint NOT NULL CHECK (quantity_delta <> 0),
  kind text NOT NULL CHECK (kind IN ('IN','OUT')),
  reference_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(kind,reference_id,ingredient_id,branch_id)
);
CREATE TABLE command_result (idempotency_key text PRIMARY KEY, request_hash text NOT NULL, result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY, aggregate_id uuid NOT NULL, event_type text NOT NULL, payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz);
