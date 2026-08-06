-- Durable credit compensation intent for failed platform AI runs.

CREATE TABLE ai_credit_compensation (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                uuid NOT NULL UNIQUE REFERENCES ai_run(id) ON DELETE RESTRICT,
    consume_operation_id  uuid NOT NULL UNIQUE,
    account_id            text NOT NULL,
    feature               varchar(64) NOT NULL,
    reason                varchar(512) NOT NULL,
    status                varchar(16) NOT NULL DEFAULT 'pending'
                              CHECK (status IN ('pending', 'completed', 'failed')),
    attempt_count         int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at       timestamptz NOT NULL DEFAULT now(),
    claim_token           uuid,
    claimed_until         timestamptz,
    last_error_code       varchar(64),
    completed_at          timestamptz,
    failed_at             timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ai_credit_compensation_claim CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    CONSTRAINT chk_ai_credit_compensation_lifecycle CHECK (
        (status = 'pending' AND completed_at IS NULL AND failed_at IS NULL)
        OR (status = 'completed' AND completed_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'failed' AND completed_at IS NULL AND failed_at IS NOT NULL)
    )
);

CREATE INDEX idx_ai_credit_compensation_pending
    ON ai_credit_compensation(next_attempt_at, claimed_until, created_at)
    WHERE status = 'pending';
