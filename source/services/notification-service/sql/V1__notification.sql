CREATE TABLE template (
  id uuid PRIMARY KEY,
  code text NOT NULL UNIQUE,
  body text NOT NULL,
  status text NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE notification (
  id uuid PRIMARY KEY,
  recipient_id uuid NOT NULL,
  branch_id uuid NOT NULL,
  template_id uuid NOT NULL REFERENCES template(id),
  reference_id uuid NOT NULL,
  rendered_body text NOT NULL,
  status text NOT NULL CHECK(status IN ('QUEUED','DELIVERED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  delivered_at timestamptz,
  UNIQUE(recipient_id,template_id,reference_id)
);
CREATE TABLE delivery_log (id uuid PRIMARY KEY,notification_id uuid NOT NULL UNIQUE REFERENCES notification(id),status text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE command_result (idempotency_key text PRIMARY KEY,request_hash text NOT NULL,result_id uuid NOT NULL);
CREATE TABLE outbox_event (id uuid PRIMARY KEY,aggregate_id uuid NOT NULL,event_type text NOT NULL,payload jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),published_at timestamptz);
