-- 草场 finance-service 第二个 schema（Epic 4 Slice 4E：资金预留/释放 escrow）。
-- 纯增量，安全跑在已部署 V1 的 neon（finance_flyway_schema now at v2）。

-- 资金预留（escrow 预留/释放，HLD 5.4 escrow「预留/释放」）。reserve 扣账户余额，release 还原。
CREATE TABLE funds_reservation (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES finance_account(id),   -- 同库 FK（finance 自有）
    organization_id uuid NOT NULL,                              -- 冗余 org（鉴权/查询，与 account 一致）
    engagement_ref text,                                        -- 跨服务引用 marketplace application/engagement（无 FK，database-per-service）
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    status varchar(32) NOT NULL DEFAULT 'reserved',             -- reserved/released/captured
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(engagement_ref)                                      -- 幂等：一 engagement 一预留（Saga 重试安全；NULL 允许多行）
);
CREATE INDEX idx_reservation_account ON funds_reservation(account_id);
CREATE INDEX idx_reservation_org ON funds_reservation(organization_id);
