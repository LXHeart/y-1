-- Analytics facts are append-only, idempotent inputs. Finance remains the authority for money movement.
CREATE TABLE marketing_attribution_event (
    id uuid PRIMARY KEY,
    idempotency_key varchar(160) NOT NULL UNIQUE,
    source_event_id varchar(160),
    source varchar(48) NOT NULL DEFAULT 'sandbox_manual',
    event_type varchar(24) NOT NULL CHECK (event_type IN ('exposure', 'interaction', 'conversion', 'conversion_refund')),
    organization_id uuid NOT NULL,
    store_id uuid,
    task_id uuid,
    recommender_account_id uuid,
    occurred_at timestamptz NOT NULL,
    value_cents bigint NOT NULL DEFAULT 0 CHECK (value_cents >= 0),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    recorded_by uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_marketing_event_scope ON marketing_attribution_event(organization_id, store_id, occurred_at DESC);
CREATE INDEX idx_marketing_event_type ON marketing_attribution_event(event_type, occurred_at DESC);
CREATE INDEX idx_marketing_event_recommender ON marketing_attribution_event(recommender_account_id, occurred_at DESC);
