-- 审查修复 02 / R04（C02-A）：面板插入的数据库纵深——当事人回避。
-- 原告 opened_by_account_id 与被告 respondent_account_id 不得担任本案审判官（任一轮）。
-- 与 V14 资格触发器同构：CREATE OR REPLACE 原子替换，在途案件不受影响；幂等可重放。
-- 服务层（DisputeConflictContextResolver + drawVerifiedPanel 排除集 + insertPanelMember SQL）
-- 与本触发器双保险；直插冲突席位被数据库拒绝。

CREATE OR REPLACE FUNCTION enforce_panel_assignment_eligibility() RETURNS trigger AS $$
DECLARE
    target_judge_id uuid;
    target_organization_id uuid;
BEGIN
    SELECT j.id, d.organization_id
    INTO target_judge_id, target_organization_id
    FROM judge j
    JOIN dispute_case d ON d.id = NEW.dispute_id
    WHERE j.account_id = NEW.judge_account_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'judge is not eligible for panel assignment';
    END IF;

    PERFORM pg_advisory_xact_lock(judge_conflict_lock_key(target_judge_id, target_organization_id));

    PERFORM 1
    FROM judge j
    JOIN dispute_case d ON d.id = NEW.dispute_id
    WHERE j.account_id = NEW.judge_account_id
      AND j.active = true
      AND j.ops_admitted = true
      AND (j.eligibility_tier >= 5
           OR (j.eligibility_tier >= 4 AND j.exam_passed_at IS NOT NULL))
      AND (j.suspended_until IS NULL OR j.suspended_until < now())
      AND (j.organization_id IS NULL OR j.organization_id <> d.organization_id)
      -- 审查修复 02 / R04：当事人回避（原告 + 被告；被告为 NULL 时由服务层先按履约授权补解析回填，
      -- 数据库只对已知账号执行不等式，不把 NULL 当作「无冲突」放行新插入）。
      AND j.account_id <> d.opened_by_account_id
      AND (d.respondent_account_id IS NULL OR j.account_id <> d.respondent_account_id)
      AND NOT EXISTS (
          SELECT 1
          FROM judge_conflict conflict
          WHERE conflict.judge_id = j.id
            AND conflict.organization_id = d.organization_id)
    FOR SHARE OF j, d;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'judge is not eligible for panel assignment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
