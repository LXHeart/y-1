-- Verified marketing attribution: provider campaign bindings, signed webhook inbox and durable alerts.
CREATE TABLE marketing_attribution_campaign (
    id uuid PRIMARY KEY,
    provider varchar(64) NOT NULL,
    external_campaign_id varchar(160) NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid,
    task_id uuid,
    recommender_account_id uuid,
    status varchar(16) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'disabled')),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, external_campaign_id)
);
CREATE INDEX idx_marketing_campaign_scope
    ON marketing_attribution_campaign(organization_id, store_id, status);

CREATE TABLE marketing_attribution_webhook_inbox (
    provider varchar(64) NOT NULL,
    event_id varchar(160) NOT NULL,
    payload_sha256 varchar(64) NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz,
    error_code varchar(64),
    PRIMARY KEY (provider, event_id)
);

CREATE TABLE marketing_attribution_alert (
    id uuid PRIMARY KEY,
    scope_key varchar(160) NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid,
    rule_code varchar(64) NOT NULL,
    severity varchar(16) NOT NULL CHECK (severity IN ('info', 'warning', 'critical')),
    status varchar(16) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'acknowledged', 'resolved')),
    message text NOT NULL,
    observed_value numeric,
    threshold_value numeric,
    last_observed_at timestamptz NOT NULL DEFAULT now(),
    acknowledged_at timestamptz,
    acknowledged_by uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scope_key, rule_code)
);
CREATE INDEX idx_marketing_alert_scope
    ON marketing_attribution_alert(organization_id, store_id, status, updated_at DESC);
