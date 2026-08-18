-- #32 商家主体品牌资料（PRD §2.1）：组织级单行资料表，独立于 KYB merchant_profile 与门店 store_profile。
-- 媒体引用指向 intelligence.media_reference，跨服务不建 FK。
-- IF NOT EXISTS：OutboxRepositoryIT 的迁移重放测试会在不同 schema 上重跑本历史，需保持幂等。
CREATE TABLE IF NOT EXISTS organization_brand_profile (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    brand_name varchar(100),
    brand_logo_media_reference_id uuid,
    description varchar(2000),
    industry varchar(32),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
