-- 草场 marketplace V61：异常订单暂扣队列（任务书 #98 / D98-05）。
--
-- 自动标记规则（阈值可配）超标写 flagged 候选行；人工确认后才 held（结算挂起 + 原因 + 处理期限），
-- 解除/驳回同样人工；flagged 未确认不影响结算（自动标记永不直接碰钱）。
-- 幂等：同一 (order_id, rule) 至多一条未终态行——partial unique WHERE status IN ('flagged','held')
-- （V55 attribution_appeal / V59 exit_request 同款惯例）；确认/解除/驳回后同规则可再标记。

CREATE TABLE IF NOT EXISTS ops_order_hold(
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL,
  rule varchar(32) NOT NULL,
  reason text NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'flagged',
  flagged_at timestamptz NOT NULL DEFAULT now(),
  confirmed_by uuid,
  confirmed_at timestamptz,
  hold_deadline_at timestamptz,
  released_by uuid,
  released_at timestamptz,
  released_reason text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_ops_order_hold_status CHECK (status IN ('flagged', 'held', 'released', 'dismissed')),
  CONSTRAINT ck_ops_order_hold_rule CHECK (rule IN ('referral_refund_rate', 'appeal_burst', 'rlid_order_burst'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ops_order_hold_open
    ON ops_order_hold(order_id, rule) WHERE status IN ('flagged', 'held');

CREATE INDEX IF NOT EXISTS idx_ops_order_hold_queue
    ON ops_order_hold(status, flagged_at DESC);

CREATE INDEX IF NOT EXISTS idx_ops_order_hold_deadline
    ON ops_order_hold(hold_deadline_at) WHERE status = 'held';
