-- #42 门店媒体库（PRD §2.1）：门店公开媒体绑定表，四类素材（门头/环境/菜单/宣传视频）。
-- 媒体引用指向 intelligence.media_reference，跨服务不建 FK；快照 mime_type/size_bytes（同 V17 先例）。
-- IF NOT EXISTS：OutboxRepositoryIT 的迁移重放测试会在不同 schema 上重跑本历史，需保持幂等。
CREATE TABLE IF NOT EXISTS store_media (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    store_id uuid NOT NULL,
    media_reference_id uuid NOT NULL,          -- 引用 intelligence.media_reference，跨服务无 FK
    kind varchar(16) NOT NULL CHECK (kind IN ('storefront','environment','menu','video')),
    position integer NOT NULL CHECK (position >= 1),
    mime_type text,                            -- 快照：媒体删除后仍可展示类型
    size_bytes bigint,
    uploaded_by_account_id uuid NOT NULL,      -- 绑定操作者
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (store_id, media_reference_id)
);
CREATE INDEX IF NOT EXISTS idx_store_media_store ON store_media(store_id, kind, position);
