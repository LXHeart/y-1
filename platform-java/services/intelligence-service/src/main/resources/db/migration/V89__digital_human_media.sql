-- 任务书 #105F C105F-01（共享契约 K05/K09/K13.5/K14.3）：数字人媒体表（F 迁移一次建齐）。
-- 通用列与 V88 相同：id UUID 主键（Java 生成）、owner_account_id TEXT NOT NULL、version>0、
-- created_at/updated_at TIMESTAMPTZ；无 organization_id 列（首期固定个人）。
-- 外键不带 ON DELETE CASCADE（不吞审计；派生清理走 dh_cleanup 显式登记）。
-- dh_guard_personal_write 扩展：新表挂同一触发器函数（K05：不改 V86/V87 历史函数），
-- 本迁移以 CREATE OR REPLACE 追加 F 表的单向 terminal 收尾分支。

-- ---------- F：自有形象 / 录制 / 附件 / 派生清理 ----------

-- 自有图片形象：一行 = 一次 API30 受理（revision 固定 1；重传走新 avatar）。
-- provider_resource_refs（K14.3）：第三方检测/准备的资源句柄与兼容 backend——仅配置 id/版本、
-- 外部资源 id、删除状态，不含 URL/key/正文。
CREATE TABLE IF NOT EXISTS dh_avatar (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    source_media_id uuid NOT NULL REFERENCES media_reference(id),
    source text NOT NULL CHECK (source = 'personal'),
    status text NOT NULL CHECK (status IN ('processing', 'ready', 'failed', 'revoked', 'deleted')),
    revision integer NOT NULL CHECK (revision > 0),
    bundle_manifest jsonb,
    rights_version text NOT NULL,
    rights_accepted_at timestamptz NOT NULL,
    license_evidence_ref text NOT NULL,
    provider_resource_refs jsonb,
    error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_dh_avatar_owner_id ON dh_avatar(owner_account_id, id);
CREATE INDEX IF NOT EXISTS idx_dh_avatar_source_media ON dh_avatar(source_media_id);

-- 输出录制段（C105F-02 首用；随 F 迁移一次建齐）：一行 = 一次 start 命令（requestId 幂等由
-- dh_operation 承担，start_command_id 存最初 requestId）。
CREATE TABLE IF NOT EXISTS dh_recording (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    session_id uuid NOT NULL REFERENCES dh_session(id),
    start_command_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('recording', 'finalizing', 'ready', 'saving', 'saved',
        'failed', 'expired', 'deleted')),
    partial boolean NOT NULL DEFAULT false,
    start_program_ms bigint NOT NULL CHECK (start_program_ms BETWEEN 0 AND 9007199254740991),
    end_program_ms bigint CHECK (end_program_ms BETWEEN 0 AND 9007199254740991),
    manifest jsonb,
    size_bytes bigint CHECK (size_bytes IS NULL OR size_bytes >= 0),
    duration_ms bigint CHECK (duration_ms IS NULL OR duration_ms >= 0),
    expires_at timestamptz,
    asset_id uuid,
    subtitle_media_id uuid,
    error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_recording_start_command UNIQUE (session_id, start_command_id),
    CONSTRAINT uq_dh_recording_asset UNIQUE (asset_id)
);

-- 同会话最多一个活动段（recording/finalizing；K05 部分唯一）。
CREATE UNIQUE INDEX IF NOT EXISTS uq_dh_recording_session_active
    ON dh_recording(session_id)
    WHERE state IN ('recording', 'finalizing');

CREATE INDEX IF NOT EXISTS idx_dh_recording_owner_created ON dh_recording(owner_account_id, created_at);
CREATE INDEX IF NOT EXISTS idx_dh_recording_expires ON dh_recording(state, expires_at);

-- 资产附属（K13.5）：字幕 media 挂到既有 content_asset；三 owner/personal 校验在应用事务内。
CREATE TABLE IF NOT EXISTS dh_asset_attachment (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    asset_id uuid NOT NULL,
    media_reference_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind = 'subtitle'),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dh_asset_attachment UNIQUE (asset_id, kind),
    CONSTRAINT uq_dh_asset_attachment_media UNIQUE (media_reference_id)
);

CREATE INDEX IF NOT EXISTS idx_dh_asset_attachment_owner ON dh_asset_attachment(owner_account_id);
CREATE INDEX IF NOT EXISTS idx_dh_asset_attachment_media ON dh_asset_attachment(media_reference_id);

-- 派生对象清理登记（K05：先登记再写对象；UNIQUE 对 NULL object_ref 用确定空串句柄）。
CREATE TABLE IF NOT EXISTS dh_cleanup (
    id uuid PRIMARY KEY,
    owner_account_id text NOT NULL,
    resource_kind text NOT NULL,
    resource_id uuid NOT NULL,
    object_ref text,
    state text NOT NULL CHECK (state IN ('pending', 'deleting', 'deleted', 'retained', 'failed')),
    reason text,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error_code text,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dh_cleanup_resource_object
    ON dh_cleanup(resource_kind, resource_id, COALESCE(object_ref, ''));

CREATE INDEX IF NOT EXISTS idx_dh_cleanup_state_attempt
    ON dh_cleanup(state, next_attempt_at);

-- ---------- dh_guard_personal_write 扩展（F 表分支；同函数替换，V88 表触发器不变） ----------

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
        RETURN NEW;  -- dh_catalog/dh_admin_audit 不挂本触发器；防御性放行
    END IF;

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
        ELSIF TG_TABLE_NAME = 'dh_avatar' AND new_state IN ('failed', 'revoked', 'deleted')
           AND (old_state IS NULL OR old_state NOT IN ('failed', 'revoked', 'deleted')) THEN
            RETURN NEW;  -- 冻结期形象收尾（撤销/失败/清理完成；注销删除走 DELETE 不经触发器）
        ELSIF TG_TABLE_NAME = 'dh_recording'
           AND new_state IN ('saved', 'failed', 'expired', 'deleted')
           AND (old_state IS NULL
                OR old_state NOT IN ('saved', 'failed', 'expired', 'deleted')) THEN
            RETURN NEW;  -- 录制段收尾（saved 后 asset 独立仍允许，K09）
        ELSIF TG_TABLE_NAME = 'dh_cleanup'
           AND new_state IN ('deleting', 'deleted', 'retained', 'failed')
           AND (old_state IS NULL
                OR old_state IN ('pending', 'deleting', 'deleted', 'retained', 'failed')) THEN
            RETURN NEW;  -- 清理推进本身在冻结期必须继续（注销清理依赖；删除不倒退）
        END IF;
    END IF;

    RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
        USING ERRCODE = 'check_violation';
END;
$dh_guard$ LANGUAGE plpgsql;

-- 挂载 F 新表（幂等：DROP IF EXISTS + CREATE；V88 表触发器保持不动）。
DO $attach$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['dh_avatar', 'dh_recording', 'dh_asset_attachment', 'dh_cleanup']
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_dh_guard_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_dh_guard_%I BEFORE INSERT OR UPDATE ON %I '
                       'FOR EACH ROW EXECUTE FUNCTION dh_guard_personal_write()', t, t);
    END LOOP;
END
$attach$;
