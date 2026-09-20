-- Identity Service owns every object in this migration.
-- branch_id values reference Organization Service identifiers and intentionally have no cross-service FK.

CREATE TABLE identity_users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(320),
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    security_version BIGINT NOT NULL DEFAULT 1,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_identity_users_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_identity_users_failed_attempts
        CHECK (failed_login_attempts >= 0),
    CONSTRAINT ck_identity_users_security_version
        CHECK (security_version > 0),
    CONSTRAINT ck_identity_users_username_not_blank
        CHECK (btrim(username) <> ''),
    CONSTRAINT ck_identity_users_email_not_blank
        CHECK (email IS NULL OR btrim(email) <> '')
);

CREATE UNIQUE INDEX uq_identity_users_username_ci
    ON identity_users (lower(username));

CREATE UNIQUE INDEX uq_identity_users_email_ci
    ON identity_users (lower(email))
    WHERE email IS NOT NULL;

CREATE INDEX ix_identity_users_status_locked_until
    ON identity_users (status, locked_until);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT ck_roles_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    code VARCHAR(120) NOT NULL,
    resource VARCHAR(80) NOT NULL,
    action VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT uq_permissions_resource_action UNIQUE (resource, action),
    CONSTRAINT ck_permissions_code_format CHECK (code ~ '^[a-z][a-z0-9_]*:[a-z][a-z0-9_]*$')
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by UUID REFERENCES identity_users(id) ON DELETE SET NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX ix_role_permissions_permission_id
    ON role_permissions (permission_id);

CREATE TABLE user_role_assignments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    branch_id UUID,
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMPTZ,
    assigned_by UUID REFERENCES identity_users(id) ON DELETE SET NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES identity_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_role_assignment_window
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_user_role_assignment_revocation
        CHECK (revoked_at IS NULL OR revoked_at >= valid_from)
);

-- PostgreSQL treats NULL values as distinct, so global and branch assignments use separate partial indexes.
CREATE UNIQUE INDEX uq_user_role_active_global
    ON user_role_assignments (user_id, role_id)
    WHERE branch_id IS NULL AND revoked_at IS NULL;

CREATE UNIQUE INDEX uq_user_role_active_branch
    ON user_role_assignments (user_id, role_id, branch_id)
    WHERE branch_id IS NOT NULL AND revoked_at IS NULL;

CREATE INDEX ix_user_role_lookup
    ON user_role_assignments (user_id, branch_id, valid_from, valid_until)
    WHERE revoked_at IS NULL;

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_users(id) ON DELETE CASCADE,
    token_family_id UUID NOT NULL,
    current_token_jti UUID NOT NULL,
    device_id VARCHAR(200) NOT NULL,
    device_name VARCHAR(200),
    user_agent VARCHAR(500),
    ip_address INET,
    expires_at TIMESTAMPTZ NOT NULL,
    last_rotated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_refresh_sessions_family UNIQUE (token_family_id),
    CONSTRAINT ck_refresh_sessions_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_sessions_revocation
        CHECK ((revoked_at IS NULL AND revoke_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);

CREATE INDEX ix_refresh_sessions_user_active
    ON refresh_sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    jti UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES refresh_sessions(id) ON DELETE CASCADE,
    parent_jti UUID REFERENCES refresh_tokens(jti) ON DELETE RESTRICT,
    token_hash VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(200),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_tokens_used CHECK (used_at IS NULL OR used_at >= issued_at),
    CONSTRAINT ck_refresh_tokens_revocation
        CHECK ((revoked_at IS NULL AND revoke_reason IS NULL)
            OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);

CREATE INDEX ix_refresh_tokens_session_issued
    ON refresh_tokens (session_id, issued_at DESC);

CREATE TABLE identity_audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    actor_user_id UUID REFERENCES identity_users(id) ON DELETE SET NULL,
    subject_user_id UUID REFERENCES identity_users(id) ON DELETE SET NULL,
    branch_id UUID,
    correlation_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100),
    ip_address INET,
    outcome VARCHAR(20) NOT NULL,
    reason_code VARCHAR(100),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_identity_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX ix_identity_audit_subject_time
    ON identity_audit_events (subject_user_id, occurred_at DESC);

CREATE INDEX ix_identity_audit_actor_time
    ON identity_audit_events (actor_user_id, occurred_at DESC);

CREATE INDEX ix_identity_audit_type_time
    ON identity_audit_events (event_type, occurred_at DESC);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(160) NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    causation_id VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    CONSTRAINT ck_outbox_event_version CHECK (event_version > 0),
    CONSTRAINT ck_outbox_publish_attempts CHECK (publish_attempts >= 0)
);

CREATE INDEX ix_outbox_unpublished
    ON outbox_events (occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    resource_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_operation_key UNIQUE (operation, idempotency_key),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_idempotency_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599)
);

CREATE INDEX ix_idempotency_expiry
    ON idempotency_records (expires_at);

