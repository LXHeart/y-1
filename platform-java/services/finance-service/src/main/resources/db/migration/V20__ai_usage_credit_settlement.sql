-- Finance-authoritative credits<->cents usage settlement for priced AI runs.
-- Existing flat one-credit calls remain usage_priced=false and retain their V8/V10 lifecycle.

ALTER TABLE credits_transaction DROP CONSTRAINT IF EXISTS credits_transaction_type_check;
ALTER TABLE credits_transaction ADD CONSTRAINT credits_transaction_type_check
    CHECK (type IN ('purchase', 'reward', 'consume', 'refund', 'judge_reward', 'usage_adjustment')) NOT VALID;
ALTER TABLE credits_transaction VALIDATE CONSTRAINT credits_transaction_type_check;

ALTER TABLE credits_consume_operation
    DROP CONSTRAINT IF EXISTS credits_consume_operation_state_check,
    DROP CONSTRAINT IF EXISTS chk_credits_consume_operation_state,
    ADD COLUMN usage_priced boolean NOT NULL DEFAULT false,
    ADD COLUMN credits_cents_policy_version varchar(64),
    ADD COLUMN credits_cents_rounding varchar(16),
    ADD COLUMN cents_numerator bigint,
    ADD COLUMN credits_denominator bigint,
    ADD COLUMN max_cents_per_operation bigint,
    ADD COLUMN reserved_cents bigint,
    ADD COLUMN reserved_credits integer,
    ADD COLUMN actual_cents bigint,
    ADD COLUMN actual_credits integer,
    ADD COLUMN adjustment_credits integer,
    ADD COLUMN settlement_transaction_id uuid UNIQUE REFERENCES credits_transaction(id),
    ADD COLUMN settled_at timestamptz;

ALTER TABLE credits_consume_operation
    ADD CONSTRAINT credits_consume_operation_state_check
        CHECK (state IN ('open', 'consumed', 'settled', 'compensated')),
    ADD CONSTRAINT chk_credits_consume_operation_usage_policy CHECK (
        (NOT usage_priced
            AND credits_cents_policy_version IS NULL
            AND credits_cents_rounding IS NULL
            AND cents_numerator IS NULL
            AND credits_denominator IS NULL
            AND max_cents_per_operation IS NULL
            AND reserved_cents IS NULL
            AND reserved_credits IS NULL
            AND actual_cents IS NULL
            AND actual_credits IS NULL
            AND adjustment_credits IS NULL
            AND settlement_transaction_id IS NULL
            AND settled_at IS NULL)
        OR
        (usage_priced
            AND credits_cents_policy_version IS NOT NULL
            AND credits_cents_rounding IN ('HALF_UP', 'HALF_EVEN', 'DOWN', 'UP')
            AND cents_numerator > 0
            AND credits_denominator > 0
            AND max_cents_per_operation > 0
            AND reserved_cents BETWEEN 0 AND max_cents_per_operation
            AND reserved_credits >= 0
            AND (
                (state <> 'settled'
                    AND actual_cents IS NULL
                    AND actual_credits IS NULL
                    AND adjustment_credits IS NULL
                    AND settlement_transaction_id IS NULL
                    AND settled_at IS NULL)
                OR
                (state = 'settled'
                    AND actual_cents BETWEEN 0 AND max_cents_per_operation
                    AND actual_credits >= 0
                    AND adjustment_credits IS NOT NULL
                    AND settled_at IS NOT NULL)
            ))
    ),
    ADD CONSTRAINT chk_credits_consume_operation_lifecycle CHECK (
        (state = 'open'
            AND consume_transaction_id IS NULL
            AND refund_transaction_id IS NULL
            AND consume_balance_after IS NULL
            AND quota_consume_transaction_id IS NULL
            AND quota_refund_transaction_id IS NULL
            AND charge_source IS NULL)
        OR
        (state IN ('consumed', 'settled')
            AND refund_transaction_id IS NULL
            AND quota_refund_transaction_id IS NULL
            AND (
                (charge_source = 'paid'
                    AND consume_transaction_id IS NOT NULL
                    AND consume_balance_after IS NOT NULL
                    AND quota_consume_transaction_id IS NULL)
                OR
                (charge_source = 'quota'
                    AND consume_transaction_id IS NULL
                    AND consume_balance_after IS NOT NULL
                    AND quota_consume_transaction_id IS NOT NULL
                    AND quota_day IS NOT NULL
                    AND quota_limit IS NOT NULL
                    AND policy_version IS NOT NULL
                    AND ai_quota_multiplier_bps IS NOT NULL)
            )
            AND (state <> 'settled' OR usage_priced))
        OR
        (state = 'compensated' AND (
            (consume_transaction_id IS NULL
                AND refund_transaction_id IS NULL
                AND consume_balance_after IS NULL
                AND quota_consume_transaction_id IS NULL
                AND quota_refund_transaction_id IS NULL
                AND charge_source IS NULL)
            OR
            (charge_source = 'paid'
                AND consume_transaction_id IS NOT NULL
                AND refund_transaction_id IS NOT NULL
                AND consume_balance_after IS NOT NULL
                AND quota_consume_transaction_id IS NULL
                AND quota_refund_transaction_id IS NULL)
            OR
            (charge_source = 'quota'
                AND consume_transaction_id IS NULL
                AND refund_transaction_id IS NULL
                AND consume_balance_after IS NOT NULL
                AND quota_consume_transaction_id IS NOT NULL
                AND quota_refund_transaction_id IS NOT NULL)
        ))
    );

CREATE INDEX idx_credits_usage_settlement_policy
    ON credits_consume_operation(credits_cents_policy_version, settled_at)
    WHERE usage_priced;
