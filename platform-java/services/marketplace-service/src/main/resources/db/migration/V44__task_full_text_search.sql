CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_task_title_description_trgm
    ON task USING gin ((lower(coalesce(title, '') || ' ' || coalesce(description, ''))) gin_trgm_ops);
