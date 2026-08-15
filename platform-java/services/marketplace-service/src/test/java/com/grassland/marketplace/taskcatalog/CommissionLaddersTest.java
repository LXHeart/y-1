package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link CommissionLadders} 单元测试：从 accept 冻结快照解析 D-02 阶梯策略。 */
class CommissionLaddersTest {

    private static final String SNAPSHOT = """
            {"taskId":"11111111-1111-1111-1111-111111111111","taskVersion":2,
             "requirements":{"commissionLadder":{
               "policyVersion":"ladder-v1","metricKey":"video.views",
               "tiers":[{"threshold":1000,"payoutCents":500},{"threshold":10000,"payoutCents":1500}]}}}
            """;

    @Test
    void parsesFrozenLadderFromSnapshot() {
        CommissionLadder ladder = CommissionLadders.fromTaskContextSnapshot(SNAPSHOT);
        assertThat(ladder).isNotNull();
        assertThat(ladder.policyVersion()).isEqualTo("ladder-v1");
        assertThat(ladder.metricKey()).isEqualTo("video.views");
        assertThat(ladder.payoutFor(4_000)).isEqualTo(500L);
    }

    @Test
    void nullForFixedPayoutContractOrMissingSnapshot() {
        assertThat(CommissionLadders.fromTaskContextSnapshot(null)).isNull();
        assertThat(CommissionLadders.fromTaskContextSnapshot("  ")).isNull();
        assertThat(CommissionLadders.fromTaskContextSnapshot(
                "{\"taskId\":\"t\",\"requirements\":{}}")).isNull();
        assertThat(CommissionLadders.fromTaskContextSnapshot(
                "{\"taskId\":\"t\"}")).isNull();
        assertThat(CommissionLadders.fromTaskContextSnapshot(
                "{\"taskId\":\"t\",\"requirements\":{\"commissionLadder\":null}}")).isNull();
    }

    @Test
    void throwsOnCorruptSnapshotJson() {
        assertThatThrownBy(() -> CommissionLadders.fromTaskContextSnapshot("{not json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务快照损坏");
    }

    @Test
    void throwsOnInvalidFrozenLadder() {
        assertThatThrownBy(() -> CommissionLadders.fromTaskContextSnapshot(
                "{\"requirements\":{\"commissionLadder\":{\"policyVersion\":\"v\",\"metricKey\":\"m\",\"tiers\":[]}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要一个档位");
    }
}
