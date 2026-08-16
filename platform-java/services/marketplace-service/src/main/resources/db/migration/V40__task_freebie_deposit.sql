-- 草场 marketplace V40：霸王餐押金字段（任务书 #22 / ADR-D12）。
--
-- task.freebie_deposit_cents 与 bounty_cents 并列（正交资金字段，v1 XOR：同时 >0 由 CHECK + 契约层双拦）。
-- 资金方向与 bounty 相反：押金由推荐官钱包预付（finance freebie_escrow），故 task_application 冻结
-- 押金快照（镜像 V14 bounty 快照 + D7 pinning），V27 触发器重建把 freebieDepositCents 纳入 task_context。
-- 存量行新列全 NULL/0 → CHECK 直接 VALIDATE 安全；DDL 全部 IF NOT EXISTS/CREATE OR REPLACE（重放安全）。

ALTER TABLE task ADD COLUMN IF NOT EXISTS freebie_deposit_cents bigint CHECK (freebie_deposit_cents >= 0);

ALTER TABLE task_version ADD COLUMN IF NOT EXISTS freebie_deposit_cents bigint;

ALTER TABLE task_application ADD COLUMN IF NOT EXISTS freebie_deposit_cents bigint NOT NULL DEFAULT 0;

-- XOR：押金与赏金不可同设（v1 单模式；组合模式为后续 backlog，见 ADR-D12 D1）。
ALTER TABLE task DROP CONSTRAINT IF EXISTS chk_task_funding_xor;
ALTER TABLE task ADD CONSTRAINT chk_task_funding_xor
    CHECK (NOT (COALESCE(bounty_cents, 0) > 0 AND COALESCE(freebie_deposit_cents, 0) > 0));

-- V27 触发器重建：accept 时冻结的 task_context 快照带上 freebieDepositCents（D7）。
CREATE OR REPLACE FUNCTION freeze_application_task_context() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'accepted' AND OLD.status <> 'accepted' AND NEW.task_context_snapshot IS NULL THEN
        SELECT jsonb_build_object(
            'taskId', t.id, 'taskVersion', t.version, 'title', t.title,
            'description', t.description, 'contentForm', t.content_form,
            'platform', t.platform, 'storeId', t.store_id,
            'applicationId', NEW.id, 'recommenderAccountId', NEW.recommender_account_id,
            'bountyCents', NEW.bounty_cents,
            'freebieDepositCents', COALESCE(NEW.freebie_deposit_cents, 0),
            'acceptedAt', COALESCE(NEW.decided_at, now()),
            'requirements', COALESCE(tv.requirements, '{}'::jsonb)
        ) INTO NEW.task_context_snapshot
        FROM task t
        LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
        WHERE t.id = NEW.task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
