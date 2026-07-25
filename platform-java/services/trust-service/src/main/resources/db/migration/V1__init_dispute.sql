-- 草场 trust-service 首个 schema（Epic 6 Slice 6A：争议受理）。
-- 独立 Flyway 历史表 trust_flyway_schema（与 identity/marketplace/finance 历史隔离，共用 neon public schema）。

-- 争议案件（dispute-case，HLD 5.5）。engagement_ref 跨服务引用 marketplace application/engagement（database-per-service 无 FK）。
-- 本 slice 极简状态机 open/decided；审判（投票/平票/上诉）状态机留后续 slice。
CREATE TABLE dispute_case (
    id uuid PRIMARY KEY,
    engagement_ref text NOT NULL,               -- marketplace applicationId（engagement_ref）
    organization_id uuid NOT NULL,              -- 冗余，鉴权/查询
    opened_by_account_id uuid NOT NULL,         -- 开争议的 merchant/recommender（caller）
    opened_by_role varchar(32) NOT NULL,        -- merchant/recommender
    status varchar(32) NOT NULL DEFAULT 'open', -- open/decided
    reason text,
    decision varchar(32),                       -- in_merchant_favor/in_recommender_favor（手动 decide；本 slice 不动钱）
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
-- 每 engagement 至多一个 open 争议（OpenDispute 幂等；partial unique index 允许历史 decided 多条）
CREATE UNIQUE INDEX uniq_dispute_open_per_engagement ON dispute_case(engagement_ref) WHERE status='open';
CREATE INDEX idx_dispute_engagement ON dispute_case(engagement_ref);

-- trust outbox（复刻 marketplace/finance，Kafka 发布器留后续）。
CREATE TABLE trust_outbox (
    id bigserial PRIMARY KEY,
    event_id text NOT NULL,
    event_type text NOT NULL,
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(event_id)
);
