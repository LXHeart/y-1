-- 草场 intelligence Slice 6：图片评价文案风格偏好持久化（首次让 intelligence 业务读写 DB）。
-- 独立 Flyway 历史：intelligence_flyway_schema（V1 已建 outbox + platform_model_config）。

-- 风格偏好（intelligence 专属表，account 维度）。legacy 存在通用 user_settings(settings_type='image-review-style')；
-- 迁入后由本服务全权读写，故新建独立表（匹配 marketplace/finance/trust「新表 + 表名隔离」范式），并一次性回填存量。
CREATE TABLE intelligence_style_preferences (
    account_id  text        PRIMARY KEY,                       -- Caller.accountId()（legacy user_id）
    preferences jsonb       NOT NULL DEFAULT '[]'::jsonb,      -- string[]（与 legacy settings_json.preferences 同形）
    version     integer     NOT NULL DEFAULT 1,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- 一次性回填 legacy image-review-style 行。intelligence 不创建/修改 legacy-owned user_settings：
-- 空库若尚未跑 legacy migration，跳过回填；后续 legacy 初始化仍能创建带完整 FK/CHECK/UNIQUE/索引的权威表。
-- 动态 SQL 是为了避免 PostgreSQL 在 DO 块编译期解析不存在的 user_settings。
DO $migration$
BEGIN
    IF to_regclass('public.user_settings') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO intelligence_style_preferences (account_id, preferences, version)
            SELECT user_id::text, COALESCE(settings_json->'preferences', '[]'::jsonb), 1
            FROM user_settings
            WHERE settings_type = 'image-review-style'
            ON CONFLICT (account_id) DO NOTHING
        $sql$;
    END IF;
END
$migration$;
