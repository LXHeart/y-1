-- Task #45: online content-safety lexicons and deterministic originality fingerprints.
CREATE TABLE IF NOT EXISTS content_safety_lexicon_version (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    label        varchar(64) NOT NULL UNIQUE,
    payload      jsonb NOT NULL,
    status       varchar(16) NOT NULL CHECK (status IN ('draft', 'active', 'retired')),
    created_by   text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_content_safety_lexicon_single_active
    ON content_safety_lexicon_version ((status)) WHERE status = 'active';

CREATE TABLE IF NOT EXISTS content_fingerprint (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_account_id text NOT NULL,
    task_id           text,
    application_id    text,
    platform          varchar(64),
    content_form      varchar(64),
    simhash           bigint NOT NULL,
    shingle_count     integer NOT NULL,
    source_kind       varchar(16) NOT NULL CHECK (source_kind IN ('generation', 'manual')),
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_content_fingerprint_task
    ON content_fingerprint(task_id, created_at DESC) WHERE task_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_content_fingerprint_owner
    ON content_fingerprint(owner_account_id, created_at DESC);
