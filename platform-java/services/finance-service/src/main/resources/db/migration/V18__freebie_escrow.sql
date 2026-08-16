-- 草场 finance V18：霸王餐押金托管（ADR-D12 / 任务书 #22）。
--
-- 资金方向与既有 funds_reservation（商家出资）相反：推荐官报名被接受时从**推荐官钱包**预付押金进托管；
-- 达标 → 全额退还推荐官（FREEBIE_REFUND，无平台费）；未达标/商家获判 → 补偿商家 org 账户（FREEBIE_COMPENSATE）。
--
-- 刻意独立建表而非复用 funds_reservation：其 org 出资 / payee 分账 / 佣金补贴列在 freebie 语义下不成立
-- （出资方是推荐官不是 org），复用需大量可空列与分支。生命周期守卫（reserved → refunded|compensated）镜像既有。

CREATE TABLE freebie_escrow (
    id uuid PRIMARY KEY,
    engagement_ref text NOT NULL UNIQUE,          -- 跨服务引用 marketplace application（幂等域，与 funds_reservation 同约定）
    recommender_account_id uuid NOT NULL,         -- 出资方（押金从此钱包扣、退款回此钱包）
    task_owner_account_id uuid,                   -- 商家发布者账号（Compensated 通知双方用；可空防御）
    organization_id uuid NOT NULL,                -- 补偿入账的商家 org（compensate 时 CREDIT ESCROW:{orgId}）
    amount_cents bigint NOT NULL CHECK (amount_cents > 0),
    status varchar(16) NOT NULL,                  -- reserved / refunded / compensated
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_freebie_escrow_org ON freebie_escrow(organization_id, created_at DESC);
