-- Verification v2: preserve every decision and the exact task/evidence context used for it.
CREATE TABLE engagement_verification_run (
    id uuid PRIMARY KEY,
    submission_id uuid NOT NULL REFERENCES engagement_submission(id),
    run_number int NOT NULL,
    engine_version varchar(32) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('passed', 'failed', 'inconclusive')),
    task_context_snapshot jsonb NOT NULL,
    evidence_snapshot jsonb NOT NULL,
    checks jsonb NOT NULL,
    triggered_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (submission_id, run_number)
);

ALTER TABLE engagement_verification
    ADD COLUMN latest_run_id uuid REFERENCES engagement_verification_run(id),
    ADD COLUMN engine_version varchar(32) NOT NULL DEFAULT 'v1',
    ADD COLUMN task_context_snapshot jsonb,
    ADD COLUMN evidence_snapshot jsonb;

CREATE INDEX idx_verification_run_submission
    ON engagement_verification_run(submission_id, run_number DESC);

-- Freeze the task contract when an application becomes accepted. A DB trigger covers both
-- the synchronous and Saga acceptance paths, so no workflow can forget the snapshot.
ALTER TABLE task_application ADD COLUMN task_context_snapshot jsonb;

CREATE FUNCTION freeze_application_task_context() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'accepted' AND OLD.status <> 'accepted' AND NEW.task_context_snapshot IS NULL THEN
        SELECT jsonb_build_object(
            'taskId', t.id, 'taskVersion', t.version, 'title', t.title,
            'description', t.description, 'contentForm', t.content_form,
            'platform', t.platform, 'storeId', t.store_id,
            'applicationId', NEW.id, 'recommenderAccountId', NEW.recommender_account_id,
            'bountyCents', NEW.bounty_cents, 'acceptedAt', COALESCE(NEW.decided_at, now()),
            'requirements', COALESCE(tv.requirements, '{}'::jsonb)
        ) INTO NEW.task_context_snapshot
        FROM task t
        LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
        WHERE t.id = NEW.task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_freeze_application_task_context
    BEFORE UPDATE OF status ON task_application
    FOR EACH ROW EXECUTE FUNCTION freeze_application_task_context();

UPDATE task_application a SET task_context_snapshot = jsonb_build_object(
    'taskId', t.id, 'taskVersion', t.version, 'title', t.title,
    'description', t.description, 'contentForm', t.content_form,
    'platform', t.platform, 'storeId', t.store_id,
    'applicationId', a.id, 'recommenderAccountId', a.recommender_account_id,
    'bountyCents', a.bounty_cents, 'acceptedAt', a.decided_at,
    'requirements', COALESCE(tv.requirements, '{}'::jsonb),
    'backfilled', true
)
FROM task t LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
WHERE a.task_id = t.id AND a.status = 'accepted' AND a.task_context_snapshot IS NULL;
