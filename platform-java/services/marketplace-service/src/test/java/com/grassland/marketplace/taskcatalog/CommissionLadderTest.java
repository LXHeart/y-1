package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionLadderTest {

    @Test
    void selectsHighestReachedFixedPayoutAndLeavesUnmetValueAtZero() {
        CommissionLadder ladder = new CommissionLadder("ladder-v1", "video.views", List.of(
                new CommissionLadder.Tier(1_000, 500),
                new CommissionLadder.Tier(10_000, 1_500),
                new CommissionLadder.Tier(50_000, 4_000)));

        assertThat(ladder.payoutFor(999)).isZero();
        assertThat(ladder.payoutFor(1_000)).isEqualTo(500);
        assertThat(ladder.payoutFor(49_999)).isEqualTo(1_500);
        assertThat(ladder.payoutFor(50_000)).isEqualTo(4_000);
        assertThat(ladder.maximumPayoutCents()).isEqualTo(4_000);
    }

    @Test
    void normalizesTierOrderAndRejectsAmbiguousPolicies() {
        CommissionLadder ladder = new CommissionLadder("ladder-v1", "likes", List.of(
                new CommissionLadder.Tier(10, 100), new CommissionLadder.Tier(0, 0)));
        assertThat(ladder.tiers().get(0).threshold()).isZero();

        assertThatThrownBy(() -> new CommissionLadder("v1", "views", List.of(
                new CommissionLadder.Tier(1, 100), new CommissionLadder.Tier(1, 200))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");
        assertThatThrownBy(() -> new CommissionLadder("v1", "views", List.of(
                new CommissionLadder.Tier(1, 200), new CommissionLadder.Tier(2, 100))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不下降");
    }

    @Test
    void rejectsInvalidMetricValuesAndEmptyPolicy() {
        assertThatThrownBy(() -> new CommissionLadder("v1", "", List.of(new CommissionLadder.Tier(0, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommissionLadder("v1", "views", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        CommissionLadder ladder = new CommissionLadder("v1", "views", List.of(new CommissionLadder.Tier(0, 1)));
        assertThatThrownBy(() -> ladder.payoutFor(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ladder.validateReserve(0L)).isInstanceOf(IllegalArgumentException.class);
        ladder.validateReserve(1L);
    }
}
