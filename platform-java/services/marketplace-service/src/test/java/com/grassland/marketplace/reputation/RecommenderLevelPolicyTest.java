package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 等级判定阈值（PRD 五表格）。纯函数，锁住每一级的边界与「Lv5 绝不自动授予」。 */
class RecommenderLevelPolicyTest {

    /** 辅助方法：构造用于等级判定的测试数据。
     *  参数含义：(totalAccepted, completed, merchantCancelled, rejected, withdrawn, ratingCount, avgScore, avgResponseSeconds)
     *  为简化测试，大部分参数使用默认值 0。
     */
    private static ReputationStats stats(int completed, int merchantCancelled, int rejected, int withdrawn, Double avgScore) {
        // acceptedCount 设置为 completed + merchantCancelled + rejected + withdrawn，模拟"这些 engagement 都曾经 accept 过"
        // 但实际等级判定只看 completionRate（基于终态），acceptedCount 已不直接用于公式
        int accepted = completed + merchantCancelled + rejected + withdrawn;
        return new ReputationStats(accepted, completed, merchantCancelled, rejected, withdrawn,
                avgScore == null ? 0 : 10, avgScore, null);
    }

    /** 简化版：只传完成数，用于测试数量门槛（假设其他终态为 0） */
    private static ReputationStats statsJustCompleted(int completed, Double avgScore) {
        return stats(completed, 0, 0, 0, avgScore);
    }

    @Test
    @DisplayName("新人（零接单）为 Lv1，不因除零崩掉")
    void freshRecommenderIsLv1() {
        assertThat(RecommenderLevelPolicy.levelOf(ReputationStats.empty())).isEqualTo(RecommenderLevel.LV1);
        assertThat(ReputationStats.empty().completionRate()).isZero();
    }

    @Test
    @DisplayName("Lv2：完成 ≥6 且完成率 ≥80%，不看评分")
    void lv2Boundary() {
        // 6 完成，1 商家取消 → 终态 7，完成率 6/7 ≈ 85.7%，≥80%，达到 Lv2
        assertThat(RecommenderLevelPolicy.levelOf(stats(6, 1, 0, 0, null))).isEqualTo(RecommenderLevel.LV2);
        // 5 完成，终态 5 → 完成数不够 Lv2 的 6 单门槛
        assertThat(RecommenderLevelPolicy.levelOf(statsJustCompleted(5, null))).isEqualTo(RecommenderLevel.LV1);
        // 6 完成，4 商家取消 → 终态 10，完成率 6/10 = 60%，不够 Lv2 的 80%
        assertThat(RecommenderLevelPolicy.levelOf(stats(6, 4, 0, 0, null))).isEqualTo(RecommenderLevel.LV1);
    }

    @Test
    @DisplayName("Lv3：完成 ≥21、完成率 ≥85%、评分 ≥4.0——三项缺一不可")
    void lv3RequiresAllThree() {
        // 21 完成，3 商家取消 → 终态 24，完成率 21/24 = 87.5%，评分 4.0 → Lv3
        assertThat(RecommenderLevelPolicy.levelOf(stats(21, 3, 0, 0, 4.0))).isEqualTo(RecommenderLevel.LV3);
        // 同上，但评分 3.9 < 4.0 → 只到 Lv2
        assertThat(RecommenderLevelPolicy.levelOf(stats(21, 3, 0, 0, 3.9))).isEqualTo(RecommenderLevel.LV2);
        // 21 完成，4 商家取消 → 终态 25，完成率 21/25 = 84%，差 1 个点 → Lv2
        assertThat(RecommenderLevelPolicy.levelOf(stats(21, 4, 0, 0, 4.5))).isEqualTo(RecommenderLevel.LV2);
        // 21 完成，9 商家取消 → 终态 30，完成率 21/30 = 70%，连 Lv2 的 80% 都不够 → Lv1
        assertThat(RecommenderLevelPolicy.levelOf(stats(21, 9, 0, 0, 4.5))).isEqualTo(RecommenderLevel.LV1);
    }

    @Test
    @DisplayName("Lv4：完成 ≥51、完成率 ≥90%、评分 ≥4.5")
    void lv4Boundary() {
        // 51 完成，4 商家取消 → 终态 55，完成率 51/55 ≈ 92.7%，评分 4.5 → Lv4
        assertThat(RecommenderLevelPolicy.levelOf(stats(51, 4, 0, 0, 4.5))).isEqualTo(RecommenderLevel.LV4);
        // 同上，但评分 4.4 < 4.5 → 只到 Lv3
        assertThat(RecommenderLevelPolicy.levelOf(stats(51, 4, 0, 0, 4.4))).isEqualTo(RecommenderLevel.LV3);
    }

    /**
     * PRD 五写明 Lv5 是**邀请制**，且绑定平台签约/保底任务量/审判官资格/T+1 优先结算——
     * 商务决定不能由算法代劳。哪怕指标远超门槛也只到 Lv4。
     */
    @Test
    @DisplayName("Lv5 邀请制：指标全面超标也不自动授予")
    void lv5IsNeverAutoGranted() {
        // 1000 完成，0 其他终态 → 100% 完成率，1000 完成，评分 5.0 → 仍只到 Lv4
        assertThat(RecommenderLevelPolicy.levelOf(stats(1000, 0, 0, 0, 5.0))).isEqualTo(RecommenderLevel.LV4);
        assertThat(RecommenderLevelPolicy.INVITE_ONLY_LEVEL).isEqualTo(RecommenderLevel.LV5);
    }

    /** 「没人评过」不等于好口碑：无评分时 averageScore 为 null，需评分的等级一律不达标。 */
    @Test
    @DisplayName("无任何评分时封顶 Lv2")
    void noRatingsCapsAtLv2() {
        // 100 完成，0 其他终态 → 100% 完成率，100 完成 → 但无评分，封顶 Lv2
        assertThat(RecommenderLevelPolicy.levelOf(statsJustCompleted(100, null))).isEqualTo(RecommenderLevel.LV2);
    }
}
