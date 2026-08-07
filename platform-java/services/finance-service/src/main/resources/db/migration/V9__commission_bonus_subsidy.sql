-- P2 推荐官等级权益：佣金加成由平台补贴，商家始终只承担原任务赏金。
-- bps 与 bonus 在 reserve 时冻结，后续等级/策略变化不得影响在途履约。
-- capture/reverse 的不可变 posting 使用 SUBSIDY_EXPENSE 账户记录平台费用及其回冲。
ALTER TABLE funds_reservation
    ADD COLUMN commission_bonus_bps integer NOT NULL DEFAULT 0
        CHECK (commission_bonus_bps BETWEEN 0 AND 10000),
    ADD COLUMN commission_bonus_cents bigint NOT NULL DEFAULT 0
        CHECK (commission_bonus_cents >= 0);

-- 钱包流水拆出平台补贴，避免把「到账 + 补贴」误解成商家毛赏金或负平台费。
ALTER TABLE wallet_ledger
    ADD COLUMN commission_bonus_cents bigint NOT NULL DEFAULT 0
        CHECK (commission_bonus_cents >= 0);
