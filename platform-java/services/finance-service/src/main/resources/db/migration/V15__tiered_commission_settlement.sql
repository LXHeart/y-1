-- D-02：阶梯佣金预留最高档，结算时捕获实际档位并释放未使用部分。
-- 两个字段只在 captured 后写入，保留 reserve 上限与最终结算事实的区别。
ALTER TABLE funds_reservation
    ADD COLUMN settlement_amount_cents bigint
        CHECK (settlement_amount_cents IS NULL OR settlement_amount_cents > 0),
    ADD COLUMN settlement_commission_bonus_cents bigint NOT NULL DEFAULT 0
        CHECK (settlement_commission_bonus_cents >= 0);
