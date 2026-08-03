-- GL-P3-AI-001 Phase 3：AI 模型预算控制（Model Budget）。
-- 独立 Flyway 历史：intelligence_flyway_schema（now at v6）。

-- AI Run 记录表（usage account）
CREATE TABLE ai_run (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     text,                             -- 可空；个人用户 Run
    account_id          text NOT NULL,                    -- 执行者
    capability          varchar(64) NOT NULL,              -- text/image/video_generation 等
    provider            varchar(64) NOT NULL,              -- qwen/openai-compatible
    model               varchar(128),                      -- 使用的模型
    run_type            varchar(32) NOT NULL DEFAULT 'sync', -- sync/async/sse

    -- 用量计量
    input_tokens        int,
    output_tokens       int,
    images_generated    int DEFAULT 0,
    video_seconds       int DEFAULT 0,

    -- 预算与结算
    budget_cents        int NOT NULL,                     -- 预算上限（按能力按实量换算）
    actual_cents        int,                              -- 实际消耗（成功后结算）

    -- 状态
    status              varchar(32) NOT NULL DEFAULT 'running', -- running/completed/failed/cancelled
    failure_reason      text,

    -- 时间
    started_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,

    -- 计费相关
    price_table_version text DEFAULT 'v1',               -- 价目表版本
    operation_id        uuid NOT NULL,                   -- 幂等键（credits 预留/退回）
    refund_operation_id uuid,                            -- 退回预留时的 operation ID

    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

-- 组织级模型预算配置
CREATE TABLE ai_model_budget (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     text NOT NULL,
    capability          varchar(64) NOT NULL,              -- 能力（text/image_generation 等）
    provider            varchar(64) NOT NULL DEFAULT 'platform', -- platform 或特定 provider

    -- 预算限制（任一达限即停止）
    max_tokens_per_run  int,                              -- 单次 Run 最大 token 数（null=不限）
    max_tokens_daily    bigint,                           -- 每日最大 token 数（null=不限）
    max_tokens_monthly  bigint,                           -- 每月最大 token 数（null=不限）
    max_cents_per_run   int,                              -- 单次 Run 最大金额（分，null=不限）
    max_cents_daily     bigint,                           -- 每日最大金额（分，null=不限）
    max_cents_monthly  bigint,                           -- 每月最大金额（分，null=不限）

    -- 统计（用于计算已用量）
    current_daily_tokens   bigint DEFAULT 0,
    current_daily_cents    bigint DEFAULT 0,
    current_monthly_tokens bigint DEFAULT 0,
    current_monthly_cents  bigint DEFAULT 0,
    last_reset_date        date DEFAULT CURRENT_DATE,

    enabled             boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),

    UNIQUE(organization_id, capability, provider)
);

-- 索引：按组织查询预算
CREATE INDEX idx_ai_model_budget_lookup
    ON ai_model_budget(organization_id, capability, enabled)
    WHERE enabled = true;

-- 索引：按账号查询 Run
CREATE INDEX idx_ai_run_account
    ON ai_run(account_id, started_at DESC);

-- 索引：按组织查询 Run
CREATE INDEX idx_ai_run_org
    ON ai_run(organization_id, started_at DESC)
    WHERE organization_id IS NOT NULL;

-- 索引：按操作 ID 查询（幂等退款）
CREATE INDEX idx_ai_run_operation
    ON ai_run(operation_id);
