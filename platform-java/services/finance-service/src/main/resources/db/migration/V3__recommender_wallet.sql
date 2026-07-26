-- 草场 finance-service V3：推荐官钱包 + 流水 + 预留收款人。
--
-- 背景（资金链断口）：此前 reserve 扣商家余额、capture 只把预留翻成 captured 而**不动任何余额**，
-- 于是钱扣完就停在平台账上——推荐官既没有账户也拿不到钱，PRD 第八章的资金流没有闭环。
--
-- 本迁移补上收款侧：
-- - recommender_wallet：账号级钱包（推荐官不属于任何 org，故不能复用按 org 唯一的 finance_account）；
-- - wallet_ledger：append-only 流水，金额带符号（入账为正、提现/冲正为负），便于对账与 UI 展示；
-- - funds_reservation.payee_account_id：预留时就记下这笔钱将来该付给谁（由 marketplace 传入，
--   因为只有它知道该 engagement 对应哪个报名推荐官）。可空：V3 之前的存量预留没有收款人，
--   capture 时按「无分账对象」处理，不猜。

CREATE TABLE recommender_wallet (
    account_id uuid PRIMARY KEY,                                       -- 跨服务引用 app_users，无 FK（database-per-service）
    balance_cents bigint NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),-- 非负不变式，与 finance_account 同约定
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 钱包流水（append-only）。amount_cents 带符号：task_payout 为正，withdrawal / clawback 为负。
-- fee_cents 记录该笔入账被平台抽走多少（毛额 = amount_cents + fee_cents），便于向推荐官如实展示。
CREATE TABLE wallet_ledger (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    entry_type varchar(32) NOT NULL,          -- task_payout / withdrawal / clawback
    amount_cents bigint NOT NULL,             -- 带符号，允许负数（故不加 CHECK > 0）
    fee_cents bigint NOT NULL DEFAULT 0,      -- 平台抽成（仅 task_payout 有意义）
    engagement_ref text,                      -- 关联的履约（提现无）
    memo text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_ledger_account ON wallet_ledger(account_id, created_at DESC);

-- 收款人：capture 分账时把钱打给谁。可空（V3 前的存量预留无收款人）。
ALTER TABLE funds_reservation ADD COLUMN payee_account_id uuid;

-- 分账净额：capture 时实际打入钱包的金额（毛额 - 平台抽成）。冲正要按它原路扣回，
-- 不能拿 amount_cents 去扣——那样会把平台抽成也从推荐官身上扣走。
ALTER TABLE funds_reservation ADD COLUMN payout_cents bigint;
