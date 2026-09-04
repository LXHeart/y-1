package com.grassland.trust.dispute;

/**
 * 争议状态（草场 Epic 6 Slice 6C 审判 + 任务书 #74 卡 B 质证期）。<b>非 {@link #FINAL} 状态均阻塞结算</b>
 * （settlement hold；partial unique {@code uniq_dispute_active_per_engagement WHERE status <> 'final'} 同口径）。
 *
 * <ul>
 *   <li>{@link #OPEN} — 受理。#74 卡 B 起仅存量兼容与 cs_direct / merchant_rejection 停留此态；
 *       读取时 open 视同 evidence（fresh 判定 open|evidence）。</li>
 *   <li>{@link #EVIDENCE} — 举证质证期（#74 卡 B，D1：48h，复用原开庭等待窗时隙）；双方质证完毕或窗口到点 → 开庭。</li>
 *   <li>{@link #VOTING} — 审判面板已分配、投票窗口开启（可多轮，轮次由 {@code round} 列记）。</li>
 *   <li>{@link #DECIDED} — 面板多数决出，在上诉窗口内（资金仍未处置）。</li>
 *   <li>{@link #APPEALED} — 当事方上诉，待客服终审。</li>
 *   <li>{@link #FINAL} — 终局——解除结算 hold。</li>
 * </ul>
 */
public enum DisputeCaseStatus {
    OPEN("open"),
    EVIDENCE("evidence"),
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

    /** 是否处于「受理/质证」pending 段（open 为存量兼容，视同 evidence）——fresh 开庭判定口径。 */
    public static boolean isEvidencePending(String status) {
        return OPEN.dbValue.equals(status) || EVIDENCE.dbValue.equals(status);
    }
}
