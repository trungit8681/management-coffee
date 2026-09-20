CREATE TABLE cash_payment (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL UNIQUE,
  branch_id uuid NOT NULL,
  amount_due_vnd bigint NOT NULL CHECK (amount_due_vnd > 0),
  cash_received_vnd bigint NOT NULL CHECK (cash_received_vnd >= amount_due_vnd),
  change_vnd bigint GENERATED ALWAYS AS (cash_received_vnd - amount_due_vnd) STORED,
  actor_id uuid NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  request_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE cash_refund (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES cash_payment(id),
  amount_vnd bigint NOT NULL CHECK (amount_vnd > 0),
  reason text NOT NULL,
  approved_by uuid NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  request_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);
