-- 本人身份审计按时间倒序 keyset 分页；id 作为同一时间戳下的稳定 tie-breaker。
-- 条件判断限定 current_schema：仓库里的历史迁移夹具会从 V9/V12 baseline 构造最小旧表，
-- 不能误解析到 public schema 的同名表，也不能假设夹具占位表已经有 V5 的全部列。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'identity_audit_log'
          AND column_name = 'account_id'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'identity_audit_log'
          AND column_name = 'occurred_at'
    ) THEN
        CREATE INDEX IF NOT EXISTS idx_identity_audit_account_time_id
            ON identity_audit_log(account_id, occurred_at DESC, id DESC);
    END IF;
END $$;
