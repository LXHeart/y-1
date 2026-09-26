-- 任务书 #107-1 C107-04（共享契约 K05/§7.1）：Hypit 全量引擎迁移业务表。
-- V91 空闲（当前最大 V90），按防撞号规则采用本号并在此登记实际路径。
-- 通用约定：id/uuid 主键（Java 生成）；account_id 沿用既有 TEXT 表示（K03）；
-- version/attempt/revision/ordinal/step_index/sequence 均为正整数 CHECK（0 允许处单列）；
-- created_at/updated_at TIMESTAMPTZ DEFAULT now()；FK 一律 NO ACTION（不级联删工程/审计）；
-- 金额 numeric(20,6)；hash 一律 char(64) 小写 hex。所有 CREATE 用 IF NOT EXISTS
-- 防重放（仓库既有防重方式）；CHECK/UNIQUE 内联在表定义里，重复执行不产生
-- duplicate object，也不覆盖已有不同约束。

-- ---------- 工程与幂等命令 ----------

CREATE TABLE IF NOT EXISTS hypit_project (
    id uuid PRIMARY KEY,
    account_id text NOT NULL,
    workspace_id uuid NOT NULL,
    title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 1 AND 60),
    mode text NOT NULL CHECK (mode IN ('clone', 'brief', 'template', 'import')),
    status text NOT NULL CHECK (status IN ('provisioning', 'ready', 'deleting', 'deleted', 'provisioning_failed')),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    head_manifest_hash text,
    selected_run text,
    source_context jsonb,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_project_owner UNIQUE (id, account_id),
    CONSTRAINT uq_hypit_project_workspace UNIQUE (workspace_id)
);

CREATE INDEX IF NOT EXISTS idx_hypit_project_owner_updated
    ON hypit_project(account_id, updated_at, id);

-- 幂等命令表：UNIQUE(account_id, action, request_id)（K06.1）。
CREATE TABLE IF NOT EXISTS hypit_command (
    id uuid PRIMARY KEY,
    account_id text NOT NULL,
    project_id uuid,
    target_key text NOT NULL,
    action text NOT NULL,
    request_id uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    payload_json jsonb NOT NULL,
    state text NOT NULL CHECK (state IN ('queued', 'dispatching', 'acknowledged', 'succeeded', 'failed', 'unknown')),
    lease_owner uuid,
    lease_until timestamptz,
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    result_json jsonb,
    error_code text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_command_idempotency UNIQUE (account_id, action, request_id)
);

CREATE INDEX IF NOT EXISTS idx_hypit_command_state_lease
    ON hypit_command(state, lease_until, created_at);

-- ---------- 修订 / 变更集 / 素材引用 ----------

CREATE TABLE IF NOT EXISTS hypit_revision (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    number bigint NOT NULL CHECK (number > 0),
    parent_number bigint CHECK (parent_number IS NULL OR parent_number > 0),
    manifest_hash char(64) NOT NULL,
    snapshot_handle text NOT NULL,
    command_id uuid UNIQUE,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_revision_number UNIQUE (project_id, number)
);

CREATE INDEX IF NOT EXISTS idx_hypit_revision_parent
    ON hypit_revision(project_id, parent_number);

