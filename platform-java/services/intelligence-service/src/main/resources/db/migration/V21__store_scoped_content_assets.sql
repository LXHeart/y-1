-- Store-scoped merchant content assets. Null remains organization-level/personal/public scope.
ALTER TABLE content_asset ADD COLUMN store_id uuid;
CREATE INDEX idx_content_asset_org_store
    ON content_asset(organization_id, store_id, category, created_at DESC)
    WHERE library_type = 'merchant' AND deleted_at IS NULL;

ALTER TABLE content_asset_version ADD COLUMN store_id uuid;
