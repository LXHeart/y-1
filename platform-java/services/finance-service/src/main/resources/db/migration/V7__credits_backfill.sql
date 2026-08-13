-- 草场 finance-service V7：积分存量平迁（GL-P3-AI-001 下属切片）。
--
-- V6 建好 credits_account / credits_transaction 后，把 legacy user_credits / credit_transactions 的存量
-- 原样搬进 finance 新表（全平台共用同一 public schema，故同库 INSERT…SELECT，无跨库管道）。
-- 幂等：ON CONFLICT DO NOTHING，重跑安全（回滚后重同步也走它）。
--
-- ⚠️ 源表存在性守卫：finance 的 testcontainer / 全新独立库**没有** legacy 两表（只有 prod 共享 schema 才有），
-- 故用 to_regclass 守卫——源表不存在时整段跳过（no-op），不阻断 Flyway。prod 共享 schema 下两者皆存在 → 正常搬运。
-- 历史无 operation_id 的行（NULL）照搬，被 partial unique index 排除在外，不冲突。

DO $$
BEGIN
    IF to_regclass('public.user_credits') IS NOT NULL
       AND to_regclass('public.credit_transactions') IS NOT NULL THEN

        INSERT INTO credits_account (account_id, balance, total_earned, total_spent, created_at, updated_at)
        SELECT legacy.user_id, legacy.balance, legacy.total_earned, legacy.total_spent,
               legacy.created_at, legacy.updated_at
        FROM user_credits AS legacy
        ON CONFLICT (account_id) DO NOTHING;

        IF EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'credit_transactions'
              AND column_name = 'operation_id'
        ) THEN
            INSERT INTO credits_transaction
                (id, account_id, amount, balance_after, type, feature, note, operation_id, created_at)
            SELECT legacy.id, legacy.user_id, legacy.amount, legacy.balance_after, legacy.type,
                   legacy.feature, legacy.note, legacy.operation_id, legacy.created_at
            FROM credit_transactions AS legacy
            ON CONFLICT (id) DO NOTHING;
        ELSE
            INSERT INTO credits_transaction
                (id, account_id, amount, balance_after, type, feature, note, operation_id, created_at)
            SELECT legacy.id, legacy.user_id, legacy.amount, legacy.balance_after, legacy.type,
                   legacy.feature, legacy.note, NULL::text, legacy.created_at
            FROM credit_transactions AS legacy
            ON CONFLICT (id) DO NOTHING;
        END IF;

    END IF;
END $$;
