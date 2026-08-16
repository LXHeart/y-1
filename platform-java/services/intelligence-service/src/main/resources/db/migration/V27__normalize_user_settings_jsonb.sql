-- settings 存量 jsonb 归一（数据治理，2026-08-16）。
--
-- user_settings.settings_json 是 legacy 无 schema 自由 jsonb：merge 会照抄任意未知键，值无类型/长度约束。
-- 写/读路径已由 intelligence SettingsSchemaGuard 白名单收敛；本迁移把存量行重建为同一白名单结构：
--   * 未知 section/键、非字符串值、空白串、超 512 字节的值 → 剔除；
--   * provider 枚举外的值（video: qwen/coze；homepage hotItems: 60s/alapi）→ 剔除该键
--     （新写入路径对枚举外值 400，此处存量保守降级为剔除）；
--   * baseUrl 的结构/私网校验不在 SQL 层做——执行侧（BYOK 改编/模型列表）有 ProviderUrlGuard 强校验。
-- 与 Java 白名单的已知偏差：model 的 128 字符上限只在写路径强制，存量 ≤512 的值保留。
-- 只处理 analysis/homepage；image-review-style 属图片分析域，由 StylePreferencesRepository 自管。
--
-- user_settings 现由 Java database-bootstrap 创建（JBE-02）；表不存在的库（如隔离 IT 的裸库）
-- 安全跳过（同 V2 口径）。UPDATE 表达式直接引用目标行旧值（settings_json 列名）。

CREATE FUNCTION normalize_user_settings_section(sec jsonb, allowed_keys text[]) RETURNS jsonb
    LANGUAGE sql IMMUTABLE AS $$
    SELECT COALESCE(jsonb_object_agg(e.key, e.value), '{}'::jsonb)
    FROM jsonb_each(COALESCE(sec, '{}'::jsonb)) e
    WHERE e.key = ANY (allowed_keys)
      AND jsonb_typeof(e.value) = 'string'
      AND btrim(e.value #>> '{}') <> ''
      AND octet_length(e.value #>> '{}') <= 512
$$;

DO $migration$
BEGIN
    IF to_regclass('public.user_settings') IS NULL THEN
        RETURN;
    END IF;

    EXECUTE $sql$
        UPDATE user_settings
        SET settings_json = jsonb_strip_nulls(jsonb_build_object(
                'features', jsonb_strip_nulls(jsonb_build_object(
                    'video', CASE
                        WHEN COALESCE(settings_json -> 'features' -> 'video' ->> 'provider', 'qwen') IN ('qwen', 'coze')
                            THEN normalize_user_settings_section(settings_json -> 'features' -> 'video',
                                ARRAY['provider', 'baseUrl', 'apiToken', 'apiKey', 'model'])
                        ELSE normalize_user_settings_section(settings_json -> 'features' -> 'video',
                                ARRAY['provider', 'baseUrl', 'apiToken', 'apiKey', 'model']) - 'provider'
                    END,
                    'image', NULLIF(normalize_user_settings_section(settings_json -> 'features' -> 'image',
                        ARRAY['baseUrl', 'apiKey', 'model']), '{}'::jsonb),
                    'article', NULLIF(normalize_user_settings_section(settings_json -> 'features' -> 'article',
                        ARRAY['baseUrl', 'apiKey', 'model']), '{}'::jsonb),
                    'imageGeneration', NULLIF(normalize_user_settings_section(settings_json -> 'features' -> 'imageGeneration',
                        ARRAY['baseUrl', 'apiKey', 'model']), '{}'::jsonb),
                    'videoProduction', NULLIF(normalize_user_settings_section(settings_json -> 'features' -> 'videoProduction',
                        ARRAY['baseUrl', 'apiKey', 'model']), '{}'::jsonb)
                )),
                'integrations', jsonb_strip_nulls(jsonb_build_object(
                    'feishu', NULLIF(normalize_user_settings_section(settings_json -> 'integrations' -> 'feishu',
                        ARRAY['appId', 'appSecret', 'folderToken']), '{}'::jsonb)
                ))
            )),
            updated_at = now()
        WHERE settings_type = 'analysis'
    $sql$;

    EXECUTE $sql$
        UPDATE user_settings
        SET settings_json = jsonb_strip_nulls(jsonb_build_object(
                'hotItems', CASE
                    WHEN COALESCE(settings_json -> 'hotItems' ->> 'provider', '60s') IN ('60s', 'alapi')
                        THEN normalize_user_settings_section(settings_json -> 'hotItems', ARRAY['provider', 'alapiToken'])
                    ELSE normalize_user_settings_section(settings_json -> 'hotItems', ARRAY['provider', 'alapiToken']) - 'provider'
                END
            )),
            updated_at = now()
        WHERE settings_type = 'homepage'
    $sql$;
END
$migration$;

DROP FUNCTION normalize_user_settings_section(jsonb, text[]);
