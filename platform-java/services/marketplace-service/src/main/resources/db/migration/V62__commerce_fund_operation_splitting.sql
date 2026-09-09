-- 审查修复 01（C01-A/C01-C）：资金闭环三件套。
-- 1) consumer_order 新增 'splitting' 状态：分账发出前的原子占位（单行条件 UPDATE 即互斥闸，
--    售后开案 / 暂扣确认 / 退款请求都以状态守卫与之竞争，不再靠「调用前再查一次」）。
-- 2) commerce_fund_operation：跨域资金操作持久化（审查 R01）——支付在途与取消后补偿退款
--    没有可承载的订单状态（cancelled 是终态），须独立操作行驱动重试与恢复。
-- 3) 部分索引：可分账行的调度扫描（C01-E：held 行在 SQL 里排除，防止占满批次）。

ALTER TABLE consumer_order DROP CONSTRAINT IF EXISTS consumer_order_status_check;
ALTER TABLE consumer_order ADD CONSTRAINT consumer_order_status_check CHECK (status IN (
    'pending_payment', 'paid', 'redeeming', 'redeemed', 'splitting',
    'refund_pending', 'partially_refunded', 'refunded', 'after_sales_disputed',
    'payment_failed', 'cancelled'));

CREATE TABLE IF NOT EXISTS commerce_fund_operation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES consumer_order(id),
    -- payment=下单支付在途（幂等键=payment_operation_id）；cancel_compensation=取消后支付已捕获的补偿退款
    operation_type varchar(32) NOT NULL CHECK (operation_type IN ('payment', 'cancel_compensation')),
    operation_id varchar(128) NOT NULL UNIQUE,
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    business_version int NOT NULL,
    -- in_flight=已发出/结果未知；succeeded=财务事实已落库收尾；failed=确定性拒绝（待重试）；
    -- needs_review=重试耗尽或口径冲突，进入对账待办（不变量 4：不能悄悄结束）
    status varchar(24) NOT NULL CHECK (status IN ('in_flight', 'succeeded', 'failed', 'needs_review')),
    provider_ref text,
    attempts int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner text,
    lease_expires_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 一单至多一行支付操作 / 一行补偿操作（operation_id 同时 UNIQUE，迟到执行者按操作 ID 收尾）
    UNIQUE (order_id, operation_type)
);

CREATE INDEX IF NOT EXISTS idx_commerce_fund_operation_due
    ON commerce_fund_operation(next_attempt_at)
    WHERE status IN ('in_flight', 'failed');

CREATE INDEX IF NOT EXISTS idx_consumer_order_split_due
    ON consumer_order(updated_at)
    WHERE status IN ('splitting')
       OR (status IN ('redeemed', 'partially_refunded') AND split_completed_at IS NULL);
