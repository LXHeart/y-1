package com.grassland.trust.dispute;

/**
 * 争议状态（草场 Epic 6 Slice 6C 审判）。<b>非 {@link #FINAL} 状态均阻塞结算</b>（settlement hold）。
 *
 * <ul>
 *   <li>{@link #OPEN} — 受理（6A 开争议）。</li>
 *   <li>{@link #VOTING} — 审判面板已分配、投票窗口开启（可多轮，轮次由 {@code round} 列记）。</li>
 *   <li>{@link #DECIDED} — 面板多数决出，在上诉窗口内（资金仍未处置）。</li>
 *   <li>{@link #APPEALED} — 当事方上诉，待客服终审。</li>
 *   <li>{@link #FINAL} — 终局（上诉窗口平淡结束 / 客服终审 / 手动 decide）——解除结算 hold。</li>
 * </ul>
 */
public enum DisputeCaseStatus {
    OPEN("open"),
    VOTING("voting"),
    DECIDED("decided"),
    APPEALED("appealed"),
    FINAL("final");

    private final String dbValue;

    DisputeCaseStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }
}
