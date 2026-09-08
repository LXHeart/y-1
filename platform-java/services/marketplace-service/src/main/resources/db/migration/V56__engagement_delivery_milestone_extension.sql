-- 任务书 #96 C96-01/C96-02（§7 V56）：交付期限/退出/延期 + 履约里程碑事实表。
-- 全部 IF NOT EXISTS（迁移重放红线）。

-- ---------- task_application：交付期限与退出事实（§7） ----------
-- delivery_deadline_at / remedy_deadline_at：accept 时快照（D96-01：截止随事件落行，改配置不影响存量）；
--   remedy = delivery + 补救窗（§5.3 默认 48h）。二者均为 NULL = 存量/套餐推广/旧政策行（D96-07 豁免）。
-- exited_at / exit_kind：退出/终结事实（no_fault=推荐官无责退出；negotiated=协商退出；timeout=交付超时有责终结）。
-- engagement_policy_version：履约政策版本（D96-07）：非 NULL 才受期限/退出/终结规则约束，历史行零回填。
-- delivery_workflow_started_at：交付看门狗 Temporal workflow 的 durable-intent 标记（照 confirmation 派发器范式；
--   延期批准时清空 → 派发器按新 deadline 补启，workflowId 含 deadline epoch，旧 workflow 由行级守卫自行 abort）。
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS delivery_deadline_at timestamptz;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS remedy_deadline_at timestamptz;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS exited_at timestamptz;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS exit_kind varchar(32);
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS engagement_policy_version integer;
ALTER TABLE task_application ADD COLUMN IF NOT EXISTS delivery_workflow_started_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_task_application_delivery_dispatch
    ON task_application(delivery_deadline_at)
    WHERE status = 'accepted' AND confirmed_at IS NULL AND exited_at IS NULL
      AND delivery_workflow_started_at IS NULL AND engagement_policy_version IS NOT NULL;

-- ---------- engagement_milestone：履约阶段事实（§7 / D96-03：事实记录，非状态机） ----------
CREATE TABLE IF NOT EXISTS engagement_milestone (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES task_application(id),
    kind varchar(32) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    evidence_submission_id uuid,
    proposed_by uuid NOT NULL,
    confirmed_by uuid,
    confirmed_at timestamptz,
    amount_cents bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (application_id, kind, version)
);

-- ---------- engagement_extension：交付延期申请/批准（C96-01 §6 /extend） ----------
CREATE TABLE IF NOT EXISTS engagement_extension (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES task_application(id),
    requested_by uuid NOT NULL,
    days integer NOT NULL,
    reason text,
    status varchar(32) NOT NULL DEFAULT 'pending',
    decided_by uuid,
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 同一报名同时最多一条待审延期申请（重复申请 409 由唯一索引兜底）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_engagement_extension_pending
    ON engagement_extension(application_id) WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_engagement_extension_application
    ON engagement_extension(application_id, created_at);
