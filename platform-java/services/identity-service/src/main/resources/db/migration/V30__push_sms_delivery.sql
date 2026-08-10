-- Push/SMS endpoints, preferences and durable delivery outbox.
CREATE TABLE notification_endpoint (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL,
    channel varchar(16) NOT NULL CHECK (channel IN ('push', 'sms')),
    address text NOT NULL,
    provider varchar(32) NOT NULL,
    verified_at timestamptz NOT NULL,
    disabled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, channel, address)
);
CREATE INDEX idx_notification_endpoint_account_active
    ON notification_endpoint(account_id, channel) WHERE disabled_at IS NULL;

CREATE TABLE notification_preference (
    account_id uuid NOT NULL,
    category varchar(32) NOT NULL,
    push_enabled boolean NOT NULL DEFAULT true,
    sms_enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, category)
);

CREATE TABLE sms_verification_challenge (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    phone_e164 varchar(20) NOT NULL,
    code_hash varchar(64) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sms_verification_account ON sms_verification_challenge(account_id, created_at DESC);

CREATE TABLE external_delivery_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_event_id text NOT NULL,
    account_id uuid,
    channel varchar(16) NOT NULL CHECK (channel IN ('push', 'sms')),
    recipient text NOT NULL,
    provider varchar(32) NOT NULL,
    title text NOT NULL,
    body text NOT NULL,
    link_path text,
    category varchar(32),
    status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'dead')),
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    claimed_until timestamptz,
    claim_token uuid,
    last_error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    UNIQUE (source_event_id, channel, recipient)
);
CREATE INDEX idx_external_delivery_dispatch
    ON external_delivery_outbox(next_attempt_at, claimed_until, created_at) WHERE status = 'pending';

