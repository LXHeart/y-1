-- Task #44: immutable generation lineage for video adaptations and recreation images.
CREATE TABLE IF NOT EXISTS creation_generation (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_account_id       text NOT NULL,
    organization_id        text,
    kind                   varchar(32) NOT NULL
                           CHECK (kind IN ('video_adaptation', 'asset_image', 'scene_image')),
    mode                   varchar(16) NOT NULL CHECK (mode IN ('independent', 'task')),
    context_snapshot_id    uuid,
    ai_run_id              uuid,
    resolution             varchar(16) NOT NULL CHECK (resolution IN ('platform', 'byok')),
    provider               varchar(64) NOT NULL,
    model                  varchar(128),
    platform_model_version integer,
    upstream_run_id        varchar(128),
    prompt_text            text NOT NULL,
    input_summary          jsonb NOT NULL DEFAULT '{}'::jsonb,
    input_media_ids        uuid[] NOT NULL DEFAULT '{}'::uuid[],
    result                 jsonb NOT NULL,
    result_media_ids       uuid[] NOT NULL DEFAULT '{}'::uuid[],
    created_at             timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_creation_generation_owner
    ON creation_generation(owner_account_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_creation_generation_owner_kind
    ON creation_generation(owner_account_id, kind, created_at DESC, id DESC);
