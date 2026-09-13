-- 任务书 #101 C101-08（§7.1/§7.2）：视觉执行持久层。
-- card_series_operation 扩展（旧行不改：api_version=1 默认，v2 行由服务单独校验）、
-- creation_visual_item 子项表、creation_visual_artifact 成品表（Java 侧由 C101-09 落地）。
-- 幂等 DDL（ADD COLUMN IF NOT EXISTS）：OutboxRepositoryIT 等迁移重放测试依赖。

ALTER TABLE card_series_operation
    ADD COLUMN IF NOT EXISTS api_version int NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS job_kind varchar(32),
    ADD COLUMN IF NOT EXISTS draft_id uuid,
    ADD COLUMN IF NOT EXISTS plan_id uuid,
    ADD COLUMN IF NOT EXISTS plan_revision int,
    ADD COLUMN IF NOT EXISTS quote_id uuid,
    ADD COLUMN IF NOT EXISTS snapshot_json jsonb,
    ADD COLUMN IF NOT EXISTS job_version int NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS cancel_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS workflow_id varchar(128),
    ADD COLUMN IF NOT EXISTS dispatch_state varchar(32),
    ADD COLUMN IF NOT EXISTS settlement_state varchar(32);

CREATE INDEX IF NOT EXISTS idx_card_series_operation_v2_dispatch
    ON card_series_operation (api_version, dispatch_state, updated_at);

-- 视觉子项：id=attemptId；execution_operation_id 确定性生成（父任务ID+itemId+attempt）。
-- 预算恢复句柄（budget_id/budget_reservation_date/reserved_cents）沿用 V62 video_shot_audio 的
-- 恢复模式：结算中断后可重建 BudgetCheckResult 只重放结算，不重发供应商请求。
CREATE TABLE IF NOT EXISTS creation_visual_item (
    id                       uuid PRIMARY KEY,
    operation_id             uuid NOT NULL,
    item_id                  varchar(64) NOT NULL,
    position                 int NOT NULL,
    state                    varchar(32) NOT NULL,
    execution_operation_id   uuid NOT NULL,
    run_id                   uuid,
    input_hash               char(64),
    budget_id                uuid,
    budget_reservation_date  date,
    reserved_cents           int,
    original_media_id        uuid,
    artifact_id              uuid,
    anchor_artifact_id       uuid,
    error_code               varchar(64),
    claim_token              uuid,
    claimed_until            timestamptz,
    heartbeat_at             timestamptz,
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_visual_item_operation_item UNIQUE (operation_id, item_id),
    CONSTRAINT uq_visual_item_execution_operation UNIQUE (execution_operation_id),
    CONSTRAINT ck_visual_item_state CHECK (state IN
        ('waiting_anchor', 'queued', 'prepared', 'dispatching', 'generated_unsettled',
         'succeeded', 'failed', 'cancelled', 'unknown'))
);

CREATE INDEX IF NOT EXISTS idx_visual_item_operation
    ON creation_visual_item (operation_id, position);
CREATE INDEX IF NOT EXISTS idx_visual_item_claim
    ON creation_visual_item (state, claimed_until)
    WHERE state IN ('queued', 'dispatching', 'generated_unsettled');

-- 视觉成品：attempt 唯一（同 attempt 重复登记读已有行）；原图与交付图分开（§6.7 D10）。
CREATE TABLE IF NOT EXISTS creation_visual_artifact (
    id                  uuid PRIMARY KEY,
    owner_account_id    text NOT NULL,
    draft_id            uuid NOT NULL,
    plan_id             uuid NOT NULL,
    plan_revision       int NOT NULL,
    item_id             varchar(64) NOT NULL,
    attempt_id          uuid NOT NULL,
    run_id              uuid,
    original_media_id   uuid NOT NULL,
    delivery_media_id   uuid NOT NULL,
    target_aspect       varchar(16) NOT NULL,
    width               int NOT NULL,
    height              int NOT NULL,
    content_hash        char(64) NOT NULL,
    anchor_artifact_id  uuid,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_visual_artifact_attempt UNIQUE (attempt_id),
    CONSTRAINT ck_visual_artifact_dimensions CHECK (width > 0 AND height > 0)
);

CREATE INDEX IF NOT EXISTS idx_visual_artifact_owner
    ON creation_visual_artifact (owner_account_id, draft_id, plan_id, item_id, created_at);
