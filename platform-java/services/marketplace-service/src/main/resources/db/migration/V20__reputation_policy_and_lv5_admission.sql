-- GL-P2-ADMIN-007 / GL-P2-TRUST-001：可配置推荐官等级权益、Lv5 邀请与审计。
-- 策略头承担全局乐观锁；五条规则在同一事务中更新，消费者只会看到完整版本。
CREATE TABLE reputation_policy (
    id smallint PRIMARY KEY DEFAULT 1,
    version bigint NOT NULL DEFAULT 1,
    updated_by uuid,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_reputation_policy_singleton CHECK (id = 1),
    CONSTRAINT ck_reputation_policy_version CHECK (version >= 1)
);

CREATE TABLE reputation_level_rule (
    level_number smallint PRIMARY KEY,
    level_code varchar(3) NOT NULL UNIQUE,
    title varchar(32) NOT NULL,
    min_completed integer NOT NULL,
    min_completion_rate numeric(6,5) NOT NULL,
    min_average_score numeric(3,2),
    invite_only boolean NOT NULL DEFAULT false,
    judge_eligible boolean NOT NULL DEFAULT false,
    task_priority_weight integer NOT NULL,
    settlement_delay_days integer NOT NULL,
    commission_bonus_bps integer NOT NULL,
    ai_quota_multiplier_bps integer NOT NULL,
    premium_support boolean NOT NULL DEFAULT false,
    benefits jsonb NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT ck_reputation_level_number CHECK (level_number BETWEEN 1 AND 5),
    CONSTRAINT ck_reputation_level_code CHECK (level_code = 'Lv' || level_number::text),
    CONSTRAINT ck_reputation_level_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 32),
    CONSTRAINT ck_reputation_level_completed CHECK (min_completed BETWEEN 0 AND 1000000),
    CONSTRAINT ck_reputation_level_rate CHECK (min_completion_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_reputation_level_score CHECK (min_average_score IS NULL OR min_average_score BETWEEN 0 AND 5),
    CONSTRAINT ck_reputation_level_invite CHECK (invite_only = (level_number = 5)),
    CONSTRAINT ck_reputation_level_judge CHECK (judge_eligible = (level_number = 5)),
    CONSTRAINT ck_reputation_level_priority CHECK (task_priority_weight BETWEEN 1 AND 10000),
    CONSTRAINT ck_reputation_level_settlement CHECK (settlement_delay_days BETWEEN 0 AND 30),
    CONSTRAINT ck_reputation_level_commission CHECK (commission_bonus_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_reputation_level_ai_quota CHECK (ai_quota_multiplier_bps BETWEEN 1000 AND 100000),
    CONSTRAINT ck_reputation_level_benefits CHECK (
        jsonb_typeof(benefits) = 'array' AND jsonb_array_length(benefits) <= 16
    )
);

CREATE TABLE reputation_lv5_admission (
    account_id uuid PRIMARY KEY,
    admitted boolean NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    updated_by uuid NOT NULL,
    note varchar(500) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_reputation_lv5_admission_version CHECK (version >= 1),
    CONSTRAINT ck_reputation_lv5_admission_note CHECK (char_length(btrim(note)) BETWEEN 1 AND 500)
);

-- 只追加审计：仓储层不提供 UPDATE/DELETE。
CREATE TABLE reputation_admin_audit (
    id bigserial PRIMARY KEY,
    action varchar(40) NOT NULL,
    target_account_id uuid,
    actor_account_id uuid NOT NULL,
    actor_role varchar(128) NOT NULL,
    policy_version bigint,
    admission_version bigint,
    note varchar(500),
    before_snapshot jsonb NOT NULL,
    after_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_reputation_admin_audit_action CHECK (
        action IN ('policy_updated', 'lv5_granted', 'lv5_revoked')
    ),
    CONSTRAINT ck_reputation_admin_audit_before CHECK (jsonb_typeof(before_snapshot) = 'object'),
    CONSTRAINT ck_reputation_admin_audit_after CHECK (jsonb_typeof(after_snapshot) = 'object')
);

CREATE INDEX idx_reputation_admin_audit_target
    ON reputation_admin_audit (target_account_id, id) WHERE target_account_id IS NOT NULL;

-- 仓储层没有 update/delete 入口仍不足以防止旁路 SQL；数据库层拒绝任何审计改写。
CREATE FUNCTION reject_reputation_admin_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'reputation admin audit is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reputation_admin_audit_immutable
    BEFORE UPDATE OR DELETE ON reputation_admin_audit
    FOR EACH ROW EXECUTE FUNCTION reject_reputation_admin_audit_mutation();

INSERT INTO reputation_policy(id, version) VALUES (1, 1);

INSERT INTO reputation_level_rule(
    level_number, level_code, title, min_completed, min_completion_rate, min_average_score,
    invite_only, judge_eligible, task_priority_weight, settlement_delay_days,
    commission_bonus_bps, ai_quota_multiplier_bps, premium_support, benefits)
VALUES
    (1, 'Lv1', '新手草友', 0, 0.00, NULL, false, false, 100, 2, 0, 10000, false,
        '["基础任务"]'::jsonb),
    (2, 'Lv2', '活跃草友', 6, 0.80, NULL, false, false, 110, 2, 0, 10000, false,
        '["更多任务"]'::jsonb),
    (3, 'Lv3', '优质草友', 21, 0.85, 4.00, false, false, 120, 2, 300, 15000, false,
        '["优先推荐"]'::jsonb),
    (4, 'Lv4', '金牌草友', 51, 0.90, 4.50, false, false, 140, 2, 500, 15000, true,
        '["专属任务", "专属支持"]'::jsonb),
    (5, 'Lv5', '草场达人', 100, 0.95, 4.80, true, true, 160, 1, 1000, 15000, true,
        '["审判官资格", "T+1 优先结算"]'::jsonb);
