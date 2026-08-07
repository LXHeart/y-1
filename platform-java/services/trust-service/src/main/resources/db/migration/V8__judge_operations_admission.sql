-- GL-P2-TRUST-001：Lv5 审判官报名与平台运营准入分离。
ALTER TABLE judge
    ADD COLUMN ops_admitted boolean NOT NULL DEFAULT false,
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD COLUMN ops_admitted_at timestamptz,
    ADD COLUMN ops_admitted_by uuid;

ALTER TABLE judge
    ADD CONSTRAINT chk_judge_version_nonnegative CHECK (version >= 0),
    ADD CONSTRAINT chk_judge_admission_metadata CHECK (
        (ops_admitted = true AND ops_admitted_at IS NOT NULL AND ops_admitted_by IS NOT NULL)
        OR (ops_admitted = false AND ops_admitted_at IS NULL AND ops_admitted_by IS NULL));

DROP INDEX idx_judge_active_tier;
CREATE INDEX idx_judge_drawable
    ON judge(active, ops_admitted, eligibility_tier)
    WHERE active = true AND ops_admitted = true;

CREATE TABLE judge_admission_audit (
    id bigserial PRIMARY KEY,
    judge_id uuid NOT NULL REFERENCES judge(id),
    action varchar(16) NOT NULL CHECK (action IN ('granted', 'revoked')),
    actor_account_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    previous_version bigint NOT NULL,
    new_version bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (previous_version >= 0 AND new_version = previous_version + 1)
);
CREATE INDEX idx_judge_admission_audit_judge ON judge_admission_audit(judge_id, id);

-- 仅 grandfather 已在进行中案件“当前轮”面板上的活跃审判官，避免 V8 上线使在途案件突然无人可投票。
-- 其他历史审判官仍维持 ops_admitted=false，必须经过平台运营准入。
WITH grandfathered AS (
    UPDATE judge j
    SET ops_admitted = true,
        ops_admitted_at = now(),
        ops_admitted_by = '00000000-0000-0000-0000-000000000000'::uuid,
        version = version + 1
    WHERE j.active = true
      AND j.ops_admitted = false
      AND EXISTS (
          SELECT 1
          FROM dispute_panel_assignment p
          JOIN dispute_case d ON d.id = p.dispute_id
          WHERE p.judge_account_id = j.account_id
            AND d.status = 'voting'
            AND p.round = d.round)
    RETURNING j.id, j.version
)
INSERT INTO judge_admission_audit(
    judge_id, action, actor_account_id, reason, previous_version, new_version)
SELECT id,
       'granted',
       '00000000-0000-0000-0000-000000000000'::uuid,
       'migration_v8_grandfather_inflight_panel',
       version - 1,
       version
FROM grandfathered;

-- 面板分配/投票与 judge_conflict 写入必须在同一 judge+organization scope 上线性化。
-- advisory lock 后的资格查询是独立语句，在 READ COMMITTED 下会看到先获锁事务已提交的最新冲突。
CREATE FUNCTION judge_conflict_lock_key(target_judge_id uuid, target_organization_id uuid) RETURNS bigint AS $$
    SELECT hashtextextended(target_judge_id::text || ':' || target_organization_id::text, 0);
$$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE;

CREATE FUNCTION serialize_judge_conflict_write() RETURNS trigger AS $$
DECLARE
    old_key bigint;
    new_key bigint;
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM pg_advisory_xact_lock(judge_conflict_lock_key(NEW.judge_id, NEW.organization_id));
    ELSIF TG_OP = 'DELETE' THEN
        PERFORM pg_advisory_xact_lock(judge_conflict_lock_key(OLD.judge_id, OLD.organization_id));
    ELSE
        old_key := judge_conflict_lock_key(OLD.judge_id, OLD.organization_id);
        new_key := judge_conflict_lock_key(NEW.judge_id, NEW.organization_id);
        IF old_key <= new_key THEN
            PERFORM pg_advisory_xact_lock(old_key);
            PERFORM pg_advisory_xact_lock(new_key);
        ELSE
            PERFORM pg_advisory_xact_lock(new_key);
            PERFORM pg_advisory_xact_lock(old_key);
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_judge_conflict_serialize
    BEFORE INSERT OR UPDATE OR DELETE ON judge_conflict
    FOR EACH ROW EXECUTE FUNCTION serialize_judge_conflict_write();

-- 滚动升级期间旧实例仍可能使用 V3 的直接 INSERT。数据库边界复验最终写入资格，
-- 避免应用版本差异形成未准入、利益冲突或低等级的面板成员。
CREATE FUNCTION enforce_panel_assignment_eligibility() RETURNS trigger AS $$
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
      AND j.eligibility_tier >= 5
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

CREATE TRIGGER trg_panel_assignment_eligibility
    BEFORE INSERT ON dispute_panel_assignment
    FOR EACH ROW EXECUTE FUNCTION enforce_panel_assignment_eligibility();

-- 投票必须来自仍处于 active + admitted 状态的本轮面板成员。FOR SHARE 与准入撤销串行化，
-- 防止撤销和旧实例投票在两个事务中交错后绕过检查。
CREATE FUNCTION enforce_dispute_vote_eligibility() RETURNS trigger AS $$
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

CREATE TRIGGER trg_dispute_vote_eligibility
    BEFORE INSERT ON dispute_vote
    FOR EACH ROW EXECUTE FUNCTION enforce_dispute_vote_eligibility();

-- 数据库层阻止审计记录被改写或删除，避免绕过应用层的“只追加”约束。
CREATE FUNCTION reject_judge_admission_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'judge admission audit is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_judge_admission_audit_immutable
    BEFORE UPDATE OR DELETE ON judge_admission_audit
    FOR EACH ROW EXECUTE FUNCTION reject_judge_admission_audit_mutation();
