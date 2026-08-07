package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;

class SettlementWorkflowStarterTest {

    @Test
    void rejectsZeroLengthDay() {
        assertThatThrownBy(() -> new SettlementWorkflowStarter(
                org.mockito.Mockito.mock(WorkflowClient.class), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day-seconds");
    }

    @Test
    void rejectsNegativeLengthDay() {
        assertThatThrownBy(() -> new SettlementWorkflowStarter(
                org.mockito.Mockito.mock(WorkflowClient.class), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("day-seconds");
    }
}
