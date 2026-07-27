package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 等级判定阈值（PRD 五表格）。纯函数，锁住每一级的边界与「Lv5 绝不自动授予」。 */
class RecommenderLevelPolicyTest {

    private static ReputationStats stats(int accepted, int completed, Double avgScore) {
        return new ReputationStats(accepted, completed, avgScore == null ? 0 : 10, avgScore, null);
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
        assertThat(RecommenderLevelPolicy.levelOf(stats(7, 6, null))).isEqualTo(RecommenderLevel.LV2);  // 6/7≈85.7%
        assertThat(RecommenderLevelPolicy.levelOf(stats(6, 5, null))).isEqualTo(RecommenderLevel.LV1);  // 完成数差 1
        assertThat(RecommenderLevelPolicy.levelOf(stats(10, 6, null))).isEqualTo(RecommenderLevel.LV1); // 60% 完成率不够
    }

    @Test
    @DisplayName("Lv3：完成 ≥21、完成率 ≥85%、评分 ≥4.0——三项缺一不可")
    void lv3RequiresAllThree() {
        assertThat(RecommenderLevelPolicy.levelOf(stats(24, 21, 4.0))).isEqualTo(RecommenderLevel.LV3);  // 87.5%
        assertThat(RecommenderLevelPolicy.levelOf(stats(24, 21, 3.9))).isEqualTo(RecommenderLevel.LV2);  // 评分差 0.1
        assertThat(RecommenderLevelPolicy.levelOf(stats(25, 21, 4.5))).isEqualTo(RecommenderLevel.LV2);  // 84% 差 1 个点
        assertThat(RecommenderLevelPolicy.levelOf(stats(30, 21, 4.5))).isEqualTo(RecommenderLevel.LV1);  // 70% 连 Lv2 都不够
    }

    @Test
    @DisplayName("Lv4：完成 ≥51、完成率 ≥90%、评分 ≥4.5")
    void lv4Boundary() {
        assertThat(RecommenderLevelPolicy.levelOf(stats(55, 51, 4.5))).isEqualTo(RecommenderLevel.LV4);  // 92.7%
        assertThat(RecommenderLevelPolicy.levelOf(stats(55, 51, 4.4))).isEqualTo(RecommenderLevel.LV3);
    }

    /**
     * PRD 五写明 Lv5 是**邀请制**，且绑定平台签约/保底任务量/审判官资格/T+1 优先结算——
     * 商务决定不能由算法代劳。哪怕指标远超门槛也只到 Lv4。
     */
    @Test
    @DisplayName("Lv5 邀请制：指标全面超标也不自动授予")
    void lv5IsNeverAutoGranted() {
        assertThat(RecommenderLevelPolicy.levelOf(stats(1000, 1000, 5.0))).isEqualTo(RecommenderLevel.LV4);
        assertThat(RecommenderLevelPolicy.INVITE_ONLY_LEVEL).isEqualTo(RecommenderLevel.LV5);
    }

    /** 「没人评过」不等于好口碑：无评分时 averageScore 为 null，需评分的等级一律不达标。 */
    @Test
    @DisplayName("无任何评分时封顶 Lv2")
    void noRatingsCapsAtLv2() {
        assertThat(RecommenderLevelPolicy.levelOf(stats(100, 100, null))).isEqualTo(RecommenderLevel.LV2);
    }
}
