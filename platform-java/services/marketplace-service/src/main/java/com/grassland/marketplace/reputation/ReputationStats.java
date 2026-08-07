package com.grassland.marketplace.reputation;

import java.time.Instant;

/**
 * 推荐官声誉指标（PRD 六「数据面板」）。**全部从既有事实派生**，不落冗余表。
 *
 * <ul>
 *   <li>{@code acceptedCount} 累计已接单数（accepted + 后续被商家取消的 refunded，含已确认的）。</li>
 *   <li>{@code completedCount} 累计完成数（confirmed_at 非空 = 商家确认履约）。</li>
 *   <li>{@code merchantCancelledCount} 商家取消数（status='refunded'，D-03 商家违约，不应计入推荐官责任）。</li>
 *   <li>{@code rejectedCount} 被拒数（status='rejected'，商家拒绝报名）。</li>
 *   <li>{@code withdrawnCount} 撤销数（status='withdrawn'，推荐官主动撤销）。</li>
 *   <li>{@code averageScore} 平均评分；<b>无评分时为 null</b>，不是 0——「没人评过」与「都给 0 分」
 *       在等级判定上是两回事（0 会让人误以为口碑极差）。</li>
 *   <li>{@code averageResponseSeconds} 平均响应时长：decided_at（接单）→ 首次交付物提交；无样本为 null。</li>
 * </ul>
 *
 * <p>PRD 六的「平均曝光数据」<b>不做</b>——那要平台侧数据采集，不在本系统能证实的事实范围内。
 *
 * <p><b>完成率公式</b>：完成数 / 推荐官责任范围内的已接单数。
 * 商家取消是商家违约，从分母排除；被拒和接单前撤销从未形成接单，也不进入分母。
 * 已接受但尚未完成的进行中任务进入分母。无责任接单时为 0。
 */
public record ReputationStats(int acceptedCount, int completedCount,
                              int merchantCancelledCount, int rejectedCount, int withdrawnCount,
                              int ratingCount, Double averageScore, Double averageResponseSeconds,
                              Instant lastActiveAt) {

    public ReputationStats(int acceptedCount, int completedCount,
                           int merchantCancelledCount, int rejectedCount, int withdrawnCount,
                           int ratingCount, Double averageScore, Double averageResponseSeconds) {
        this(acceptedCount, completedCount, merchantCancelledCount, rejectedCount, withdrawnCount,
                ratingCount, averageScore, averageResponseSeconds, Instant.now());
    }

    public static ReputationStats empty() {
        return new ReputationStats(0, 0, 0, 0, 0, 0, null, null, null);
    }

    /**
     * 完成率 = 完成数 / (累计已接单 - 商家取消)。
     */
    public double completionRate() {
        int accountableAccepted = Math.max(0, acceptedCount - merchantCancelledCount);
        return accountableAccepted == 0 ? 0.0 : (double) completedCount / accountableAccepted;
    }

    /** 终态总数（完成 + 商家取消 + 被拒 + 撤销），供外部展示用。 */
    public int terminalCount() {
        return completedCount + merchantCancelledCount + rejectedCount + withdrawnCount;
    }
}
