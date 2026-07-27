package com.grassland.marketplace.reputation;

/**
 * 推荐官声誉指标（PRD 六「数据面板」）。**全部从既有事实派生**，不落冗余表。
 *
 * <ul>
 *   <li>{@code acceptedCount} 已接单数（application.status='accepted'，含已确认的）。</li>
 *   <li>{@code completedCount} 累计完成数（confirmed_at 非空 = 商家确认履约）。</li>
 *   <li>{@code averageScore} 平均评分；<b>无评分时为 null</b>，不是 0——「没人评过」与「都给 0 分」
 *       在等级判定上是两回事（0 会让人误以为口碑极差）。</li>
 *   <li>{@code averageResponseSeconds} 平均响应时长：decided_at（接单）→ 首次交付物提交；无样本为 null。</li>
 * </ul>
 *
 * <p>PRD 六的「平均曝光数据」<b>不做</b>——那要平台侧数据采集，不在本系统能证实的事实范围内。
 */
public record ReputationStats(int acceptedCount, int completedCount, int ratingCount,
                              Double averageScore, Double averageResponseSeconds) {

    public static ReputationStats empty() {
        return new ReputationStats(0, 0, 0, null, null);
    }

    /** 完成率 = 完成/已接单；无接单时为 0（而非除零/NaN）。 */
    public double completionRate() {
        return acceptedCount == 0 ? 0.0 : (double) completedCount / acceptedCount;
    }
}
