-- Issue #38: account-level export, closure orchestration, retention execution and immutable PII audit.
-- Financial/dispute/audit facts deliberately keep logical account references after closure.

DO $migration$
BEGIN
    -- app_users is owned by database-bootstrap. Production runs that bootstrap first,
    -- while isolated identity migration tests intentionally start without the shared table.
    IF to_regclass('public.app_users') IS NOT NULL THEN
        ALTER TABLE app_users ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
    END IF;
END
$migration$;

CREATE TABLE personal_data_export_request (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'processing', 'completed', 'failed', 'expired')),
    format varchar(16) NOT NULL DEFAULT 'zip' CHECK (format IN ('zip')),
    artifact bytea,
    artifact_sha256 varchar(64),
    artifact_size_bytes bigint CHECK (artifact_size_bytes IS NULL OR artifact_size_bytes >= 0),
    expires_at timestamptz,
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_until timestamptz,
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (status <> 'completed' OR
           (artifact IS NOT NULL AND artifact_sha256 IS NOT NULL AND artifact_size_bytes IS NOT NULL
            AND expires_at IS NOT NULL AND completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_personal_data_export_active
    ON personal_data_export_request(account_id)
    WHERE status IN ('queued', 'processing', 'completed');
CREATE INDEX idx_personal_data_export_claim
    ON personal_data_export_request(next_attempt_at, created_at)
    WHERE status IN ('queued', 'failed');
CREATE INDEX idx_personal_data_export_expiry
    ON personal_data_export_request(expires_at)
    WHERE status = 'completed';

CREATE TABLE account_closure_request (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    status varchar(24) NOT NULL
        CHECK (status IN ('blocked', 'retention', 'erasing', 'completed', 'cancelled', 'failed')),
    blockers jsonb NOT NULL DEFAULT '[]'::jsonb,
    retention_until timestamptz,
    attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_until timestamptz,
    error_code varchar(64),
    requested_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (status NOT IN ('retention', 'erasing', 'completed') OR retention_until IS NOT NULL)
);

CREATE UNIQUE INDEX uq_account_closure_active
    ON account_closure_request(account_id)
    WHERE status IN ('retention', 'erasing', 'failed');
CREATE INDEX idx_account_closure_due
    ON account_closure_request(next_attempt_at, retention_until)
    WHERE status IN ('retention', 'failed');

CREATE TABLE pii_lifecycle_audit (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    action varchar(48) NOT NULL,
    request_id uuid,
    actor_type varchar(16) NOT NULL DEFAULT 'account'
        CHECK (actor_type IN ('account', 'system', 'admin')),
    detail jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_pii_lifecycle_audit_account
    ON pii_lifecycle_audit(account_id, occurred_at DESC, id DESC);

CREATE FUNCTION reject_pii_lifecycle_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'PII lifecycle audit is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pii_lifecycle_audit_immutable
    BEFORE UPDATE OR DELETE ON pii_lifecycle_audit
    FOR EACH ROW EXECUTE FUNCTION reject_pii_lifecycle_audit_mutation();
