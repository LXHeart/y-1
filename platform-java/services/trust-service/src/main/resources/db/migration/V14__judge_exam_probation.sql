-- 任务书 #74 卡 E：准入考试 + 见习审判官 + 挂起/恢复。幂等 DDL（重放测试跑两遍）。

ALTER TABLE judge
    ADD COLUMN IF NOT EXISTS exam_passed_at timestamptz,
    ADD COLUMN IF NOT EXISTS admission_level varchar(16) NOT NULL DEFAULT 'full', -- full / probation
    ADD COLUMN IF NOT EXISTS probation_since timestamptz,
    ADD COLUMN IF NOT EXISTS suspended_until timestamptz,
    ADD COLUMN IF NOT EXISTS suspension_reason varchar(500);

-- 题库（治理台维护；UPDATE 即 version+1，乐观锁）。
CREATE TABLE IF NOT EXISTS judge_exam_question (
    id uuid PRIMARY KEY,
    category varchar(32) NOT NULL,
    question text NOT NULL,
    options jsonb NOT NULL,          -- 字符串数组（≥2 项）
    answer_index int NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_judge_exam_question_options CHECK (jsonb_typeof(options) = 'array'),
    CONSTRAINT chk_judge_exam_question_answer CHECK (answer_index >= 0)
);

-- 考试留痕（出题/交卷各一条 attempt；及格 → judge.exam_passed_at 落值）。
CREATE TABLE IF NOT EXISTS judge_exam_attempt (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    score int NOT NULL,
    passed boolean NOT NULL,
    answers jsonb NOT NULL,          -- [{questionId, choiceIndex, correct}]
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_judge_exam_attempt_account ON judge_exam_attempt(account_id, created_at DESC);

-- 准入审计 action 扩值（考试及格转见习 = probation、见习转正 = promoted、挂起/恢复）。
ALTER TABLE judge_admission_audit DROP CONSTRAINT IF EXISTS judge_admission_audit_action_check;
ALTER TABLE judge_admission_audit
    ADD CONSTRAINT judge_admission_audit_action_check
    CHECK (action IN ('granted', 'revoked', 'probation', 'promoted', 'suspended', 'reinstated'));

-- ============================================================================
-- V8 触发器改造：资格条件 Lv5 → (Lv5 OR (Lv4 AND exam_passed_at))，追加挂起排除。
-- 两处函数（panel_assignment + vote_eligibility）都改；CREATE OR REPLACE 原子，在途案件不受影响。
-- ============================================================================

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

CREATE OR REPLACE FUNCTION enforce_dispute_vote_eligibility() RETURNS trigger AS $$
DECLARE
    target_judge_id uuid;
    target_organization_id uuid;
BEGIN
    SELECT j.id, d.organization_id
    INTO target_judge_id, target_organization_id
    FROM judge j
    JOIN dispute_panel_assignment p
      ON p.dispute_id = NEW.dispute_id
     AND p.round = NEW.round
     AND p.judge_account_id = j.account_id
    JOIN dispute_case d ON d.id = p.dispute_id
    WHERE j.account_id = NEW.judge_account_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'judge is not eligible to vote on this panel';
    END IF;

    PERFORM pg_advisory_xact_lock(judge_conflict_lock_key(target_judge_id, target_organization_id));

    PERFORM 1
    FROM judge j
    JOIN dispute_panel_assignment p
      ON p.dispute_id = NEW.dispute_id
     AND p.round = NEW.round
     AND p.judge_account_id = j.account_id
    JOIN dispute_case d ON d.id = p.dispute_id
    WHERE j.account_id = NEW.judge_account_id
      AND j.active = true
      AND j.ops_admitted = true
      AND d.status = 'voting'
      AND d.round = NEW.round
      AND d.appeal_state <> 'escalated'
      AND (j.eligibility_tier >= 5
           OR (j.eligibility_tier >= 4 AND j.exam_passed_at IS NOT NULL))
      AND (j.suspended_until IS NULL OR j.suspended_until < now())
      AND (j.organization_id IS NULL OR j.organization_id <> d.organization_id)
      AND NOT EXISTS (
          SELECT 1
          FROM judge_conflict conflict
          WHERE conflict.judge_id = j.id
            AND conflict.organization_id = d.organization_id)
    FOR SHARE OF j, p, d;

    IF NOT FOUND THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'judge is not eligible to vote on this panel';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 可抽候选部分索引同步重建含考试通道（挂起条件含 now() 非 IMMUTABLE，不进索引谓词，
-- 由查询与触发器双处把关）。
DROP INDEX IF EXISTS idx_judge_drawable;
CREATE INDEX idx_judge_drawable
    ON judge(active, ops_admitted, eligibility_tier)
    WHERE active = true
      AND ops_admitted = true
      AND (eligibility_tier >= 5 OR (eligibility_tier >= 4 AND exam_passed_at IS NOT NULL));
