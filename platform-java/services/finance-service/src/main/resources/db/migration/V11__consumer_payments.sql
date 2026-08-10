-- ADR-D07 finance-owned consumer payment, refund and redemption split facts.

CREATE TABLE consumer_payment (
    id uuid PRIMARY KEY,
    order_ref text NOT NULL UNIQUE,
    consumer_account_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    currency varchar(8) NOT NULL DEFAULT 'CNY',
    channel varchar(32) NOT NULL,
    provider_ref text NOT NULL,
    operation_id varchar(128) NOT NULL UNIQUE,
    status varchar(24) NOT NULL CHECK (status IN ('succeeded', 'refunded')),
    created_at timestamptz NOT NULL DEFAULT now(),
    refunded_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consumer_payment_refund (
    id uuid PRIMARY KEY,
    order_ref text NOT NULL UNIQUE REFERENCES consumer_payment(order_ref),
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    reason text,
    operation_id varchar(128) NOT NULL UNIQUE,
    provider_ref text NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('succeeded')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consumer_payment_split (
    id uuid PRIMARY KEY,
    order_ref text NOT NULL UNIQUE REFERENCES consumer_payment(order_ref),
    recommender_account_id uuid,
    recommender_amount_cents bigint NOT NULL CHECK (recommender_amount_cents >= 0),
    merchant_amount_cents bigint NOT NULL CHECK (merchant_amount_cents >= 0),
    platform_fee_cents bigint NOT NULL CHECK (platform_fee_cents >= 0),
    operation_id varchar(128) NOT NULL UNIQUE,
    status varchar(24) NOT NULL CHECK (status IN ('processing', 'completed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);

CREATE INDEX idx_consumer_payment_org ON consumer_payment(organization_id, created_at DESC);
