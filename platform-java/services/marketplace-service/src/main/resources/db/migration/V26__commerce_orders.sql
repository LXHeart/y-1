-- ADR-D07 commerce MVP: versioned packages, per-version inventory, immutable order snapshots,
-- redemption and post-redemption reviews. Payment/refund/split facts remain finance-owned.

CREATE TABLE commerce_package (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    store_id uuid,
    task_id uuid,
    owner_account_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('draft', 'published', 'off_sale')),
    current_version int NOT NULL DEFAULT 1 CHECK (current_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    off_sale_at timestamptz
);
CREATE INDEX idx_commerce_package_scope
    ON commerce_package(organization_id, store_id, status, updated_at DESC);

CREATE TABLE commerce_package_version (
    id uuid PRIMARY KEY,
    package_id uuid NOT NULL REFERENCES commerce_package(id),
    version int NOT NULL CHECK (version > 0),
    title text NOT NULL,
    description text,
    price_cents bigint NOT NULL CHECK (price_cents > 0),
    total_stock int NOT NULL CHECK (total_stock >= 0),
    fixed_redeem_deadline timestamptz,
    valid_days_after_purchase int CHECK (valid_days_after_purchase > 0),
    recommender_share_bps int NOT NULL CHECK (recommender_share_bps BETWEEN 0 AND 10000),
    platform_fee_bps int NOT NULL CHECK (platform_fee_bps BETWEEN 0 AND 10000),
    merchant_share_bps int NOT NULL CHECK (merchant_share_bps BETWEEN 0 AND 10000),
    policy_version varchar(64) NOT NULL DEFAULT 'commerce-v1',
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (package_id, version),
    CHECK (recommender_share_bps + platform_fee_bps + merchant_share_bps = 10000),
    CHECK (fixed_redeem_deadline IS NOT NULL OR valid_days_after_purchase IS NOT NULL)
);

CREATE TABLE commerce_package_inventory (
    package_version_id uuid PRIMARY KEY REFERENCES commerce_package_version(id),
    total_stock int NOT NULL CHECK (total_stock >= 0),
    remaining_stock int NOT NULL CHECK (remaining_stock >= 0 AND remaining_stock <= total_stock),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE consumer_order (
    id uuid PRIMARY KEY,
    consumer_account_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    store_id uuid,
    task_id uuid,
    package_id uuid NOT NULL REFERENCES commerce_package(id),
    package_version_id uuid NOT NULL REFERENCES commerce_package_version(id),
    package_version int NOT NULL,
    package_title text NOT NULL,
    recommender_account_id uuid,
    price_cents bigint NOT NULL CHECK (price_cents > 0),
    recommender_share_bps int NOT NULL,
    platform_fee_bps int NOT NULL,
    merchant_share_bps int NOT NULL,
    recommender_amount_cents bigint NOT NULL CHECK (recommender_amount_cents >= 0),
    platform_fee_cents bigint NOT NULL CHECK (platform_fee_cents >= 0),
    merchant_amount_cents bigint NOT NULL CHECK (merchant_amount_cents >= 0),
    policy_version varchar(64) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'pending_payment', 'paid', 'redeeming', 'redeemed',
        'refund_pending', 'refunded', 'payment_failed', 'cancelled')),
    redeem_code_hash varchar(64) NOT NULL UNIQUE,
    redeem_deadline timestamptz NOT NULL,
    payment_operation_id varchar(128) NOT NULL UNIQUE,
    refund_operation_id varchar(128) UNIQUE,
    split_operation_id varchar(128) UNIQUE,
    provider_ref text,
    last_error text,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    paid_at timestamptz,
    redeemed_at timestamptz,
    refunded_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (recommender_amount_cents + platform_fee_cents + merchant_amount_cents = price_cents),
    CHECK (recommender_share_bps + platform_fee_bps + merchant_share_bps = 10000)
);

CREATE INDEX idx_consumer_order_consumer
    ON consumer_order(consumer_account_id, created_at DESC);
CREATE INDEX idx_consumer_order_merchant
    ON consumer_order(organization_id, store_id, created_at DESC);
CREATE INDEX idx_consumer_order_dispatch
    ON consumer_order(status, updated_at)
    WHERE status IN ('pending_payment', 'redeeming', 'refund_pending');
CREATE INDEX idx_consumer_order_expiry
    ON consumer_order(redeem_deadline)
    WHERE status = 'paid';

CREATE TABLE consumer_review (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES consumer_order(id),
    consumer_account_id uuid NOT NULL,
    rating int NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment text,
    created_at timestamptz NOT NULL DEFAULT now()
);
