-- 任务书 #96 C96-03（§7 V57）：体验权益单——预约/兑现/失约的独立事实表，与押金字段解耦展示。
-- 全部 IF NOT EXISTS（迁移重放红线）。

CREATE TABLE IF NOT EXISTS experience_benefit (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES task_application(id),
    store_id uuid,
    items jsonb,
    booking_window timestamptz,
    fulfilled_at timestamptz,
    fulfilled_confirmed_by uuid,
    default_claimed_at timestamptz,
    default_deadline_at timestamptz,
    -- §7 列之外的两个补充列（本任务白名单内新增迁移）：失约主张的结局与时间——
    -- status=merchant_defaulted（到期未回应自动成立）/ denied（商家限时回应否认），
    -- 暂停区间 [default_claimed_at, default_resolved_at] 由此可查（TC96-012 计时顺延可解释）。
    default_resolved_at timestamptz,
    default_resolution varchar(32),
    status varchar(32) NOT NULL DEFAULT 'pending_booking',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (application_id)
);

-- 失约自动成立扫描（派发器 durable intent：已主张、未决、到期）。
CREATE INDEX IF NOT EXISTS idx_experience_benefit_default_scan
    ON experience_benefit(default_deadline_at)
    WHERE default_claimed_at IS NOT NULL AND default_resolved_at IS NULL AND status = 'booked';
