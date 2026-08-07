package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.taskcatalog.TaskApplication;
import org.junit.jupiter.api.Test;

class SettlementWindowPolicyTest {

    @Test
    void convertsAcceptanceSnapshotDaysToDeterministicWorkflowSeconds() {
        assertThat(SettlementWindowPolicy.windowSeconds(app(1), 5)).isEqualTo(5);
        assertThat(SettlementWindowPolicy.windowSeconds(app(2), 5)).isEqualTo(10);
    }

    @Test
    void historicalApplicationWithoutSnapshotUsesStandardTwoDays() {
        assertThat(SettlementWindowPolicy.windowSeconds(app(null), 5)).isEqualTo(10);
    }

    private TaskApplication app(Integer settlementDelayDays) {
        return new TaskApplication("app", "task", "rec", "accepted", null, "merchant",
                null, null, null, null, 500L, null, null, null, null, null, null, null,
                1, 1L, settlementDelayDays, 0, false);
    }
}
