-- JBE-07: retire the Node credit tables after V7 copied their authoritative data.
-- Fail before DROP if any source row is missing from the Java-owned tables.

DO $$
DECLARE
    missing_accounts bigint;
    missing_transactions bigint;
BEGIN
    IF to_regclass('public.user_credits') IS NOT NULL THEN
        SELECT count(*) INTO missing_accounts
        FROM user_credits legacy_account
        LEFT JOIN credits_account current_account
          ON current_account.account_id = legacy_account.user_id
        WHERE current_account.account_id IS NULL;

        IF missing_accounts > 0 THEN
            RAISE EXCEPTION
                'cannot retire user_credits: % account rows are missing from credits_account',
                missing_accounts;
        END IF;
    END IF;

    IF to_regclass('public.credit_transactions') IS NOT NULL THEN
        SELECT count(*) INTO missing_transactions
        FROM credit_transactions legacy_transaction
        LEFT JOIN credits_transaction current_transaction
          ON current_transaction.id = legacy_transaction.id
        WHERE current_transaction.id IS NULL;

        IF missing_transactions > 0 THEN
            RAISE EXCEPTION
                'cannot retire credit_transactions: % rows are missing from credits_transaction',
                missing_transactions;
        END IF;
    END IF;
END $$;

DROP TABLE IF EXISTS credit_transactions;
DROP TABLE IF EXISTS user_credits;
