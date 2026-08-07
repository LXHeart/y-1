-- Some AI endpoints charge credits without creating ai_run rows. Their uncertain HTTP outcomes
-- still need the same durable compensation worker, keyed by the original consume operation.
--
-- Rolling-upgrade contract: pre-V20 workers always parse run_id as a UUID. Keep that physical
-- column non-null and use consume_operation_id as a standalone sentinel. New workers read
-- actual_run_id, which remains null for standalone intents. The trigger also keeps old writers
-- compatible while actual_run_id retains the original FK / ON DELETE RESTRICT invariant.
ALTER TABLE ai_credit_compensation
    ADD COLUMN actual_run_id uuid,
    ADD COLUMN standalone boolean NOT NULL DEFAULT false;

UPDATE ai_credit_compensation
SET actual_run_id = run_id;

ALTER TABLE ai_credit_compensation
    DROP CONSTRAINT ai_credit_compensation_run_id_fkey,
    ADD CONSTRAINT fk_ai_credit_compensation_actual_run
        FOREIGN KEY (actual_run_id) REFERENCES ai_run(id) ON DELETE RESTRICT;

CREATE FUNCTION normalize_ai_credit_compensation_run_scope() RETURNS trigger AS $$
BEGIN
    IF NEW.standalone THEN
        NEW.actual_run_id := NULL;
        IF NEW.run_id IS DISTINCT FROM NEW.consume_operation_id THEN
            RAISE EXCEPTION 'standalone compensation run sentinel must equal consume operation'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NEW.actual_run_id IS NULL THEN
            NEW.actual_run_id := NEW.run_id;
        END IF;
        IF NEW.actual_run_id IS DISTINCT FROM NEW.run_id THEN
            RAISE EXCEPTION 'run-backed compensation scope mismatch'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ai_credit_compensation_run_scope
    BEFORE INSERT OR UPDATE OF run_id, actual_run_id, consume_operation_id, standalone
    ON ai_credit_compensation
    FOR EACH ROW EXECUTE FUNCTION normalize_ai_credit_compensation_run_scope();

ALTER TABLE ai_credit_compensation
    ADD CONSTRAINT chk_ai_credit_compensation_run_scope CHECK (
        (standalone AND actual_run_id IS NULL AND run_id = consume_operation_id)
        OR
        (NOT standalone AND actual_run_id IS NOT NULL AND run_id = actual_run_id)
    );
