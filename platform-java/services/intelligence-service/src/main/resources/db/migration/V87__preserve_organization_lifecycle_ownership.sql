-- #104 C104-03（§3 D02 / §7.3）：组织 BYOK 生命周期屏障纠正。
-- R02：V86 对 ai_provider_key 使用历史 owner 解析——创建者注销（frozen/erasing/erased）后，
-- 现任合法组织管理员对原组织凭据的配置/轮换/停用被误拦。本迁移以窄例外放行「固定归属的既有组织凭据维护」：
--   * 仅 ai_provider_key 的 UPDATE；OLD/NEW organization_id 均非空且相等；id 与 owner_account_id 不变；
--   * 可变列固定为仓储实际 SET 的七列：base_url、model、encrypted_key、key_version、masked_hint、enabled、updated_at；
--     其中 enabled 只允许不变或 true→false（不得借例外重新启用）；
--   * 其余列（capability、provider、created_at 等）经 jsonb 行比较（去除可变列）必须逐列相同。
-- 操作者鉴权仍由组织控制器 + IdentityOrgAuthorizationClient 负责；触发器不代替鉴权。
-- 不放开 INSERT、不放开改 owner/org/id、不改变个人屏障与其余表防护（V86 语义原样保留）。
-- 组织键的越权形态 UPDATE（改归属/非白名单列/重新启用）一律拒绝，不落回 NEW.owner 门（防 active 接盘绕过）。
-- 增量替换函数逻辑，不改 V1~V86 已发布 checksum，不做 Flyway repair。
CREATE OR REPLACE FUNCTION intelligence_guard_personal_write() RETURNS trigger AS $guard$
DECLARE
    owner text;
    gate_state text;
    row_state text;
BEGIN
    IF TG_TABLE_NAME = 'ai_provider_key' AND TG_OP = 'UPDATE' AND OLD.organization_id IS NOT NULL THEN
        IF NEW.organization_id = OLD.organization_id
           AND NEW.id = OLD.id
           AND NEW.owner_account_id = OLD.owner_account_id
           AND (NEW.enabled = OLD.enabled OR (OLD.enabled AND NOT NEW.enabled))
           AND (to_jsonb(NEW) - ARRAY['base_url','model','encrypted_key','key_version','masked_hint','enabled','updated_at'])
               IS NOT DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['base_url','model','encrypted_key','key_version','masked_hint','enabled','updated_at'])
        THEN
            RETURN NEW;  -- 既有组织凭据维护窄例外（D02）
        END IF;
        RAISE EXCEPTION 'account_closure_barrier: organization key update outside maintenance whitelist'
            USING ERRCODE = 'check_violation';
    END IF;

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
