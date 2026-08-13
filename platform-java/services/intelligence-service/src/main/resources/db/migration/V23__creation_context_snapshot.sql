-- PRD §4.12: immutable context frozen when a task enters AI creation.
CREATE TABLE creation_context_snapshot (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id                 text NOT NULL,
    organization_id            text,
    task_id                    text NOT NULL,
    application_id             text NOT NULL,
    task_version               integer NOT NULL,
    platform_id                varchar(64) NOT NULL,
    content_form_id            varchar(64) NOT NULL,
    task_snapshot              jsonb NOT NULL,
    platform_rules_snapshot    jsonb NOT NULL,
    material_snapshot          jsonb NOT NULL,
    ai_config_snapshot         jsonb NOT NULL,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, application_id, task_version, platform_id, content_form_id)
);

CREATE INDEX idx_creation_context_account ON creation_context_snapshot(account_id, created_at DESC);
CREATE INDEX idx_creation_context_task ON creation_context_snapshot(task_id, application_id, task_version);

ALTER TABLE ai_run ADD COLUMN context_snapshot_id uuid REFERENCES creation_context_snapshot(id);
CREATE INDEX idx_ai_run_context_snapshot ON ai_run(context_snapshot_id) WHERE context_snapshot_id IS NOT NULL;

-- The application role must not be able to rewrite a context after it has been used.
CREATE FUNCTION reject_creation_context_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'creation context snapshots are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_creation_context_immutable
    BEFORE UPDATE ON creation_context_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_creation_context_mutation();
