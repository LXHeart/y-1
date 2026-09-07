-- 任务书 #90 C90-02：报名条款快照 / 关键修订重确认 / 取消终态化。
-- §7 同批登记 C90-05 结算契约列（settlement_status / settlement_eligible_at），消费在后卡。

ALTER TABLE task_application ADD COLUMN IF NOT EXISTS task_version_at_apply integer;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS terms_snapshot_json jsonb;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS reconsent_required boolean NOT NULL DEFAULT false;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS cancelled_at timestamptz;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS settlement_status varchar;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS settlement_eligible_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_task_application_task_status ON task_application(task_id, status);

-- 报名时冻结任务版本与关键条款摘要（D90-07）。DB trigger 覆盖所有创建路径（V27 task_context_snapshot 先例），
-- Java 侧任何 INSERT 都不可能漏存快照。条款摘要取 task_version 同版本快照的 requirements。
-- UPDATE 分支维护三个簿记字段，状态迁移与簿记原子绑定：
--   pending → reconsent：reconsent_required=true（关键修订触发，D90-06/D90-07）；
--   reconsent → pending：reconsent_required=false 并把快照刷新到当前版本（重新确认 = 认可现行条款）；
--   * → cancelled：cancelled_at=now()（取消终态化时间戳）。
CREATE OR REPLACE FUNCTION freeze_application_terms() RETURNS trigger AS $$
DECLARE
    t record;
    req jsonb;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT version, bounty_cents, COALESCE(freebie_deposit_cents, 0) AS deposit, platform, content_form
          INTO t FROM task WHERE id = NEW.task_id;
        SELECT COALESCE(requirements, '{}'::jsonb) INTO req
          FROM task_version WHERE task_id = NEW.task_id AND version = t.version;
        NEW.task_version_at_apply := t.version;
        NEW.terms_snapshot_json := jsonb_build_object(
            'taskVersion', t.version, 'bountyCents', t.bounty_cents,
            'freebieDepositCents', t.deposit, 'platform', t.platform,
            'contentForm', t.content_form, 'requirements', req);
    ELSE
        IF NEW.status = 'reconsent' AND OLD.status <> 'reconsent' THEN
            NEW.reconsent_required := true;
        END IF;
        IF NEW.status = 'pending' AND OLD.status = 'reconsent' THEN
            NEW.reconsent_required := false;
            SELECT version, bounty_cents, COALESCE(freebie_deposit_cents, 0) AS deposit, platform, content_form
              INTO t FROM task WHERE id = NEW.task_id;
            SELECT COALESCE(requirements, '{}'::jsonb) INTO req
              FROM task_version WHERE task_id = NEW.task_id AND version = t.version;
            NEW.task_version_at_apply := t.version;
            NEW.terms_snapshot_json := jsonb_build_object(
                'taskVersion', t.version, 'bountyCents', t.bounty_cents,
                'freebieDepositCents', t.deposit, 'platform', t.platform,
                'contentForm', t.content_form, 'requirements', req);
        END IF;
        IF NEW.status = 'cancelled' AND OLD.status <> 'cancelled' THEN
            NEW.cancelled_at := now();
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_application_terms ON task_application;
CREATE TRIGGER trg_task_application_terms
BEFORE INSERT OR UPDATE ON task_application
FOR EACH ROW EXECUTE FUNCTION freeze_application_terms();
