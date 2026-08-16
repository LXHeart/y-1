-- 任务书 #29+#30 #29：推荐官画像资料字段补齐 + 头像。
-- 新增列全部可空（存量画像无这些字段，PUT 整份覆盖时缺省落 null/空）。
-- IF NOT EXISTS：OutboxRepositoryIT 的迁移重放测试会在不同 schema 上重跑本历史，需保持幂等。
ALTER TABLE recommender_profile
    ADD COLUMN IF NOT EXISTS resident_city varchar(64),
    ADD COLUMN IF NOT EXISTS service_regions text[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS content_preferences text,
    ADD COLUMN IF NOT EXISTS work_samples jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS avatar_media_id uuid;
