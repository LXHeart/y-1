-- 任务书 #103 C103-08（R03 / D103-06 / §7.3）：个人数据生命周期屏障。
-- intelligence_account_lifecycle 是注销协作的本地 gate 行：prepare 在行锁内复核活动任务后冻结；
-- 冻结/清理期间受保护表的【新建】被触发器拒绝（恢复内容/重新派发不允许；安全终态、财务回执、
-- 内容删空与 DELETE 清理继续放行）。owner 解析按 §7.3/§7.4：直连 account 列优先，子表沿父关系。
CREATE TABLE IF NOT EXISTS intelligence_account_lifecycle (
    account_id text PRIMARY KEY,
    closure_request_id uuid,
    state text NOT NULL DEFAULT 'active'
        CHECK (state IN ('active', 'frozen', 'erasing', 'erased')),
    revision bigint NOT NULL DEFAULT 0,
    frozen_at timestamptz,
    erased_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_intelligence_account_lifecycle_state
    ON intelligence_account_lifecycle(state, updated_at);

-- 任务书 #103 C103-09（§7.2）：分阶段清理 manifest / 步骤 / 对象登记。
-- manifest 只存计数、游标与状态，绝不复制被删除正文（本卡禁止项）。
CREATE TABLE IF NOT EXISTS personal_data_erasure_manifest (
    id uuid PRIMARY KEY,
    closure_request_id uuid NOT NULL UNIQUE,
    account_id text NOT NULL,
    policy_version text NOT NULL DEFAULT 'd10-v1',
    retention_until timestamptz,
    state text NOT NULL DEFAULT 'planned'
        CHECK (state IN ('planned', 'db_cleaning', 'objects_pending', 'verifying', 'completed', 'needs_review')),
    counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    verified_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_personal_data_erasure_manifest_state
    ON personal_data_erasure_manifest(state, created_at);

-- 每类资源一个步骤行：依赖序由代码维护（子先父后）；游标=已处理行数（单调），
-- 批次与步骤推进同事务提交，批次重跑幂等（DELETE 天然幂等，重复批次删 0 行）。
CREATE TABLE IF NOT EXISTS personal_data_erasure_step (
    manifest_id uuid NOT NULL,
    resource_kind text NOT NULL,
    batch_size int NOT NULL DEFAULT 200,
    state text NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'running', 'succeeded', 'retry_wait', 'needs_review')),
    deleted_count bigint NOT NULL DEFAULT 0,
    claim_token uuid,
    claimed_until timestamptz,
    attempts int NOT NULL DEFAULT 0,
    last_error_code text,
    next_retry_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (manifest_id, resource_kind)
);

-- 对象先登记后删（C103-09 登记、C103-10 物删）；删除后清空原 key 只留 hash。
CREATE TABLE IF NOT EXISTS personal_data_erasure_object (
    manifest_id uuid NOT NULL,
    object_key_hash text NOT NULL,
    object_key text,
    kind text NOT NULL,
    owner_scope text NOT NULL DEFAULT 'personal',
    retention_reason text,
    state text NOT NULL DEFAULT 'pending'
        CHECK (state IN ('pending', 'deleted', 'retained', 'failed')),
    attempts int NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (manifest_id, object_key_hash)
);

-- 通用写入防护：解析行 owner 后核对 gate 状态。
-- active/无行 → 放行；frozen/erasing/erased → RAISE（应用层映射 409 account_closure_barrier）。
CREATE OR REPLACE FUNCTION intelligence_guard_personal_write() RETURNS trigger AS $guard$
DECLARE
    owner text;
    gate_state text;
