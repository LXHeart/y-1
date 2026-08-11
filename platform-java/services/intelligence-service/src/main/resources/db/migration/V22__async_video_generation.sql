-- Provider-neutral async video generation jobs. Provider credentials remain in runtime secrets.
CREATE TABLE video_generation_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id text NOT NULL,
    organization_id text,
    idempotency_key varchar(160) NOT NULL,
    run_id uuid UNIQUE REFERENCES ai_run(id) ON DELETE RESTRICT,
    provider varchar(64) NOT NULL,
    model varchar(128) NOT NULL,
    provider_task_id varchar(256),
    status varchar(24) NOT NULL DEFAULT 'preparing'
        CHECK (status IN ('preparing', 'queued', 'submitted', 'processing',
                          'succeeded', 'failed', 'cancelled')),
    progress int NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    input_payload jsonb NOT NULL,
    result_url text,
    requested_duration_seconds int NOT NULL CHECK (requested_duration_seconds > 0),
    actual_duration_seconds int,
    aspect_ratio varchar(16) NOT NULL,
    pricing_version varchar(64) NOT NULL,
    unit_price_cents int NOT NULL CHECK (unit_price_cents >= 0),
    estimated_cost_cents int NOT NULL CHECK (estimated_cost_cents >= 0),
    actual_cost_cents int,
    budget_id uuid,
    budget_reservation_date date,
    reserved_cost_cents int,
    platform_model_version int NOT NULL,
    attempt_count int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    error_code varchar(64),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    UNIQUE(account_id, idempotency_key)
);

CREATE INDEX idx_video_generation_job_dispatch
    ON video_generation_job(next_attempt_at, created_at)
    WHERE status IN ('queued', 'submitted', 'processing');
CREATE INDEX idx_video_generation_job_account
    ON video_generation_job(account_id, created_at DESC);
