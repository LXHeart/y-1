-- #38 account closure lifecycle. app_users is owned by database-bootstrap, so its
-- deletion timestamp is migrated here rather than relying on an identity-domain migration.
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS deleted_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_app_users_deleted_at
    ON app_users(deleted_at)
    WHERE deleted_at IS NOT NULL;
