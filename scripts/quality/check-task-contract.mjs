import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'

const root = fileURLToPath(new URL('../../', import.meta.url))
const rules = JSON.parse(readFileSync(resolve(root, 'contracts/task-contract.v1.json'), 'utf8'))
const seen = new Set()
for (const field of rules.fields) {
  if (seen.has(field.key) || !field.label || !field.format || !field.source?.startsWith('/')
    || !Object.hasOwn(field, 'nullValue')) throw new Error(`Incomplete contract rule: ${field.key}`)
  seen.add(field.key)
  for (const column of [field.column, field.versionColumn, field.acceptColumn].filter(Boolean)) {
    if (!/^[a-z][a-z0-9_]*$/.test(column)) throw new Error(`Invalid contract column: ${column}`)
  }
}
const sqlRules = rules.fields.map(({ key, column, versionColumn, acceptColumn, nullValue }) =>
  ({ key, column, versionColumn, acceptColumn, nullValue }))
const expected = `-- Generated from contracts/task-contract.v1.json. Never rewrite an applied migration.
-- Field sources, including the accepted money override, share the canonical registry.
CREATE OR REPLACE FUNCTION task_contract_projection(t jsonb, v jsonb DEFAULT NULL, a jsonb DEFAULT NULL)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('schemaVersion', ${rules.schemaVersion}, 'taskVersion', t->'version')
        || jsonb_object_agg(f->>'key', COALESCE(
            NULLIF(a->(f->>'acceptColumn'), 'null'::jsonb),
            NULLIF(v->(f->>'versionColumn'), 'null'::jsonb),
            NULLIF(t->(f->>'column'), 'null'::jsonb), f->'nullValue'))
    FROM jsonb_array_elements($rules$${JSON.stringify(sqlRules)}$rules$::jsonb) f
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
`
const target = resolve(root, 'platform-java/services/marketplace-service/src/main/resources/db/migration', rules.migration)
if (process.argv.includes('--write') && !existsSync(target)) writeFileSync(target, expected)
if (!existsSync(target) || readFileSync(target, 'utf8') !== expected) {
  throw new Error('Contract SQL drift: add a NEW migration name to the registry, then run npm run contracts:check -- --write. Never edit a historical migration.')
}
console.log(`Contract registry and SQL agree: ${rules.fields.length} fields, schema ${rules.schemaVersion}`)
