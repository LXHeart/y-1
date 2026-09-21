-- #103 review: serialize account preparation with content/job writes, including previously omitted resources.
-- Additive correction of V85; never change its published checksum.
CREATE OR REPLACE FUNCTION intelligence_guard_personal_write() RETURNS trigger AS $guard$
DECLARE
    owner text;
    gate_state text;
    row_state text;
BEGIN
    -- 1) 直连 owner 列（§7.3 各表的归属列）。
    IF TG_TABLE_NAME IN ('ai_run', 'video_generation_job', 'creation_canvas_agent_plan',
                         'video_production_task', 'creation_context_snapshot',
                         'video_storyboard_workspace', 'creation_canvas_document',
                         'intelligence_style_preferences', 'ai_provider_preference',
                         'video_storyboard_variant', 'video_storyboard') THEN
        owner := NEW.account_id;
    ELSIF TG_TABLE_NAME IN ('speech_transcription', 'card_series_operation', 'media_reference',
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

    -- Register missing accounts before locking: absence must not evade serialization.
    INSERT INTO intelligence_account_lifecycle(account_id) VALUES (owner)
        ON CONFLICT (account_id) DO NOTHING;
    SELECT state INTO gate_state FROM intelligence_account_lifecycle
        WHERE account_id = owner FOR SHARE;
    IF gate_state <> 'active' THEN
        IF TG_OP = 'UPDATE' THEN
            row_state := COALESCE(to_jsonb(NEW)->>'status', to_jsonb(NEW)->>'state', to_jsonb(NEW)->>'phase');
            -- Existing terminal callbacks and PII scrubbing may finish; reactivation may not.
            IF TG_TABLE_NAME = 'ai_run' AND row_state IN ('succeeded', 'failed', 'cancelled')
               AND (gate_state = 'frozen' OR to_jsonb(NEW)->>'failure_reason' IS NULL) THEN
                RETURN NEW;
            ELSIF TG_TABLE_NAME = 'video_generation_job' AND row_state IN ('succeeded', 'failed', 'cancelled')
               AND (gate_state = 'frozen' OR (to_jsonb(NEW)->'input_payload' = '{}'::jsonb
                    AND to_jsonb(NEW)->>'result_url' IS NULL AND to_jsonb(NEW)->>'error_message' IS NULL)) THEN
                RETURN NEW;
            ELSIF TG_TABLE_NAME = 'media_reference' AND row_state IN ('deleting', 'deleted')
               AND to_jsonb(NEW)->>'object_key' = to_jsonb(OLD)->>'object_key'
               AND to_jsonb(NEW)->>'owner_account_id' = to_jsonb(OLD)->>'owner_account_id' THEN
                RETURN NEW;
            ELSIF gate_state = 'frozen' AND TG_TABLE_NAME IN
                ('speech_transcription', 'card_series_operation', 'creation_visual_item',
                 'creation_text_proposal', 'creation_visual_plan', 'creation_canvas_agent_plan',
                 'video_production_task', 'video_shot_take', 'video_shot_audio',
                 'creation_export', 'creation_wechat_draft_sync', 'content_asset_embedding')
               AND row_state IN ('succeeded', 'failed', 'cancelled', 'ready', 'applied', 'clarify',
                                 'expired', 'not_required') THEN
                RETURN NEW;
            END IF;
        END IF;
        RAISE EXCEPTION 'account_closure_barrier: % is %', owner, gate_state
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$guard$ LANGUAGE plpgsql;


DO $install$
DECLARE t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ai_run',
        'video_generation_job',
        'speech_transcription',
        'card_series_operation',
        'creation_visual_item',
        'creation_text_proposal',
        'creation_visual_plan',
        'creation_draft',
        'creation_canvas_agent_plan',
        'video_production_task',
        'creation_export',
        'creation_wechat_draft_sync',
        'creation_wechat_account',
        'creation_wechat_media_mapping',
        'creation_generation',
        'creation_context_snapshot',
        'intelligence_style_preferences',
        'ai_provider_preference',
        'ai_provider_key',
        'content_asset',
        'content_fingerprint',
        'video_storyboard_workspace',
        'creation_canvas_document',
        'creation_draft_version',
        'creation_visual_plan_revision',
        'video_storyboard',
        'video_shot',
        'video_shot_media_source',
        'video_shot_take',
        'video_shot_audio',
        'content_asset_grant',
        'content_asset_embedding',
        'creation_source_document',
        'creation_visual_quote',
        'creation_studio_apply',
        'creation_visual_artifact',
        'content_asset_version',
        'video_storyboard_variant',
        'media_reference'
    ] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_guard_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_guard_%I BEFORE INSERT OR UPDATE ON %I'
                       || ' FOR EACH ROW EXECUTE FUNCTION intelligence_guard_personal_write()', t, t);
    END LOOP;
END;
$install$;
