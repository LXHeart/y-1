CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_app_users_admin_search_trgm
    ON app_users USING gin (
        (lower(coalesce(email, '') || ' ' || coalesce(display_name, '') || ' ' || id::text)) gin_trgm_ops
    );
