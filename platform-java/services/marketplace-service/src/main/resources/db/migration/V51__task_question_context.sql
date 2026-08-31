-- 任务书 #62 卡7：把目标问题带进报名冻结的创作上下文。
--
-- freeze_application_task_context() 是**显式列白名单**（V27 建、V40 加押金），
-- 不是整行序列化——只加 task/task_version 两列不会自动出现在 task_context_snapshot 里，
-- intelligence 侧就永远读不到 questionText，回答模式锁定也就无从触发。
--
-- CREATE OR REPLACE 幂等；触发器本身 V27 已建，不重建（重放安全）。
-- 存量已 accepted 的报名不回填：task_context_snapshot 是不可变冻结快照，
-- 且这些任务发布时本就没有目标问题（新列默认 NULL），回填等于伪造历史。
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
        )
        -- 任务书 #62：目标问题取**冻结版本**（tv）优先，回落 task 当前值。修订会改 task，
        -- 但 accept 冻的是报名时的版本，以 tv 为准才与 requirements 同口径。
        --
        -- 用 `||` 条件拼接而非直接写进 jsonb_build_object：后者会给**每一个**快照塞
        -- 'questionText': null，改变全部存量任务（含公众号/小红书）的快照形状；
        -- 条件拼接下无目标问题的任务快照逐字节不变（零回归红线）。
        || CASE
               WHEN COALESCE(tv.question_text, t.question_text) IS NULL THEN '{}'::jsonb
               ELSE jsonb_strip_nulls(jsonb_build_object(
                   'questionText', COALESCE(tv.question_text, t.question_text),
                   'questionRef', COALESCE(tv.question_ref, t.question_ref)))
           END
        INTO NEW.task_context_snapshot
        FROM task t
        LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
        WHERE t.id = NEW.task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
