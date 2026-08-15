package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionSettlementPlanTest {

    @Test
    void freezesTheVerifiedMetricDecisionAgainstLaterPolicyChanges() {
        CommissionLadder ladder = new CommissionLadder("ladder-v1", "video.views", List.of(
                new CommissionLadder.Tier(1_000, 500),
                new CommissionLadder.Tier(10_000, 1_500)));

        CommissionSettlementPlan plan = CommissionSettlementPlan.evaluate(ladder, 4_000, 1_500);

        assertThat(plan.policyVersion()).isEqualTo("ladder-v1");
        assertThat(plan.metricValue()).isEqualTo(4_000);
        assertThat(plan.settlementAmountCents()).isEqualTo(500);
        assertThat(plan.reservedAmountCents()).isEqualTo(1_500);
    }

    @Test
    void rejectsAReserveThatCannotCoverTheHighestTier() {
        CommissionLadder ladder = new CommissionLadder("v1", "likes",
                List.of(new CommissionLadder.Tier(1, 100), new CommissionLadder.Tier(2, 200)));
        assertThatThrownBy(() -> CommissionSettlementPlan.evaluate(ladder, 2, 199))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("足额");
    }
}
