-- P2 recommendation AI benefit: atomic per-account daily free quota and append-only audit flow.
-- quota_day is computed by finance from the configured business timezone; callers cannot choose it.

CREATE TABLE credits_daily_quota_usage (
    account_id uuid NOT NULL,
    quota_day date NOT NULL,
    used integer NOT NULL DEFAULT 0 CHECK (used >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, quota_day)
);

CREATE TABLE credits_quota_transaction (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL,
    quota_day date NOT NULL,
    delta_used smallint NOT NULL CHECK (delta_used IN (-1, 1)),
    used_after integer NOT NULL CHECK (used_after >= 0),
    quota_limit integer NOT NULL CHECK (quota_limit >= 0),
    type varchar(16) NOT NULL CHECK (type IN ('consume', 'refund')),
    source varchar(16) NOT NULL DEFAULT 'quota' CHECK (source = 'quota'),
    feature varchar(64) NOT NULL,
    -- Refund compensation derives "refund:" + the accepted 256-char consume key.
    -- Keep this aligned with credits_transaction/credits_consume_operation (text).
    operation_id text NOT NULL UNIQUE,
    policy_version bigint NOT NULL CHECK (policy_version >= 1),
    ai_quota_multiplier_bps integer NOT NULL
        CHECK (ai_quota_multiplier_bps BETWEEN 1000 AND 100000),
    note varchar(512),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_credits_quota_transaction_type_delta CHECK (
        (type = 'consume' AND delta_used = 1)
        OR (type = 'refund' AND delta_used = -1)
    )
);

CREATE INDEX idx_credits_quota_transaction_account_day
    ON credits_quota_transaction(account_id, quota_day, created_at DESC);

-- The existing consume fence remains the single serialization point for paid and quota charges.
ALTER TABLE credits_consume_operation
    DROP CONSTRAINT chk_credits_consume_operation_state,
    ADD COLUMN charge_source varchar(16) CHECK (charge_source IN ('quota', 'paid')),
    ADD COLUMN quota_day date,
    ADD COLUMN quota_limit integer CHECK (quota_limit IS NULL OR quota_limit >= 0),
    ADD COLUMN policy_version bigint CHECK (policy_version IS NULL OR policy_version >= 1),
    ADD COLUMN ai_quota_multiplier_bps integer
        CHECK (ai_quota_multiplier_bps IS NULL OR ai_quota_multiplier_bps BETWEEN 1000 AND 100000),
    ADD COLUMN quota_consume_transaction_id uuid UNIQUE REFERENCES credits_quota_transaction(id),
    ADD COLUMN quota_refund_transaction_id uuid UNIQUE REFERENCES credits_quota_transaction(id);

-- Existing fenced operations predate quota and are necessarily paid charges.
UPDATE credits_consume_operation
SET charge_source = CASE WHEN consume_transaction_id IS NULL THEN NULL ELSE 'paid' END;

-- Keep the V8 rolling-upgrade trigger compatible with the new source invariant. Old finance
-- instances still write only credits_transaction; the database classifies those charges as paid.
CREATE OR REPLACE FUNCTION fence_legacy_credit_consume()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_state varchar(16);
    current_source varchar(16);
    current_consume_transaction_id uuid;
BEGIN
    IF NEW.type <> 'consume' OR NEW.operation_id IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO credits_consume_operation(
        operation_id, account_id, feature, state,
        consume_transaction_id, consume_balance_after, charge_source,
        created_at, updated_at
    )
    VALUES (
        NEW.operation_id, NEW.account_id, COALESCE(NEW.feature, 'unknown'), 'consumed',
        NEW.id, NEW.balance_after, 'paid', NEW.created_at, NEW.created_at
    )
    ON CONFLICT (operation_id) DO NOTHING;

    SELECT state, charge_source, consume_transaction_id
    INTO current_state, current_source, current_consume_transaction_id
    FROM credits_consume_operation
    WHERE operation_id = NEW.operation_id
    FOR UPDATE;

    IF current_state = 'open' THEN
        UPDATE credits_consume_operation
        SET state = 'consumed',
            charge_source = 'paid',
            consume_transaction_id = NEW.id,
            consume_balance_after = NEW.balance_after,
            updated_at = now()
        WHERE operation_id = NEW.operation_id;
        RETURN NEW;
    END IF;

    -- The insert above owns a newly-created fence. A committed quota/compensation or a paid
    -- transaction with another id means a concurrent writer already owns this operation.
    IF current_state <> 'consumed'
            OR current_source IS DISTINCT FROM 'paid'
            OR current_consume_transaction_id IS DISTINCT FROM NEW.id THEN
        RAISE EXCEPTION 'credit consume operation % is already owned', NEW.operation_id
            USING ERRCODE = 'P0001';
    END IF;

    RETURN NEW;
END;
$$;

ALTER TABLE credits_consume_operation
    ADD CONSTRAINT chk_credits_consume_operation_state CHECK (
        (state = 'open'
            AND consume_transaction_id IS NULL
            AND refund_transaction_id IS NULL
            AND consume_balance_after IS NULL
            AND quota_consume_transaction_id IS NULL
            AND quota_refund_transaction_id IS NULL
            AND charge_source IS NULL)
        OR
        (state = 'consumed' AND refund_transaction_id IS NULL
            AND quota_refund_transaction_id IS NULL AND (
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
            ))
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

CREATE FUNCTION reject_credits_quota_transaction_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'credits quota transaction is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_credits_quota_transaction_immutable
    BEFORE UPDATE OR DELETE ON credits_quota_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_credits_quota_transaction_mutation();
