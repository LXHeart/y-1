package com.grassland.trust.workflow;

/**
 * 一轮计票结果（草场 Epic 6 Slice 6C Phase C，workflow 用）。{@code decided}=是否过多数决；
 * {@code winner}={@code for_merchant}/{@code for_recommender}（decided 时非空）。
 */
public record TallyResult(int forMerchant, int forRecommender, int abstain, int panelSize,
                          boolean decided, String winner) {

    public static TallyResult undecided(int fm, int fr, int ab, int panelSize) {
        return new TallyResult(fm, fr, ab, panelSize, false, null);
    }

    public static TallyResult decided(int fm, int fr, int ab, int panelSize, String winner) {
        return new TallyResult(fm, fr, ab, panelSize, true, winner);
    }
}
