-- Slice 7A: PostgreSQL-clock claim lease and bounded retry metadata.
ALTER TABLE trust_outbox ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE trust_outbox ADD COLUMN next_attempt_at timestamptz;
ALTER TABLE trust_outbox ADD COLUMN claimed_until timestamptz;
ALTER TABLE trust_outbox ADD COLUMN claim_token uuid;
ALTER TABLE trust_outbox ADD COLUMN last_error_code varchar(64);

UPDATE trust_outbox SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;
ALTER TABLE trust_outbox ALTER COLUMN next_attempt_at SET DEFAULT now();
ALTER TABLE trust_outbox ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX idx_trust_outbox_publish_ready
    ON trust_outbox(next_attempt_at, claimed_until, created_at)
    WHERE published_at IS NULL;
