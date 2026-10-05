-- Preserve historical consent/version snapshots. Only new snapshots use schema v2.
ALTER TABLE task ADD CONSTRAINT task_delivery_days_positive
    CHECK (delivery_deadline_days IS NULL OR delivery_deadline_days > 0) NOT VALID;
-- NOT VALID retains legacy rows without fabricating a corrected contract; new writes are checked.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM task WHERE delivery_deadline_days <= 0) THEN
        RAISE WARNING 'Legacy task delivery_deadline_days <= 0 require explicit contract correction';
    ELSE
        ALTER TABLE task VALIDATE CONSTRAINT task_delivery_days_positive;
    END IF;
END $$;

CREATE FUNCTION task_contract_terms(t task) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object(
        'schemaVersion', 2, 'taskVersion', t.version,
        'bountyCents', t.bounty_cents, 'freebieDepositCents', COALESCE(t.freebie_deposit_cents, 0),
        'platform', t.platform, 'contentForm', t.content_form, 'requirements', t.requirements,
        'questionText', t.question_text, 'questionRef', t.question_ref,
        'commercePackageId', t.commerce_package_id, 'reviewRequired', t.review_required,
        'deliveryDeadlineDays', t.delivery_deadline_days, 'cancelPolicy', t.cancel_policy)
$$;

ALTER TABLE task_version ADD COLUMN contract_terms jsonb;
CREATE FUNCTION freeze_task_version_contract() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT task_contract_terms(t) INTO NEW.contract_terms FROM task t WHERE id = NEW.task_id;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_task_version_contract BEFORE INSERT ON task_version
    FOR EACH ROW EXECUTE FUNCTION freeze_task_version_contract();

CREATE OR REPLACE FUNCTION freeze_application_terms() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR (NEW.status = 'pending' AND OLD.status = 'reconsent') THEN
        SELECT t.version, task_contract_terms(t)
          INTO NEW.task_version_at_apply, NEW.terms_snapshot_json FROM task t WHERE id = NEW.task_id;
        NEW.reconsent_required := false;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.status = 'reconsent' AND OLD.status <> 'reconsent' THEN
            NEW.reconsent_required := true;
        END IF;
        IF NEW.status = 'cancelled' AND OLD.status <> 'cancelled' THEN
            NEW.cancelled_at := now();
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- New acceptances carry the same contract fields; existing accepted snapshots stay untouched.
CREATE OR REPLACE FUNCTION freeze_application_task_context() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'accepted' AND OLD.status <> 'accepted' AND NEW.task_context_snapshot IS NULL THEN
        SELECT jsonb_build_object(
            'taskId', t.id, 'taskVersion', t.version, 'title', t.title,
            'description', t.description, 'contentForm', t.content_form,
            'platform', t.platform, 'storeId', t.store_id,
            'applicationId', NEW.id, 'recommenderAccountId', NEW.recommender_account_id,
            'bountyCents', NEW.bounty_cents,
            'freebieDepositCents', COALESCE(NEW.freebie_deposit_cents, 0),
            'acceptedAt', COALESCE(NEW.decided_at, now()),
            'requirements', COALESCE(tv.requirements, '{}'::jsonb)
        )
        -- Preserve optional question shape and accepted amount overrides.
        || CASE
               WHEN COALESCE(tv.question_text, t.question_text) IS NULL THEN '{}'::jsonb
               ELSE jsonb_strip_nulls(jsonb_build_object(
                   'questionText', COALESCE(tv.question_text, t.question_text),
                   'questionRef', COALESCE(tv.question_ref, t.question_ref)))
           END
        || (task_contract_terms(t) - ARRAY['bountyCents','freebieDepositCents','requirements','questionText','questionRef']::text[])
        INTO NEW.task_context_snapshot
        FROM task t
        LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
        WHERE t.id = NEW.task_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
