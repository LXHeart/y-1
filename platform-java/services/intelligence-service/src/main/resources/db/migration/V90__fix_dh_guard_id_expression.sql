-- 任务书 #105G C105G-02：修复 dh_guard_personal_write UPDATE 分支的隐藏运算符解析错误。
-- V88/V89 版 new_id/old_id 表达式 to_jsonb(NEW)->>'session_id' || '/' || to_jsonb(NEW)->>'seq'
-- 中 || 与 ->> 同优先级且左结合，实际解析为 ((...text) || to_jsonb(NEW)) ->> 'seq'，
-- 即 text ->> unknown——非 active 态首次 UPDATE dh 表时计划失败（account 空间从未走到：
-- DELETE 不经本触发器，active 态提前 RETURN）。本迁移仅给 ->> 两侧补括号，其余与 V89 逐字一致。

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
            (to_jsonb(NEW)->>'session_id') || '/' || (to_jsonb(NEW)->>'seq'));
        old_id := COALESCE(to_jsonb(OLD)->>'id',
            (to_jsonb(OLD)->>'session_id') || '/' || (to_jsonb(OLD)->>'seq'));
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
