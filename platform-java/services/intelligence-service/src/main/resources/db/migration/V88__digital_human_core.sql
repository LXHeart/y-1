-- 任务书 #105B C105B-01（共享契约 K05）：数字人工作台核心表（B/C/D/G 一次建齐；F 媒体表另行迁移）。
-- 通用列：id UUID 主键（Java 生成）、owner_account_id TEXT NOT NULL（dh_catalog/dh_admin_audit 除外）、
-- version INTEGER>0、created_at/updated_at TIMESTAMPTZ；dh_event 例外只有列出列+owner_account_id，
-- 以 (session_id,seq) 复合主键。所有 dh 个人表不预建 organization_id 列（首期固定个人，K05）。
-- 外键不带 ON DELETE CASCADE（不吞审计；内容删除走显式清理步骤）。
-- 序号/代次统一 0~2^53-1（JS 安全整数上界）CHECK，超界由 DB 拒绝。

-- ---------- B：目录 / 角色 / 操作幂等 ----------

CREATE TABLE IF NOT EXISTS dh_profile (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    name varchar(160) NOT NULL,
    active_revision integer NOT NULL DEFAULT 1 CHECK (active_revision > 0),
    status text NOT NULL CHECK (status IN ('active', 'deleted')),
    deleted_at timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dh_profile_owner_updated
    ON dh_profile(owner_account_id, updated_at, id);

CREATE TABLE IF NOT EXISTS dh_profile_revision (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    profile_id uuid NOT NULL REFERENCES dh_profile(id),
    revision integer NOT NULL CHECK (revision > 0),
    persona text NOT NULL,
    greeting text NOT NULL,
    tone text NOT NULL CHECK (tone IN ('natural', 'professional', 'friendly')),
    avatar_id uuid NOT NULL,
    avatar_revision integer NOT NULL CHECK (avatar_revision > 0),
    voice_id text NOT NULL,
    catalog_version integer NOT NULL CHECK (catalog_version > 0),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_profile_revision UNIQUE (profile_id, revision)
);

CREATE INDEX IF NOT EXISTS idx_dh_profile_revision_owner
    ON dh_profile_revision(owner_account_id);

-- 平台目录 singleton（无 owner/UUID 主键；配置差异走审计，不存密钥/正文）。
CREATE TABLE IF NOT EXISTS dh_catalog (
    singleton_id integer PRIMARY KEY CHECK (singleton_id = 1),
    version integer NOT NULL CHECK (version > 0),
    config_json jsonb NOT NULL,
    updated_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 幂等键 (owner_account_id, kind, request_id)；payloadHash=canonical JSON SHA-256。
CREATE TABLE IF NOT EXISTS dh_operation (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    kind text NOT NULL,
    request_id uuid NOT NULL,
    payload_hash char(64) NOT NULL,
    resource_id uuid,
    state text NOT NULL CHECK (state IN ('pending', 'running', 'succeeded', 'failed', 'unknown')),
    result_ref uuid,
    error_code text,
    retry_at timestamptz,
    lease_owner text,
    lease_until timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_operation_key UNIQUE (owner_account_id, kind, request_id)
);

CREATE INDEX IF NOT EXISTS idx_dh_operation_owner_kind_time
    ON dh_operation(owner_account_id, kind, created_at);

-- ---------- C：会话 / 轮次 / 事件 / 转写 ----------

CREATE TABLE IF NOT EXISTS dh_session (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    profile_id uuid NOT NULL,
    profile_revision integer NOT NULL CHECK (profile_revision > 0),
    profile_name_at_creation text NOT NULL,
    deleted_at timestamptz,
    backend_id text NOT NULL,
    preflight_id uuid NOT NULL UNIQUE,
    worker_id text,
    state_entered_at timestamptz NOT NULL,
    last_browser_heartbeat_at timestamptz,
    worker_lease_expires_at timestamptz,
    last_activity_at timestamptz,
    next_turn_epoch bigint NOT NULL DEFAULT 1 CHECK (next_turn_epoch BETWEEN 0 AND 9007199254740991),
    render_ms bigint NOT NULL DEFAULT 0 CHECK (render_ms >= 0),
    config_snapshot jsonb NOT NULL,
    state text NOT NULL CHECK (state IN ('preparing', 'queued', 'connecting', 'ready', 'listening',
        'responding', 'paused', 'reconnecting', 'ending', 'ended', 'failed')),
    lease_epoch bigint NOT NULL DEFAULT 1 CHECK (lease_epoch BETWEEN 0 AND 9007199254740991),
    media_epoch bigint NOT NULL DEFAULT 1 CHECK (media_epoch BETWEEN 0 AND 9007199254740991),
    controller_id uuid NOT NULL,
    last_seq bigint NOT NULL DEFAULT 0 CHECK (last_seq BETWEEN 0 AND 9007199254740991),
    ready_at timestamptz,
    expires_at timestamptz,
    paused_until timestamptz,
    lease_expires_at timestamptz,
    ended_at timestamptz,
    save_transcript boolean NOT NULL DEFAULT false,
    transcript_version integer NOT NULL DEFAULT 1 CHECK (transcript_version > 0),
    content_epoch bigint NOT NULL DEFAULT 1 CHECK (content_epoch BETWEEN 0 AND 9007199254740991),
    content_deleted boolean NOT NULL DEFAULT false,
    cleanup_pending boolean NOT NULL DEFAULT false,
    error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- owner 活动会话唯一（state 非 ended/failed；ending 仍占位，K05）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_dh_session_owner_active
    ON dh_session(owner_account_id)
    WHERE state NOT IN ('ended', 'failed');

CREATE INDEX IF NOT EXISTS idx_dh_session_owner_created
    ON dh_session(owner_account_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_dh_session_state_lease
    ON dh_session(state, lease_expires_at);

CREATE TABLE IF NOT EXISTS dh_turn (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    session_id uuid NOT NULL REFERENCES dh_session(id),
    request_id uuid NOT NULL,
    turn_epoch bigint NOT NULL CHECK (turn_epoch BETWEEN 0 AND 9007199254740991),
    input_kind text NOT NULL CHECK (input_kind IN ('text', 'audio', 'greeting')),
    state text NOT NULL CHECK (state IN ('accepted', 'transcribing', 'generating', 'speaking',
        'completed', 'interrupted', 'failed', 'unknown')),
    started_at timestamptz NOT NULL,
    ended_at timestamptz,
    error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_turn_request UNIQUE (session_id, request_id)
);

-- 单 session 活动轮次唯一（非终态）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_dh_turn_session_active
    ON dh_turn(session_id)
    WHERE state IN ('accepted', 'transcribing', 'generating', 'speaking');

CREATE TABLE IF NOT EXISTS dh_event (
    session_id uuid NOT NULL REFERENCES dh_session(id),
    seq bigint NOT NULL CHECK (seq BETWEEN 0 AND 9007199254740991),
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    owner_account_id text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, seq),
    CONSTRAINT uq_dh_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS idx_dh_event_owner_created
    ON dh_event(owner_account_id, created_at);

CREATE TABLE IF NOT EXISTS dh_transcript (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    session_id uuid NOT NULL REFERENCES dh_session(id),
    utterance_id uuid NOT NULL,
    utterance_seq bigint NOT NULL CHECK (utterance_seq BETWEEN 0 AND 9007199254740991),
    role text NOT NULL CHECK (role IN ('user', 'assistant')),
    final_text text NOT NULL,
    status text NOT NULL CHECK (status IN ('complete', 'interrupted', 'truncated')),
    started_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL,
    content_epoch bigint NOT NULL CHECK (content_epoch BETWEEN 0 AND 9007199254740991),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_transcript_utterance UNIQUE (session_id, utterance_id)
);

CREATE INDEX IF NOT EXISTS idx_dh_transcript_session_seq
    ON dh_transcript(session_id, utterance_seq);

-- ---------- D：调用/用量（B 建表，D 首用） ----------

CREATE TABLE IF NOT EXISTS dh_invocation (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    session_id uuid,
    turn_id uuid,
    stage text NOT NULL CHECK (stage IN ('stt', 'llm', 'tts', 'preview', 'render')),
    resource_id uuid NOT NULL,
    segment_index integer NOT NULL CHECK (segment_index >= 0),
    operation_id uuid NOT NULL,
    ai_run_id uuid,
    state text NOT NULL CHECK (state IN ('reserved', 'preparing', 'prepared', 'dispatched',
        'succeeded', 'failed', 'cancelled', 'unknown')),
    settlement_state text NOT NULL CHECK (settlement_state IN ('not_required', 'pending', 'settled', 'failed')),
    provider_snapshot jsonb NOT NULL,
    budget_snapshot jsonb NOT NULL,
    usage_json jsonb,
    provider_run_id text,
    request_hash char(64) NOT NULL,
    deadline_at timestamptz NOT NULL,
    next_attempt_at timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_invocation_resource UNIQUE (owner_account_id, resource_id, stage, segment_index),
    CONSTRAINT uq_dh_invocation_operation UNIQUE (operation_id),
    CONSTRAINT uq_dh_invocation_run UNIQUE (ai_run_id),
    -- stage 形状（K05/K07.3）：preview 不挂 session；render 挂 session 不挂 turn、segment=0；
    -- stt/llm/tts 均由具体 turn 产生。
    CONSTRAINT ck_dh_invocation_stage_shape CHECK (
        (stage = 'preview' AND session_id IS NULL AND turn_id IS NULL)
        OR (stage = 'render' AND session_id IS NOT NULL AND turn_id IS NULL AND segment_index = 0)
        OR (stage IN ('stt', 'llm', 'tts') AND session_id IS NOT NULL AND turn_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_dh_invocation_owner_stage_state
    ON dh_invocation(owner_account_id, stage, state);

CREATE TABLE IF NOT EXISTS dh_preview (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    voice_id text NOT NULL,
    catalog_version integer NOT NULL CHECK (catalog_version > 0),
    state text NOT NULL CHECK (state IN ('processing', 'ready', 'failed', 'expired')),
    expires_at timestamptz NOT NULL,
    invocation_id uuid,
    error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dh_preview_owner_created
    ON dh_preview(owner_account_id, created_at);

-- ---------- G：管理审计（B 建表，G 首用） ----------

CREATE TABLE IF NOT EXISTS dh_admin_audit (
    id uuid PRIMARY KEY,
    actor_account_id text NOT NULL,
    action text NOT NULL,
    resource_id uuid,
    request_id uuid NOT NULL,
    reason text NOT NULL,
    metadata_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_admin_audit UNIQUE (actor_account_id, action, request_id)
);

-- ---------- dh 个人写守卫（K05：独立函数，同一 account gate 初始化/SHARE 锁规则） ----------
-- 与 V86/V87 的 intelligence_guard_personal_write 语义对齐但独立维护：冻结/清理期间拒绝新建；
-- UPDATE 只放行「同 owner、原 id、单向 terminal 收尾、正文清空」白名单（不泛放）。
-- 列引用一律走 to_jsonb 访问器：本函数挂在无 id 列的 dh_event 上，直引 NEW.id 会 42703
-- （plpgsql 语句不保证短路，V87 列引用守卫教训）。

CREATE OR REPLACE FUNCTION dh_guard_personal_write() RETURNS trigger AS $dh_guard$
DECLARE
    owner text;
    gate_state text;
    old_state text;
    new_state text;
    new_id text;
    old_id text;
BEGIN
    owner := NEW.owner_account_id;
    IF owner IS NULL THEN
        RETURN NEW;  -- NOT NULL 列外（dh_catalog/dh_admin_audit 不挂本触发器）；防御性放行
    END IF;
    -- 归属不可空也不接受客户端组织归属：dh 表无 organization_id 列，owner 即唯一归属。

    INSERT INTO intelligence_account_lifecycle(account_id) VALUES (owner)
        ON CONFLICT (account_id) DO NOTHING;
    SELECT state INTO gate_state FROM intelligence_account_lifecycle
        WHERE account_id = owner FOR SHARE;

    IF gate_state = 'active' THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF to_jsonb(NEW)->>'owner_account_id' IS DISTINCT FROM to_jsonb(OLD)->>'owner_account_id' THEN
            RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
                USING ERRCODE = 'check_violation';
        END IF;
        -- 原 id 不变（dh_event 无 id 列，退回 session_id/seq 复合身份）。
        new_id := COALESCE(to_jsonb(NEW)->>'id',
            to_jsonb(NEW)->>'session_id' || '/' || to_jsonb(NEW)->>'seq');
        old_id := COALESCE(to_jsonb(OLD)->>'id',
            to_jsonb(OLD)->>'session_id' || '/' || to_jsonb(OLD)->>'seq');
        IF new_id IS DISTINCT FROM old_id THEN
            RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
                USING ERRCODE = 'check_violation';
        END IF;

        new_state := COALESCE(to_jsonb(NEW)->>'status', to_jsonb(NEW)->>'state');
        old_state := COALESCE(to_jsonb(OLD)->>'status', to_jsonb(OLD)->>'state');

        -- 单向 terminal 收尾：进入该表终态且此前非终态（终态不可离开、不可复活）。
        IF TG_TABLE_NAME = 'dh_profile' AND new_state = 'deleted'
           AND old_state IS DISTINCT FROM 'deleted' THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_operation' AND new_state IN ('succeeded', 'failed')
           AND (old_state IS NULL OR old_state IN ('pending', 'running', 'unknown')) THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_session' AND new_state IN ('ended', 'failed')
           AND (old_state IS NULL OR old_state NOT IN ('ended', 'failed')) THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_turn' AND new_state IN ('completed', 'interrupted', 'failed', 'unknown')
           AND (old_state IS NULL
                OR old_state NOT IN ('completed', 'interrupted', 'failed', 'unknown')) THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_invocation'
           AND new_state IN ('succeeded', 'failed', 'cancelled', 'unknown')
           AND (old_state IS NULL
                OR old_state NOT IN ('succeeded', 'failed', 'cancelled', 'unknown')) THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_preview' AND new_state IN ('failed', 'expired')
           AND (old_state IS NULL OR old_state NOT IN ('failed', 'expired')) THEN
            RETURN NEW;
        ELSIF TG_TABLE_NAME = 'dh_profile_revision'
           AND to_jsonb(NEW)->>'persona' = '' AND to_jsonb(NEW)->>'greeting' = ''
           AND to_jsonb(NEW)->>'revision' IS NOT DISTINCT FROM to_jsonb(OLD)->>'revision'
           AND to_jsonb(NEW)->>'profile_id' IS NOT DISTINCT FROM to_jsonb(OLD)->>'profile_id' THEN
            RETURN NEW;  -- 正文清空（注销擦除；版本与归属不变）
        ELSIF TG_TABLE_NAME = 'dh_transcript'
           AND to_jsonb(NEW)->>'final_text' = ''
           AND to_jsonb(NEW)->>'utterance_id' IS NOT DISTINCT FROM to_jsonb(OLD)->>'utterance_id'
           AND to_jsonb(NEW)->>'session_id' IS NOT DISTINCT FROM to_jsonb(OLD)->>'session_id' THEN
            RETURN NEW;  -- 正文清空
        END IF;
    END IF;

    RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
        USING ERRCODE = 'check_violation';
END;
$dh_guard$ LANGUAGE plpgsql;

-- 挂载（幂等：DROP IF EXISTS + CREATE）。dh_catalog/dh_admin_audit 无 owner，不挂。
DO $attach$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['dh_profile', 'dh_profile_revision', 'dh_operation', 'dh_session',
                             'dh_turn', 'dh_event', 'dh_transcript', 'dh_invocation', 'dh_preview']
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_dh_guard_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_dh_guard_%I BEFORE INSERT OR UPDATE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION dh_guard_personal_write()', t, t);
    END LOOP;
END
$attach$;
