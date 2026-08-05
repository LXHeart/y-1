-- Expand KYB retention tokens with finite leases and terminal audit deadlines.
-- V9 is immutable; compatibility data is backfilled separately in V11.
ALTER TABLE media_kyb_retention
    ADD COLUMN reference_type varchar(24) NOT NULL DEFAULT 'attachment',
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN retained_until timestamptz,
    ADD COLUMN release_requested_at timestamptz,
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

ALTER TABLE media_kyb_retention
    ADD CONSTRAINT chk_media_kyb_retention_reference_type
        CHECK (reference_type IN ('attachment', 'review_request')),
    ADD CONSTRAINT chk_media_kyb_retention_deadline
        CHECK (lease_until IS NOT NULL OR retained_until IS NOT NULL OR released_at IS NOT NULL) NOT VALID;
