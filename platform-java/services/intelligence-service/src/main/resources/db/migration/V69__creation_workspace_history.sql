-- ADD COLUMN IF NOT EXISTS：与全仓迁移重放测试约定一致，重放安全。
ALTER TABLE creation_draft_version
    ADD COLUMN IF NOT EXISTS workspace_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS result_asset_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS run_ids jsonb NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX IF NOT EXISTS idx_creation_draft_owner_updated_id
    ON creation_draft (owner_account_id, updated_at DESC, id DESC)
    WHERE deleted_at IS NULL;
