-- 任务书 #103 C103-02（R01 / D103-01/02）：无责/协商退出的资金恢复操作与资金腿。
-- 业务终止权（task_application 终态）与资金意图在同一父行锁事务内提交；资金腿由
-- 恢复 worker（C103-03）按原经济键推进，本表只存编排事实与冻结快照，不存正文。
CREATE TABLE IF NOT EXISTS engagement_exit_operation (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL UNIQUE,
    task_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    kind varchar(32) NOT NULL CHECK (kind IN ('no_fault', 'negotiated')),
    exit_request_id uuid,
    business_version bigint NOT NULL DEFAULT 0,
    contract_version integer,
    settlement_snapshot jsonb NOT NULL,
    state varchar(32) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'processing', 'retry_wait', 'needs_review', 'succeeded')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz,
    lease_owner varchar(128),
    lease_token uuid,
    lease_expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    last_error_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK ((state = 'succeeded') = (completed_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_engagement_exit_operation_state
    ON engagement_exit_operation(state, next_attempt_at, id);
CREATE INDEX IF NOT EXISTS idx_engagement_exit_operation_task
    ON engagement_exit_operation(task_id, id);

-- 资金腿：economic_key = Finance 原动作名 + engagementRef（applicationId），同键重放不重复落账。
-- 成功腿不可变更金额/收款方或回退（应用层 guarded UPDATE 保证，CHECK 兜底状态域）。
CREATE TABLE IF NOT EXISTS engagement_exit_fund_leg (
    operation_id uuid NOT NULL REFERENCES engagement_exit_operation(id),
    leg_kind varchar(32) NOT NULL CHECK (leg_kind IN ('deposit_refund', 'bounty_capture', 'bounty_release')),
    economic_key varchar(256) NOT NULL UNIQUE,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    state varchar(32) NOT NULL DEFAULT 'pending'
        CHECK (state IN ('not_required', 'pending', 'unknown', 'succeeded', 'needs_review')),
    finance_reference varchar(256),
    verified_at timestamptz,
    params_hash varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_id, leg_kind)
);

CREATE INDEX IF NOT EXISTS idx_engagement_exit_fund_leg_state
    ON engagement_exit_fund_leg(operation_id, leg_kind, state);
