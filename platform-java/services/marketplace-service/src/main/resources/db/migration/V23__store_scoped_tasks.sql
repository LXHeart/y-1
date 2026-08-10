-- Store-scoped task resources. Null remains the legacy organization-level scope.
ALTER TABLE task ADD COLUMN store_id uuid;
CREATE INDEX idx_task_org_store ON task(organization_id, store_id);

ALTER TABLE task_version ADD COLUMN store_id uuid;
