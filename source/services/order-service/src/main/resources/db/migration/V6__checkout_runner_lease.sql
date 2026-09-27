ALTER TABLE checkout_saga ADD COLUMN runner_id uuid;
ALTER TABLE checkout_saga ADD COLUMN lease_until timestamptz;

