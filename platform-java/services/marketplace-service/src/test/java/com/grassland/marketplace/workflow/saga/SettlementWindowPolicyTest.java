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

    // ---------- C11（业务审查 2026-09-07）：可支付时间 = max(T+N, 争议窗口下限) ----------

    @Test
    void disputeWindowFloorsShortLevelEntitlements() {
        // Lv5 T+1（86400s）< 48h 争议窗口 → 被抬到下限；等级不能单独缩短资金保护期。
        assertThat(SettlementWindowPolicy.windowSeconds(app(1), 86400, 172800)).isEqualTo(172800);
        // T+2（172800s）恰等于下限 → 不变。
        assertThat(SettlementWindowPolicy.windowSeconds(app(2), 86400, 172800)).isEqualTo(172800);
        // 更长权益照旧（下限不延长）。
        assertThat(SettlementWindowPolicy.windowSeconds(app(5), 86400, 172800)).isEqualTo(5 * 86400);
    }

    @Test
    void zeroDisputeWindowDisablesFloorForFastTests() {
        assertThat(SettlementWindowPolicy.windowSeconds(app(1), 2, 0)).isEqualTo(2);
    }

    private TaskApplication app(Integer settlementDelayDays) {
        return new TaskApplication("app", "task", "rec", "accepted", null, "merchant",
                null, null, null, null, 500L, null, null, null, null, null, null, null,
                1, 1L, settlementDelayDays, 0, false);
    }
}
