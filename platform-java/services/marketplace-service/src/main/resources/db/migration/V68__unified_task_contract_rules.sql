-- Generated from contracts/task-contract.v1.json. Never rewrite an applied migration.
-- Field sources, including the accepted money override, share the canonical registry.
CREATE OR REPLACE FUNCTION task_contract_projection(t jsonb, v jsonb DEFAULT NULL, a jsonb DEFAULT NULL)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('schemaVersion', 3, 'taskVersion', t->'version')
        || jsonb_object_agg(f->>'key', COALESCE(
            NULLIF(a->(f->>'acceptColumn'), 'null'::jsonb),
            NULLIF(v->(f->>'versionColumn'), 'null'::jsonb),
            NULLIF(t->(f->>'column'), 'null'::jsonb), f->'nullValue'))
    FROM jsonb_array_elements($rules$[{"key":"bountyCents","column":"bounty_cents","acceptColumn":"bounty_cents","nullValue":null},{"key":"freebieDepositCents","column":"freebie_deposit_cents","acceptColumn":"freebie_deposit_cents","nullValue":0},{"key":"platform","column":"platform","nullValue":null},{"key":"contentForm","column":"content_form","nullValue":null},{"key":"requirements","column":"requirements","versionColumn":"requirements","nullValue":null},{"key":"questionText","column":"question_text","versionColumn":"question_text","nullValue":null},{"key":"questionRef","column":"question_ref","versionColumn":"question_ref","nullValue":null},{"key":"commercePackageId","column":"commerce_package_id","nullValue":null},{"key":"reviewRequired","column":"review_required","nullValue":false},{"key":"deliveryDeadlineDays","column":"delivery_deadline_days","nullValue":null},{"key":"cancelPolicy","column":"cancel_policy","nullValue":null}]$rules$::jsonb) f
$$;

CREATE OR REPLACE FUNCTION task_contract_terms(t task) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT task_contract_projection(to_jsonb(t))
$$;

-- V67 consent and version triggers already call task_contract_terms; old rows stay untouched.
CREATE OR REPLACE FUNCTION freeze_application_task_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'accepted' AND OLD.status <> 'accepted' AND NEW.task_context_snapshot IS NULL THEN
        SELECT task_contract_projection(to_jsonb(t), to_jsonb(tv), to_jsonb(NEW))
            || jsonb_build_object(
                'taskId', t.id, 'title', t.title, 'description', t.description, 'storeId', t.store_id,
                'applicationId', NEW.id, 'recommenderAccountId', NEW.recommender_account_id,
                'acceptedAt', COALESCE(NEW.decided_at, now()))
        INTO NEW.task_context_snapshot
        FROM task t LEFT JOIN task_version tv ON tv.task_id = t.id AND tv.version = t.version
        WHERE t.id = NEW.task_id;
    END IF;
    RETURN NEW;
END;
$$;
