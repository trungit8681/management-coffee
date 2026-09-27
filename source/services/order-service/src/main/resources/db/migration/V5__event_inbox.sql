CREATE TABLE inbox_event (
  event_id uuid PRIMARY KEY,
  event_type text NOT NULL,
  source text NOT NULL,
  payload jsonb NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);

