CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_content_asset_title_tags_trgm
    ON content_asset USING gin ((lower(coalesce(title, '') || ' ' || tags::text)) gin_trgm_ops);
