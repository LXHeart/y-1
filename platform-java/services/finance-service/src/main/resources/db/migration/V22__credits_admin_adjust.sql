-- 草场 finance V22：管理端积分调整安全修复（任务书 #94）。
--
-- credits_transaction.type 扩新值 admin_adjust：管理调账专用，与注册赠送（reward）/退款（refund）
-- 对账口径区分——正向 balance/total_earned 同增，负向只减 balance（earned/spent 均不动）。
-- V19/V20 同款两段式置换，重放安全（IF EXISTS + DROP 再 ADD）。
ALTER TABLE credits_transaction DROP CONSTRAINT IF EXISTS credits_transaction_type_check;
ALTER TABLE credits_transaction DROP CONSTRAINT IF EXISTS ck_credits_transaction_type;
ALTER TABLE credits_transaction ADD CONSTRAINT credits_transaction_type_check
    CHECK (type IN ('purchase', 'reward', 'consume', 'refund', 'judge_reward', 'usage_adjustment', 'admin_adjust')) NOT VALID;
ALTER TABLE credits_transaction VALIDATE CONSTRAINT credits_transaction_type_check;

-- 操作者审计（D94-05）：可空列，不回填、不加索引；仅 admin_adjust 流水写入后台账号 id。
ALTER TABLE credits_transaction ADD COLUMN IF NOT EXISTS operator_account_id uuid;
