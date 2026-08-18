CREATE TABLE content_asset_embedding (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id uuid NOT NULL,
    asset_version integer NOT NULL CHECK (asset_version > 0),
    content_hash text NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','processing','ready','failed','stale')),
    provider varchar(64),
    model varchar(128),
    model_version_key varchar(128),
    algorithm_version varchar(128),
    dimensions integer,
    embedding jsonb,
    ai_run_id uuid,
    failure_code varchar(64),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claim_token uuid,
    claimed_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (status <> 'ready' OR (
        embedding IS NOT NULL AND jsonb_typeof(embedding) = 'array' AND dimensions > 0
        AND provider IS NOT NULL AND model IS NOT NULL AND model_version_key IS NOT NULL
        AND algorithm_version IS NOT NULL AND ai_run_id IS NOT NULL AND completed_at IS NOT NULL
    )),
    CHECK (status <> 'failed' OR failure_code IS NOT NULL),
    CHECK (status <> 'processing' OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)),
    CHECK (status <> 'pending' OR (claim_token IS NULL AND claimed_until IS NULL)),
    CHECK (status NOT IN ('ready','failed','stale') OR (claim_token IS NULL AND claimed_until IS NULL))
);

CREATE UNIQUE INDEX uq_content_asset_embedding_pending ON content_asset_embedding(asset_id, asset_version, content_hash) WHERE status IN ('pending','processing');
CREATE UNIQUE INDEX uq_content_asset_embedding_ready_model ON content_asset_embedding(asset_id, asset_version, content_hash, provider, model, model_version_key, algorithm_version) WHERE status = 'ready';
CREATE INDEX idx_content_asset_embedding_claim ON content_asset_embedding(next_attempt_at, created_at) WHERE status IN ('pending','failed');