BEGIN
    -- 1) 直连 owner 列（§7.3 各表的归属列）。
    IF TG_TABLE_NAME IN ('ai_run', 'video_generation_job', 'creation_canvas_agent_plan',
                         'video_production_task', 'creation_context_snapshot',
                         'video_storyboard_workspace', 'creation_canvas_document',
                         'intelligence_style_preferences', 'ai_provider_preference',
                         'video_storyboard_variant', 'video_storyboard') THEN
        owner := NEW.account_id;
    ELSIF TG_TABLE_NAME IN ('speech_transcription', 'card_series_operation',
                            'creation_text_proposal', 'creation_visual_plan', 'creation_export',
                            'creation_wechat_draft_sync', 'creation_wechat_account',
                            'creation_wechat_media_mapping', 'creation_generation', 'ai_provider_key',
                            'creation_draft', 'creation_source_document', 'creation_visual_quote',
                            'creation_studio_apply', 'creation_visual_artifact',
                            'content_asset', 'content_fingerprint', 'content_asset_version') THEN
        owner := NEW.owner_account_id;
    ELSE
        -- 2) 子表沿父关系解析（§7.4：无 account 列的子表不能只按请求 accountId 一刀切）。
        -- 注意：必须用 IF/ELSIF 语句分支而非 CASE 表达式——CASE 的全部分支表达式在解析期统一校验
        -- NEW 字段名，任何表缺其他分支引用的列（如 plan_id）都会让整条语句解析失败。
        IF TG_TABLE_NAME = 'creation_draft_version' THEN
            owner := (SELECT d.owner_account_id FROM creation_draft d WHERE d.id = NEW.draft_id);
        ELSIF TG_TABLE_NAME = 'creation_visual_plan_revision' THEN
            owner := (SELECT p.owner_account_id FROM creation_visual_plan p WHERE p.id = NEW.plan_id);
        ELSIF TG_TABLE_NAME = 'creation_visual_item' THEN
            owner := (SELECT o.owner_account_id FROM card_series_operation o WHERE o.id = NEW.operation_id);
        ELSIF TG_TABLE_NAME = 'video_shot' THEN
            owner := (SELECT s.account_id FROM video_storyboard s WHERE s.id = NEW.storyboard_id);
        ELSIF TG_TABLE_NAME = 'video_shot_media_source' THEN
            owner := (SELECT s.account_id FROM video_storyboard s WHERE s.id = NEW.storyboard_id);
        ELSIF TG_TABLE_NAME = 'video_shot_take' THEN
            owner := (SELECT s.account_id FROM video_storyboard s
                      JOIN video_shot sh ON sh.storyboard_id = s.id WHERE sh.id = NEW.shot_id);
        ELSIF TG_TABLE_NAME = 'video_shot_audio' THEN
            owner := (SELECT s.account_id FROM video_storyboard s
                      JOIN video_shot sh ON sh.storyboard_id = s.id WHERE sh.id = NEW.shot_id);
        ELSIF TG_TABLE_NAME = 'content_asset_grant' THEN
            owner := (SELECT a.owner_account_id FROM content_asset a WHERE a.id = NEW.asset_id);
        ELSIF TG_TABLE_NAME = 'content_asset_embedding' THEN
            owner := (SELECT a.owner_account_id FROM content_asset a WHERE a.id = NEW.asset_id);
        ELSE
            owner := NULL;
        END IF;
    END IF;

    IF owner IS NULL THEN
        RETURN NEW;  -- 归属不可解析的行（组织/平台公共数据）不阻断；登记进 C19 注册表核对。
    END IF;

    SELECT state INTO gate_state FROM intelligence_account_lifecycle
     WHERE account_id = owner AND state IN ('frozen', 'erasing', 'erased');
    IF gate_state IS NOT NULL THEN
        RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$guard$ LANGUAGE plpgsql;

-- 直连 owner 表触发器（新建即防护）。
DO $install$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ai_run', 'video_generation_job', 'speech_transcription', 'card_series_operation',
        'creation_visual_item', 'creation_text_proposal', 'creation_visual_plan', 'creation_draft',
        'creation_canvas_agent_plan', 'video_production_task', 'creation_export',
        'creation_wechat_draft_sync', 'creation_wechat_account', 'creation_wechat_media_mapping',
        'creation_generation', 'creation_context_snapshot', 'intelligence_style_preferences',
        'ai_provider_preference', 'ai_provider_key', 'content_asset', 'content_fingerprint',
        'video_storyboard_workspace', 'creation_canvas_document'
    ]
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_guard_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_guard_%I BEFORE INSERT ON %I'
                       || ' FOR EACH ROW EXECUTE FUNCTION intelligence_guard_personal_write()', t, t);
    END LOOP;
END;
$install$;

-- 子表触发器（父关系解析）。
DO $install$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'creation_draft_version', 'creation_visual_plan_revision', 'creation_visual_item',
        'video_storyboard', 'video_shot', 'video_shot_media_source',
        'video_shot_take', 'video_shot_audio', 'content_asset_grant',
        'content_asset_embedding'
    ]
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_guard_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_guard_%I BEFORE INSERT ON %I'
                       || ' FOR EACH ROW EXECUTE FUNCTION intelligence_guard_personal_write()', t, t);
    END LOOP;
END;
$install$;
