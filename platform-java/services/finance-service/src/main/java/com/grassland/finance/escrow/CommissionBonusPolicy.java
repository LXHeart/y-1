package com.grassland.finance.escrow;

/** 平台补贴佣金计算。基点范围固定为 0-10000，结果向下取整。 */
public final class CommissionBonusPolicy {

    public static final int MAX_BPS = 10_000;

    private CommissionBonusPolicy() {}

    public static long calculateCents(long amountCents, int bonusBps) {
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be >= 1");
        }
        if (bonusBps < 0 || bonusBps > MAX_BPS) {
            throw new IllegalArgumentException("commissionBonusBps must be within [0, 10000]");
        }
        long whole = Math.multiplyExact(amountCents / MAX_BPS, bonusBps);
        long remainder = Math.multiplyExact(amountCents % MAX_BPS, bonusBps) / MAX_BPS;
        return Math.addExact(whole, remainder);
    }

    /** 预留阶段提前验证「基础净额 + 补贴」一定可存进 bigint。 */
    public static long validateTotalPayout(long amountCents, long bonusCents) {
        try {
            return Math.addExact(amountCents, bonusCents);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("commission bonus payout exceeds supported range", overflow);
        }
    }
}
