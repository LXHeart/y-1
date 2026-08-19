-- Durable Finance settlement intent for completed priced platform AI runs.

ALTER TABLE ai_run
    ADD COLUMN credits_cents_policy_version varchar(64);

CREATE TABLE ai_credit_usage_settlement (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                uuid NOT NULL UNIQUE REFERENCES ai_run(id) ON DELETE CASCADE,
    consume_operation_id  uuid NOT NULL UNIQUE,
    account_id            text NOT NULL,
    feature               varchar(64) NOT NULL,
    credits_cents_policy_version varchar(64) NOT NULL,
    actual_cents          integer NOT NULL CHECK (actual_cents >= 0),
    status                varchar(16) NOT NULL DEFAULT 'pending'
                              CHECK (status IN ('pending', 'completed', 'failed')),
    attempt_count         integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at       timestamptz NOT NULL DEFAULT now(),
    claim_token           uuid,
    claimed_until         timestamptz,
    last_error_code       varchar(64),
    charge_source         varchar(16) CHECK (charge_source IN ('paid', 'quota')),
    reserved_cents        bigint,
    reserved_credits      integer,
    actual_credits        integer,
    adjustment_credits    integer,
    completed_at          timestamptz,
    failed_at             timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_credit_usage_settlement_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    CONSTRAINT chk_ai_credit_usage_settlement_lifecycle CHECK (
        (status = 'pending' AND completed_at IS NULL AND failed_at IS NULL)
        OR (status = 'completed' AND completed_at IS NOT NULL AND failed_at IS NULL
            AND charge_source IS NOT NULL AND reserved_cents IS NOT NULL
            AND reserved_credits IS NOT NULL AND actual_credits IS NOT NULL
            AND adjustment_credits IS NOT NULL)
        OR (status = 'failed' AND completed_at IS NULL AND failed_at IS NOT NULL)
    )
);

CREATE INDEX idx_ai_credit_usage_settlement_pending
    ON ai_credit_usage_settlement(next_attempt_at, claimed_until, created_at)
    WHERE status = 'pending';