CREATE TABLE IF NOT EXISTS hypit_changeset (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    command_id uuid UNIQUE,
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    apply_mode text NOT NULL CHECK (apply_mode IN ('save', 'validated')),
    change_manifest_handle text NOT NULL,
    check_status text NOT NULL DEFAULT 'not_checked' CHECK (check_status IN ('not_checked', 'passed', 'failed')),
    state text NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'applying', 'applied', 'rejected', 'conflict')),
    applied_revision bigint,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS hypit_asset (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    media_id uuid,
    resource_handle text NOT NULL,
    role text NOT NULL CHECK (role IN ('reference', 'portrait', 'product', 'voice', 'music', 'footage', 'font', 'other')),
    origin_kind text NOT NULL CHECK (origin_kind IN ('upload', 'library', 'url', 'tool', 'generated', 'import')),
    origin_url text,
    sha256 char(64) NOT NULL,
    mime_type text NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    width integer,
    height integer,
    probe jsonb,
    status text NOT NULL CHECK (status IN ('importing', 'ready', 'failed', 'deleted')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_asset_project_status
    ON hypit_asset(project_id, status);

-- 显式引用表（快照依赖与删除保护；K05）。
CREATE TABLE IF NOT EXISTS hypit_asset_reference (
    project_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    asset_id uuid NOT NULL REFERENCES hypit_asset(id),
    relative_path text NOT NULL,
    sha256 char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, revision, relative_path)
);

-- ---------- Job / 动作 / 事件 ----------

CREATE TABLE IF NOT EXISTS hypit_job (
    id uuid PRIMARY KEY,
    command_id uuid UNIQUE,
    project_id uuid,
    account_id text NOT NULL,
    kind text NOT NULL,
    state text NOT NULL CHECK (state IN ('queued', 'running', 'waiting_input', 'cancel_requested', 'succeeded', 'failed', 'cancelled')),
    phase text,
    progress_json jsonb,
    checkpoint_json jsonb,
    base_revision bigint,
    grant_id uuid,
    step_index integer NOT NULL DEFAULT 0 CHECK (step_index >= 0),
    attempt integer NOT NULL DEFAULT 1 CHECK (attempt > 0),
    lease_owner uuid,
    lease_until timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    cancel_requested_at timestamptz,
    blocked_reason text,
    error_code text,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_job_owner_project_created
    ON hypit_job(account_id, project_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_hypit_job_state_lease
    ON hypit_job(state, lease_until);

CREATE TABLE IF NOT EXISTS hypit_job_action (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES hypit_job(id),
    step_index integer NOT NULL CHECK (step_index >= 0),
    kind text NOT NULL CHECK (kind IN ('llm', 'tool', 'apply', 'approval', 'review')),
    state text NOT NULL CHECK (state IN ('prepared', 'dispatched', 'succeeded', 'failed', 'unknown', 'cancelled')),
    input_hash char(64) NOT NULL,
    input_json jsonb,
    result_json jsonb,
    ai_run_id uuid,
    operation_id uuid,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_job_action_step UNIQUE (job_id, step_index)
);

CREATE TABLE IF NOT EXISTS hypit_job_event (
    job_id uuid NOT NULL REFERENCES hypit_job(id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    type text NOT NULL CHECK (type IN ('snapshot', 'progress', 'checkpoint', 'output', 'diagnostic', 'terminal', 'heartbeat')),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, sequence)
);

-- ---------- 计划 / 价格 / 授权 / 构建 / 输出 / 执行 / 变体 ----------

CREATE TABLE IF NOT EXISTS hypit_plan (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    revision bigint NOT NULL CHECK (revision > 0),
    run_file text NOT NULL,
    plan_hash char(64) NOT NULL,
    profile_hash char(64) NOT NULL,
    plan_json jsonb NOT NULL,
    pricing_json jsonb,
    repository_location jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_plan_project_revision
    ON hypit_plan(project_id, revision);

CREATE TABLE IF NOT EXISTS hypit_pricing_snapshot (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES hypit_plan(id),
    pricing_hash char(64) NOT NULL,
    costs_json jsonb NOT NULL,
    unknowns_json jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_pricing_plan_hash UNIQUE (plan_id, pricing_hash)
);

CREATE TABLE IF NOT EXISTS hypit_execution_grant (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    account_id text NOT NULL,
    plan_id uuid,
    pricing_id uuid REFERENCES hypit_pricing_snapshot(id),
    scope_hash char(64) NOT NULL,
    scope_json jsonb NOT NULL,
    max_cost numeric(20,6) CHECK (max_cost IS NULL OR max_cost >= 0),
    currency text NOT NULL,
    allow_unknown boolean NOT NULL DEFAULT false,
    variant_count integer NOT NULL DEFAULT 1 CHECK (variant_count > 0),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_grant_project
    ON hypit_execution_grant(project_id);

CREATE TABLE IF NOT EXISTS hypit_build (
    id uuid PRIMARY KEY,
    engine_build_id text UNIQUE,
    command_id uuid UNIQUE,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    revision bigint NOT NULL CHECK (revision > 0),
    plan_id uuid REFERENCES hypit_plan(id),
    run_file text NOT NULL,
    lifecycle text NOT NULL CHECK (lifecycle IN ('submitting', 'active', 'execution_decided', 'result_pending', 'finished', 'submission_incomplete')),
    outcome text CHECK (outcome IS NULL OR outcome IN ('complete', 'failed', 'cancelled')),
    result_location jsonb,
    submitted_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_build_project_created
    ON hypit_build(project_id, created_at, id);

CREATE TABLE IF NOT EXISTS hypit_output (
    id uuid PRIMARY KEY,
    build_id uuid NOT NULL REFERENCES hypit_build(id),
    output_name text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('scalar', 'resource', 'composite')),
    media_type text,
    resource_handle text,
    value_summary jsonb NOT NULL,
    archive_state text NOT NULL DEFAULT 'pending' CHECK (archive_state IN ('pending', 'archiving', 'archived', 'failed')),
    media_id uuid,
    archive_command_id uuid,
    error_code text,
    size_bytes bigint CHECK (size_bytes IS NULL OR size_bytes >= 0),
    duration_seconds numeric(12,6) CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_output_name UNIQUE (build_id, output_name)
);

CREATE INDEX IF NOT EXISTS idx_hypit_output_build
    ON hypit_output(build_id);

CREATE TABLE IF NOT EXISTS hypit_execution (
    operation_id uuid PRIMARY KEY,
    job_id uuid,
    build_id uuid,
    grant_id uuid NOT NULL REFERENCES hypit_execution_grant(id),
    need_id text NOT NULL,
    ai_run_id uuid,
    endpoint_id text NOT NULL,
    capability text NOT NULL,
    model text NOT NULL,
    request_hash char(64) NOT NULL,
    state text NOT NULL CHECK (state IN ('prepared', 'submitting', 'submitted', 'running', 'succeeded', 'failed', 'cancelled', 'unknown')),
    receipt jsonb,
    estimated_cost numeric(20,6),
    actual_cost numeric(20,6),
    currency text NOT NULL,
    provider_cancel_state text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_hypit_execution_build
    ON hypit_execution(build_id);
CREATE INDEX IF NOT EXISTS idx_hypit_execution_job
    ON hypit_execution(job_id);

CREATE TABLE IF NOT EXISTS hypit_variant (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES hypit_project(id),
    batch_job_id uuid NOT NULL REFERENCES hypit_job(id),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    base_revision bigint NOT NULL CHECK (base_revision >= 0),
    parameters_json jsonb NOT NULL,
    run_file text NOT NULL,
    plan_id uuid,
    build_id uuid,
    state text NOT NULL DEFAULT 'draft' CHECK (state IN ('draft', 'planned', 'queued', 'running', 'succeeded', 'failed', 'cancelled')),
    attempt integer NOT NULL DEFAULT 1 CHECK (attempt > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_hypit_variant_ordinal UNIQUE (batch_job_id, ordinal)
);
