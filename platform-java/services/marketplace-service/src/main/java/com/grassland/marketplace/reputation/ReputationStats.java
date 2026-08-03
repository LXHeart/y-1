package com.grassland.marketplace.reputation;

/**
 * 推荐官声誉指标（PRD 六「数据面板」）。**全部从既有事实派生**，不落冗余表。
 *
 * <ul>
 *   <li>{@code acceptedCount} 已接单数（application.status='accepted'，含已确认的）。</li>
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
 * <p><b>完成率公式（D-03 cancel 声誉消费者）</b>：完成数 / 终态数 = completed / (completed + merchantCancelled + rejected + withdrawn)。
 * 商家取消不应降低推荐官完成率——这是商家违约，推荐官无过错。拒绝/撤销是正常终态，
 * 计入分母确保完成率反映真实能力。无任何终态时为 0。
 */
public record ReputationStats(int acceptedCount, int completedCount,
                              int merchantCancelledCount, int rejectedCount, int withdrawnCount,
                              int ratingCount, Double averageScore, Double averageResponseSeconds) {

    public static ReputationStats empty() {
        return new ReputationStats(0, 0, 0, 0, 0, 0, null, null);
    }

    /**
     * 完成率 = 完成数 / 终态数（已完成 + 商家取消 + 被拒 + 撤销）。
     *
     * <p>商家取消（merchantCancelledCount）不计入推荐官责任——这是 D-03 定义的商家违约行为。
     * 若终态数为 0（从未有过任何终态 engagement），返回 0。
     */
    public double completionRate() {
        int terminalCount = completedCount + merchantCancelledCount + rejectedCount + withdrawnCount;
        return terminalCount == 0 ? 0.0 : (double) completedCount / terminalCount;
    }

    /** 终态总数（完成 + 商家取消 + 被拒 + 撤销），供外部展示用。 */
    public int terminalCount() {
        return completedCount + merchantCancelledCount + rejectedCount + withdrawnCount;
    }
}
