-- Slice 7A: PostgreSQL-clock claim lease and bounded retry metadata.
ALTER TABLE intelligence_outbox ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE intelligence_outbox ADD COLUMN next_attempt_at timestamptz;
ALTER TABLE intelligence_outbox ADD COLUMN claimed_until timestamptz;
ALTER TABLE intelligence_outbox ADD COLUMN claim_token uuid;
ALTER TABLE intelligence_outbox ADD COLUMN last_error_code varchar(64);

UPDATE intelligence_outbox SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;
ALTER TABLE intelligence_outbox ALTER COLUMN next_attempt_at SET DEFAULT now();
ALTER TABLE intelligence_outbox ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX idx_intelligence_outbox_publish_ready
    ON intelligence_outbox(next_attempt_at, claimed_until, created_at)
    WHERE published_at IS NULL;
