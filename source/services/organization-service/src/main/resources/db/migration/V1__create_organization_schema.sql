CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE branches (
  id UUID PRIMARY KEY, code VARCHAR(32) NOT NULL, name VARCHAR(160) NOT NULL, address VARCHAR(500) NOT NULL,
  timezone VARCHAR(80) NOT NULL, opens_at TIME NOT NULL, closes_at TIME NOT NULL, status VARCHAR(30) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_branches_code UNIQUE(code), CONSTRAINT ck_branches_code CHECK(code ~ '^[A-Z][A-Z0-9_-]{1,31}$'),
  CONSTRAINT ck_branches_status CHECK(status IN ('OPEN','TEMPORARILY_CLOSED','INACTIVE')), CONSTRAINT ck_branches_hours CHECK(opens_at<>closes_at)
);
CREATE TABLE employees (
  id UUID PRIMARY KEY, identity_user_id UUID NOT NULL, employee_code VARCHAR(32) NOT NULL, full_name VARCHAR(160) NOT NULL,
  email VARCHAR(320), phone VARCHAR(30), hire_date DATE NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_employees_identity UNIQUE(identity_user_id), CONSTRAINT uq_employees_code UNIQUE(employee_code),
  CONSTRAINT ck_employees_status CHECK(status IN ('ACTIVE','ON_LEAVE','TERMINATED'))
);
CREATE TABLE employee_assignments (
  id UUID PRIMARY KEY, employee_id UUID NOT NULL REFERENCES employees(id), branch_id UUID NOT NULL REFERENCES branches(id), position VARCHAR(80) NOT NULL,
  effective_from DATE NOT NULL, effective_until DATE, assigned_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_assignment_window CHECK(effective_until IS NULL OR effective_until>=effective_from),
  EXCLUDE USING gist(employee_id WITH =, branch_id WITH =, daterange(effective_from,COALESCE(effective_until,'infinity'::date),'[]') WITH &&)
);
CREATE INDEX ix_assignments_branch_active ON employee_assignments(branch_id,effective_from,effective_until);
CREATE TABLE schedules (
  id UUID PRIMARY KEY, branch_id UUID NOT NULL REFERENCES branches(id), week_start DATE NOT NULL, status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1, published_by UUID NOT NULL, published_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_schedule_version UNIQUE(branch_id,week_start,version), CONSTRAINT ck_schedule_monday CHECK(EXTRACT(ISODOW FROM week_start)=1),
  CONSTRAINT ck_schedule_status CHECK(status IN ('DRAFT','PUBLISHED','SUPERSEDED'))
);
CREATE TABLE schedule_entries (
  id UUID PRIMARY KEY, schedule_id UUID NOT NULL REFERENCES schedules(id) ON DELETE CASCADE, employee_id UUID NOT NULL REFERENCES employees(id),
  starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL, role VARCHAR(80) NOT NULL,
  CONSTRAINT ck_schedule_entry_window CHECK(ends_at>starts_at AND ends_at<=starts_at+INTERVAL '24 hours'),
  EXCLUDE USING gist(employee_id WITH =, tstzrange(starts_at,ends_at,'[)') WITH &&)
);
CREATE INDEX ix_schedule_entries_schedule ON schedule_entries(schedule_id,starts_at);
CREATE TABLE attendances (
  id UUID PRIMARY KEY, employee_id UUID NOT NULL REFERENCES employees(id), branch_id UUID NOT NULL REFERENCES branches(id),
  schedule_entry_id UUID REFERENCES schedule_entries(id), checked_in_at TIMESTAMPTZ NOT NULL, checked_out_at TIMESTAMPTZ,
  evidence_type VARCHAR(30) NOT NULL, evidence_ref VARCHAR(500), late_minutes INTEGER NOT NULL DEFAULT 0,
  exception_approved BOOLEAN NOT NULL DEFAULT FALSE, recorded_by UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, CONSTRAINT ck_attendance_window CHECK(checked_out_at IS NULL OR checked_out_at>checked_in_at),
  CONSTRAINT ck_attendance_late CHECK(late_minutes>=0), CONSTRAINT ck_attendance_evidence CHECK(evidence_type IN ('PIN','QR','GPS','DEVICE'))
);
CREATE UNIQUE INDEX uq_attendance_employee_open ON attendances(employee_id) WHERE checked_out_at IS NULL;
CREATE TABLE work_shifts (
  id UUID PRIMARY KEY, branch_id UUID NOT NULL REFERENCES branches(id), register_code VARCHAR(80) NOT NULL, opened_by UUID NOT NULL,
  opened_at TIMESTAMPTZ NOT NULL, opening_cash NUMERIC(19,2) NOT NULL, cash_sales NUMERIC(19,2), cash_in NUMERIC(19,2), cash_out NUMERIC(19,2),
  cash_refunds NUMERIC(19,2), expected_cash NUMERIC(19,2), actual_cash NUMERIC(19,2), variance NUMERIC(19,2), explanation VARCHAR(1000),
  approved_by UUID, closed_by UUID, closed_at TIMESTAMPTZ, status VARCHAR(20) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_work_shift_status CHECK(status IN ('OPEN','CLOSED')), CONSTRAINT ck_opening_cash CHECK(opening_cash>=0),
  CONSTRAINT ck_work_shift_close CHECK((status='OPEN' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_work_shift_open_register ON work_shifts(branch_id,register_code) WHERE status='OPEN';
CREATE TABLE organization_audit (
  id UUID PRIMARY KEY,event_type VARCHAR(100) NOT NULL,actor_user_id UUID NOT NULL,subject_id UUID,branch_id UUID,correlation_id VARCHAR(100) NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE organization_outbox (
  id UUID PRIMARY KEY,aggregate_type VARCHAR(80) NOT NULL,aggregate_id UUID NOT NULL,event_type VARCHAR(120) NOT NULL,event_version INT NOT NULL DEFAULT 1,
  payload JSONB NOT NULL,correlation_id VARCHAR(100) NOT NULL,occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,published_at TIMESTAMPTZ,publish_attempts INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_organization_outbox_unpublished ON organization_outbox(occurred_at) WHERE published_at IS NULL;
CREATE TABLE organization_idempotency (
  id UUID PRIMARY KEY,operation VARCHAR(80) NOT NULL,idempotency_key VARCHAR(200) NOT NULL,request_hash VARCHAR(64) NOT NULL,resource_id UUID,
  expires_at TIMESTAMPTZ NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,completed_at TIMESTAMPTZ,
  CONSTRAINT uq_organization_idempotency UNIQUE(operation,idempotency_key),CONSTRAINT ck_organization_idempotency_expiry CHECK(expires_at>created_at)
);
