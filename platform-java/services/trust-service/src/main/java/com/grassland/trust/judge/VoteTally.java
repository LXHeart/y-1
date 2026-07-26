package com.grassland.trust.judge;

/**
 * 一轮投票计票结果（草场 Epic 6 Slice 6C）。多数决阈值 = 同方票数 × 2 &gt; {@code panelSize}
 * （7 官需 ≥4 同方；弃权/未投不计入任一方，但 {@code panelSize} 仍按面板满员计 → 鼓励满投）。
 *
 * <p>{@link #hasMajorityForMerchant()} / {@link #hasMajorityForRecommender()} 互斥；均 false = 平票/不足 → 重开或客服兜底。
 */
public record VoteTally(int forMerchant, int forRecommender, int abstain, int panelSize) {

    public int cast() {
        return forMerchant + forRecommender + abstain;
    }

    public boolean hasMajorityForMerchant() {
        return forMerchant * 2 > panelSize;
    }

    public boolean hasMajorityForRecommender() {
        return forRecommender * 2 > panelSize;
    }

    public boolean hasMajority() {
        return hasMajorityForMerchant() || hasMajorityForRecommender();
    }
}
