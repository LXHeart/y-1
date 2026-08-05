-- Durable desired state for cross-service KYB media retention reconciliation.
CREATE TABLE kyb_media_retention_sync (
    media_reference_id uuid NOT NULL,
    reference_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    reference_type varchar(24) NOT NULL,
    desired_state varchar(16) NOT NULL,
    retain_until timestamptz,
    remote_lease_until timestamptz,
    sync_status varchar(16) NOT NULL DEFAULT 'pending',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (media_reference_id, reference_id),
    CONSTRAINT chk_kyb_retention_sync_reference_type
        CHECK (reference_type IN ('attachment', 'review_request')),
    CONSTRAINT chk_kyb_retention_sync_desired_state
        CHECK (desired_state IN ('live', 'sealed', 'released')),
    CONSTRAINT chk_kyb_retention_sync_status
        CHECK (sync_status IN ('pending', 'synced')),
    CONSTRAINT chk_kyb_retention_sync_sealed_deadline
        CHECK (desired_state <> 'sealed' OR retain_until IS NOT NULL),
    CONSTRAINT chk_kyb_retention_sync_claim
        CHECK ((claim_token IS NULL) = (claimed_until IS NULL))
);

CREATE INDEX idx_kyb_media_retention_sync_due
    ON kyb_media_retention_sync(next_attempt_at, updated_at)
    WHERE sync_status = 'pending' OR desired_state = 'live';

CREATE INDEX idx_kyb_media_retention_sync_reference
    ON kyb_media_retention_sync(reference_id, organization_id);
