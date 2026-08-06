-- Fence AI credit consume and compensation on one operation row.
-- This closes the response-loss race where an unconditional refund could mint a credit
-- before the original consume commits, or a late consume could arrive after compensation.

CREATE TABLE credits_consume_operation (
    operation_id              text PRIMARY KEY,
    account_id                uuid NOT NULL,
    feature                   text NOT NULL,
    state                     varchar(16) NOT NULL CHECK (state IN ('open', 'consumed', 'compensated')),
    consume_transaction_id    uuid UNIQUE REFERENCES credits_transaction(id),
    refund_transaction_id     uuid UNIQUE REFERENCES credits_transaction(id),
    consume_balance_after     integer,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_credits_consume_operation_state CHECK (
        (state = 'open'
            AND consume_transaction_id IS NULL
            AND refund_transaction_id IS NULL
            AND consume_balance_after IS NULL)
        OR
        (state = 'consumed'
            AND consume_transaction_id IS NOT NULL
            AND refund_transaction_id IS NULL
            AND consume_balance_after IS NOT NULL)
        OR
        (state = 'compensated' AND (
            (consume_transaction_id IS NULL
                AND refund_transaction_id IS NULL
                AND consume_balance_after IS NULL)
            OR
            (consume_transaction_id IS NOT NULL
                AND refund_transaction_id IS NOT NULL
                AND consume_balance_after IS NOT NULL)
        ))
    )
);

-- Preserve idempotency for operations created before the fence existed.
INSERT INTO credits_consume_operation(
    operation_id, account_id, feature, state,
    consume_transaction_id, refund_transaction_id, consume_balance_after,
    created_at, updated_at
)
SELECT consume.operation_id,
       consume.account_id,
       COALESCE(consume.feature, 'unknown'),
       CASE WHEN refund.id IS NULL THEN 'consumed' ELSE 'compensated' END,
       consume.id,
       refund.id,
       consume.balance_after,
       consume.created_at,
       COALESCE(refund.created_at, consume.created_at)
FROM credits_transaction consume
LEFT JOIN credits_transaction refund
       ON refund.operation_id = 'refund:' || consume.operation_id
      AND refund.type = 'refund'
WHERE consume.type = 'consume'
  AND consume.operation_id IS NOT NULL
ON CONFLICT (operation_id) DO NOTHING;

-- Rolling upgrades may keep old finance instances alive after this migration. Those instances
-- insert only credits_transaction rows and do not know about credits_consume_operation. Mirror
-- every such consume into the fence at the database boundary. If compensation won first, raising
-- here aborts the old transaction and rolls back its preceding balance decrement.
CREATE FUNCTION fence_legacy_credit_consume()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_state varchar(16);
BEGIN
    IF NEW.type <> 'consume' OR NEW.operation_id IS NULL THEN
        RETURN NEW;
    END IF;

    INSERT INTO credits_consume_operation(
        operation_id, account_id, feature, state,
        consume_transaction_id, consume_balance_after,
        created_at, updated_at
    )
    VALUES (
        NEW.operation_id, NEW.account_id, COALESCE(NEW.feature, 'unknown'), 'consumed',
        NEW.id, NEW.balance_after, NEW.created_at, NEW.created_at
    )
    ON CONFLICT (operation_id) DO NOTHING;

    SELECT state
    INTO current_state
    FROM credits_consume_operation
    WHERE operation_id = NEW.operation_id
    FOR UPDATE;

    IF current_state = 'compensated' THEN
        RAISE EXCEPTION 'credit consume operation % was already compensated', NEW.operation_id
            USING ERRCODE = 'P0001';
    END IF;

    -- state=open belongs to the new finance writer, which updates it to consumed after this insert.
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_fence_legacy_credit_consume
AFTER INSERT ON credits_transaction
FOR EACH ROW
WHEN (NEW.type = 'consume' AND NEW.operation_id IS NOT NULL)
EXECUTE FUNCTION fence_legacy_credit_consume();

-- Reconcile once more after the trigger is installed. This catches any old-writer transaction
-- that committed after the initial backfill snapshot but before CREATE TRIGGER acquired its lock.
INSERT INTO credits_consume_operation(
    operation_id, account_id, feature, state,
    consume_transaction_id, refund_transaction_id, consume_balance_after,
    created_at, updated_at
)
SELECT consume.operation_id,
       consume.account_id,
       COALESCE(consume.feature, 'unknown'),
       CASE WHEN refund.id IS NULL THEN 'consumed' ELSE 'compensated' END,
       consume.id,
       refund.id,
       consume.balance_after,
       consume.created_at,
       COALESCE(refund.created_at, consume.created_at)
FROM credits_transaction consume
LEFT JOIN credits_transaction refund
       ON refund.operation_id = 'refund:' || consume.operation_id
      AND refund.type = 'refund'
WHERE consume.type = 'consume'
  AND consume.operation_id IS NOT NULL
ON CONFLICT (operation_id) DO NOTHING;
