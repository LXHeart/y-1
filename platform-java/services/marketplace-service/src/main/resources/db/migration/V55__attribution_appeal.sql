-- 业务审查 2026-09-07 C01（P0）：消费者不再直接提交最终分成——买家只能发起归因申诉，
-- 运营（客服/财务/风控角色）审核证据后由服务端按订单冻结的套餐版本规则纠错。
-- 一单同时至多一条待处理申诉（部分唯一索引），处理结果 append 审计可查。

CREATE TABLE IF NOT EXISTS consumer_order_attribution_appeal (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES consumer_order(id),
    consumer_account_id uuid NOT NULL,
    claimed_recommender_account_id uuid NOT NULL,
    reason text NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'open'
        CHECK (status IN ('open', 'applied', 'rejected')),
    resolution_note text,
    reviewed_by uuid,
    reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_attribution_appeal_open_per_order
    ON consumer_order_attribution_appeal(order_id)
    WHERE status = 'open';

CREATE INDEX IF NOT EXISTS idx_attribution_appeal_status
    ON consumer_order_attribution_appeal(status, created_at DESC);
