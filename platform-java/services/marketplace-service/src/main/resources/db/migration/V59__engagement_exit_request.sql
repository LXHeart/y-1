-- 草场 marketplace V59：协商退出申请表（任务书 #97 / D97-04）。
--
-- 双向对等：商家/推荐官任一方可发起，对方在响应窗（可配 marketplace.engagement.exit-response-hours，
-- 缺省 72h）内确认或拒绝；超时=申请失效（dispatcher 扫描置 expired）、合作按原履约继续；发起方可撤回
-- pending 申请。同一报名同时至多一个开放申请——partial unique WHERE status='pending'（V55
-- attribution_appeal / V56 engagement_extension 同款既有惯例，并发双开收敛为唯一冲突 → 调用方 409）。
-- 终态竞态（超时终结/商家取消/无责退出先到）：终态化路径同事务将残留 pending 置 cancelled（D97-05）。

CREATE TABLE IF NOT EXISTS exit_request(
  id uuid PRIMARY KEY,
  application_id uuid NOT NULL,
  task_id uuid NOT NULL,
  initiated_by_account_id uuid NOT NULL,
  initiated_role varchar(16) NOT NULL,        -- recommender | merchant
  reason text NOT NULL,
  status varchar(16) NOT NULL,                -- pending|confirmed|rejected|expired|cancelled
  respond_deadline_at timestamptz NOT NULL,
  responded_by_account_id uuid,
  responded_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_exit_request_role CHECK (initiated_role IN ('recommender', 'merchant')),
  CONSTRAINT ck_exit_request_status CHECK (status IN ('pending', 'confirmed', 'rejected', 'expired', 'cancelled'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_exit_request_open
    ON exit_request(application_id) WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_exit_request_expire_scan
    ON exit_request(respond_deadline_at) WHERE status = 'pending';
