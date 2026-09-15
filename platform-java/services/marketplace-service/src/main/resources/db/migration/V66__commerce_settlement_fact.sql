-- 任务书 #103 C103-15（§7.2）：已确认分账净额事实与每推荐官快照。
-- 只存 Finance 已完成的经确认事实；不覆盖订单原冻结分配列（consumer_order 三方金额仍为下单冻结口径）。
-- 幂等：order_id 主键 + operation_id 唯一；同 order 不同 source_hash 由查询层标 partial 进入核对。
CREATE TABLE IF NOT EXISTS commerce_settlement_fact (
    order_id uuid PRIMARY KEY REFERENCES consumer_order(id),
    operation_id varchar(128) NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    original_paid_cents bigint NOT NULL CHECK (original_paid_cents >= 0),
    refunded_before_split_cents bigint NOT NULL CHECK (refunded_before_split_cents >= 0),
    net_total_cents bigint NOT NULL CHECK (net_total_cents >= 0),
    merchant_cents bigint NOT NULL CHECK (merchant_cents >= 0),
    platform_cents bigint NOT NULL CHECK (platform_cents >= 0),
    recommender_total_cents bigint NOT NULL CHECK (recommender_total_cents >= 0),
    finance_completed_at timestamptz,
    source_hash varchar(64) NOT NULL,
    verified_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (merchant_cents + platform_cents + recommender_total_cents = net_total_cents)
);

CREATE INDEX IF NOT EXISTS idx_commerce_settlement_fact_org
    ON commerce_settlement_fact(organization_id);
CREATE INDEX IF NOT EXISTS idx_commerce_settlement_fact_verified
    ON commerce_settlement_fact(verified_at);

-- 每推荐官分配快照（Finance snapshot 来源）：每单分配之和等于父行 recommender_total_cents。
CREATE TABLE IF NOT EXISTS commerce_settlement_allocation_fact (
    order_id uuid NOT NULL REFERENCES commerce_settlement_fact(order_id),
    recommender_account_id uuid NOT NULL,
    amount_cents bigint NOT NULL CHECK (amount_cents >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, recommender_account_id)
);

CREATE INDEX IF NOT EXISTS idx_settlement_allocation_recommender
    ON commerce_settlement_allocation_fact(recommender_account_id);
