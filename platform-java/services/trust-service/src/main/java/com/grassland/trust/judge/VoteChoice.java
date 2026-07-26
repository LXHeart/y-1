package com.grassland.trust.judge;

/**
 * 审判投票选择（草场 Epic 6 Slice 6C / HLD §5.5）。db 值小写短串，幂等写入 {@code dispute_vote.vote}。
 *
 * <ul>
 *   <li>{@link #FOR_MERCHANT} — 判商家方胜诉（资金 release/reverse 给商家）。</li>
 *   <li>{@link #FOR_RECOMMENDER} — 判推荐官方胜诉（资金 capture 给推荐官）。</li>
 *   <li>{@link #ABSTAIN} — 弃权（不计入任一方多数）。</li>
 * </ul>
 */
public enum VoteChoice {
    FOR_MERCHANT("for_merchant"),
    FOR_RECOMMENDER("for_recommender"),
    ABSTAIN("abstain");

    private final String dbValue;

    VoteChoice(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 db 值解析；非法 → IllegalArgumentException（controller advice 转 400）。 */
    public static VoteChoice fromDb(String value) {
        if (value == null) {
            throw new IllegalArgumentException("vote is required");
        }
        for (VoteChoice c : values()) {
            if (c.dbValue.equalsIgnoreCase(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("invalid vote: " + value);
    }
}
