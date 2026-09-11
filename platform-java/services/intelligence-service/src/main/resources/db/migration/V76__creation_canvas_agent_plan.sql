-- 任务书 #100（C100-16 / API-13/14/15 / §7.1 V76 / §6.6）：画布 AI 计划表。
--
-- 语义：账号+operationId 唯一 = 幂等占位（先落行再调模型，崩溃/断线不重复计费）；
-- base_* 三版本记录计划基线（apply 时 CAS 校验）；status: preparing/ready/clarify/
-- failed/applied/expired；action jsonb 只允许 §6.6 单顶层动作形态（服务层严格解析）；
-- expires_at：preparing 行以创建+30min 填写，ready/clarify 从完成时刻计 30 分钟。
--
-- 硬约束（沿用 V71～V75 口径）：DDL 全幂等，不建 FK。

CREATE TABLE IF NOT EXISTS creation_canvas_agent_plan (
    id uuid PRIMARY KEY,
    account_id text NOT NULL,
    operation_id uuid NOT NULL,
    request_hash char(64) NOT NULL,
    draft_id uuid NOT NULL,
    storyboard_id uuid NOT NULL,
    base_draft_version int NOT NULL,
    base_edit_version bigint NOT NULL,
    base_canvas_revision bigint NOT NULL,
    status varchar(16) NOT NULL,
    selected_node_ids jsonb NOT NULL,
    instruction text NOT NULL,
    summary text NOT NULL DEFAULT '',
    clarification text,
    action jsonb,
    run_id uuid,
    error_code varchar(64),
    apply_result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'creation_canvas_agent_plan_account_operation_key'
                       AND tablename = 'creation_canvas_agent_plan') THEN
        CREATE UNIQUE INDEX creation_canvas_agent_plan_account_operation_key
            ON creation_canvas_agent_plan (account_id, operation_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'creation_canvas_agent_plan_account_recent'
                       AND tablename = 'creation_canvas_agent_plan') THEN
        CREATE INDEX creation_canvas_agent_plan_account_recent
            ON creation_canvas_agent_plan (account_id, draft_id, created_at DESC);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes
                     WHERE indexname = 'creation_canvas_agent_plan_status_expiry'
                       AND tablename = 'creation_canvas_agent_plan') THEN
        CREATE INDEX creation_canvas_agent_plan_status_expiry
            ON creation_canvas_agent_plan (status, expires_at);
    END IF;
END $$;

COMMENT ON TABLE creation_canvas_agent_plan IS
    '#100 C100-16 画布 AI 计划：操作键幂等占位，严格动作协议由服务层校验';
