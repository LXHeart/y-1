package com.grassland.marketplace.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.workflow.saga.SettlementReconciliationWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link SettlementReconciliationDispatcher} 派发逻辑（Slice 7B）。mockStatic WorkflowClient.start 以单测成功路径。 */
class SettlementReconciliationDispatcherTest {

    private final SettlementReconciliationRepository reconciliations = mock(SettlementReconciliationRepository.class);
    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final SettlementReconciliationProperties props =
            new SettlementReconciliationProperties(true, 2000L, 16, 30L, 10L);
    private final SettlementReconciliationDispatcher dispatcher =
            new SettlementReconciliationDispatcher(reconciliations, workflowClient, props);

    @Test
    void emptyBatchDoesNothing() {
        when(reconciliations.findDispatchable(16)).thenReturn(Flux.empty());

        dispatcher.dispatchBatch();

        verify(workflowClient, never()).newWorkflowStub(eq(SettlementReconciliationWorkflow.class), any(WorkflowOptions.class));
        verify(reconciliations, never()).markStarted(anyString(), anyInt(), any());
    }

    @Test
    void startsWorkflowAndMarksStarted() {
        SettlementReconciliation row = row("src-1", "dispute-1", "for_recommender");
        when(reconciliations.findDispatchable(16)).thenReturn(Flux.just(row));
        SettlementReconciliationWorkflow stub = mock(SettlementReconciliationWorkflow.class);
        when(workflowClient.newWorkflowStub(eq(SettlementReconciliationWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(stub);
        when(reconciliations.markStarted(anyString(), anyInt(), any())).thenReturn(Mono.just(true));

        // mockStatic 中和 WorkflowClient.start（void→noop），使派发器继续走到 markStarted。
        try (var ws = mockStatic(WorkflowClient.class)) {
            dispatcher.dispatchBatch();
        }

        verify(reconciliations).markStarted(eq("src-1"), eq(1), eq(Duration.ofSeconds(30)));
    }

    @Test
    void startFailureMarksStartFailedForRetry() {
        SettlementReconciliation row = row("src-2", "dispute-2", "for_merchant");
        when(reconciliations.findDispatchable(16)).thenReturn(Flux.just(row));
        when(workflowClient.newWorkflowStub(eq(SettlementReconciliationWorkflow.class), any(WorkflowOptions.class)))
                .thenThrow(new IllegalStateException("temporal unavailable"));
        when(reconciliations.markStartFailed(anyString(), anyInt(), any())).thenReturn(Mono.just(true));

        dispatcher.dispatchBatch();

        verify(reconciliations).markStartFailed(eq("src-2"), eq(1), eq(Duration.ofSeconds(10)));
        verify(reconciliations, never()).markStarted(anyString(), anyInt(), any());
    }

    private SettlementReconciliation row(String source, String dispute, String decision) {
        return new SettlementReconciliation(source, dispute, "app-" + source, "org-1", decision,
                "settlement-reconcile-" + dispute, "pending", null, 0,
                Instant.now(), null, null, Instant.now(), Instant.now());
    }
}
