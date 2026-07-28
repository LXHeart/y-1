-- Slice 7A: baseline-compatible reliability lease for both fresh databases and
-- legacy databases where the minimal outbox table was created at runtime.
CREATE TABLE IF NOT EXISTS outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id text NOT NULL UNIQUE,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload json NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    last_error_code varchar(64)
);

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS next_attempt_at timestamptz;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS claimed_until timestamptz;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS claim_token uuid;
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS last_error_code varchar(64);
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS published_at timestamptz;

UPDATE outbox SET next_attempt_at = created_at WHERE next_attempt_at IS NULL;
ALTER TABLE outbox ALTER COLUMN next_attempt_at SET DEFAULT now();
ALTER TABLE outbox ALTER COLUMN next_attempt_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_identity_outbox_publish_ready
    ON outbox(next_attempt_at, claimed_until, created_at)
    WHERE published_at IS NULL;
