package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;

class SettlementWorkflowStarterTest {

    @Test
    void rejectsZeroLengthDay() {
        assertThatThrownBy(() -> new SettlementWorkflowStarter(
                org.mockito.Mockito.mock(WorkflowClient.class), 0, 172800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day-seconds");
    }

    @Test
    void rejectsNegativeLengthDay() {
        assertThatThrownBy(() -> new SettlementWorkflowStarter(
                org.mockito.Mockito.mock(WorkflowClient.class), -1, 172800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day-seconds");
    }

    @Test
    void acceptsZeroDisputeWindowForTests() {
        // C11：争议窗口下限 0=关闭（IT/e2e 拨快），非负即合法；负配静默归 0 不抛。
        new SettlementWorkflowStarter(org.mockito.Mockito.mock(WorkflowClient.class), 86400, 0);
        new SettlementWorkflowStarter(org.mockito.Mockito.mock(WorkflowClient.class), 86400, -5);
    }
}
