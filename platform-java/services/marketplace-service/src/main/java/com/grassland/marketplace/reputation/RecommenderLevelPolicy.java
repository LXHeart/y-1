package com.grassland.marketplace.reputation;

/**
 * 推荐官等级判定（PRD 五表格阈值）。纯函数 + 静态表，随查随算——等级不落库，
 * 因而 PRD「完成率降至阈值以下自动降级」天然成立（下一次查询即反映）。
 *
 * <table>
 *   <tr><td>Lv2</td><td>完成 ≥6，完成率 ≥80%</td></tr>
 *   <tr><td>Lv3</td><td>完成 ≥21，完成率 ≥85%，评分 ≥4.0</td></tr>
 *   <tr><td>Lv4</td><td>完成 ≥51，完成率 ≥90%，评分 ≥4.5</td></tr>
 *   <tr><td>Lv5</td><td><b>邀请制</b>——见下</td></tr>
 * </table>
 *
 * <p><b>Lv5 永不由本策略授予</b>：PRD 五写明「完成 ≥100，完成率 ≥95%，评分 ≥4.8，<b>邀请制</b>」，
 * 且 Lv5 绑定平台签约、保底任务量、审判官资格与 T+1 优先结算——这些是平台的商务决定，
 * 不能让算法达标就偷偷发出去。达标只是**必要条件**，授予入口留给后续的运营侧动作。
 */
public final class RecommenderLevelPolicy {

    /** 仅邀请制授予，本策略永不返回。 */
    public static final RecommenderLevel INVITE_ONLY_LEVEL = RecommenderLevel.LV5;

    private RecommenderLevelPolicy() {}

    public static RecommenderLevel levelOf(ReputationStats stats) {
        if (meets(stats, 51, 0.90, 4.5)) {
            return RecommenderLevel.LV4;
        }
        if (meets(stats, 21, 0.85, 4.0)) {
            return RecommenderLevel.LV3;
        }
        if (meets(stats, 6, 0.80, null)) {
            return RecommenderLevel.LV2;
        }
        return RecommenderLevel.LV1;
    }

    /** 三项门槛全达标才算数。{@code minScore} 为 null 表示该级不看评分（Lv2）；
     *  有评分要求而**尚无任何评分**时不达标——不能把「没被评过」当成好口碑。 */
    private static boolean meets(ReputationStats stats, int minCompleted, double minRate, Double minScore) {
        if (stats.completedCount() < minCompleted || stats.completionRate() < minRate) {
            return false;
        }
        if (minScore == null) {
            return true;
        }
        return stats.averageScore() != null && stats.averageScore() >= minScore;
    }
}
