package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link SettlementReconciliationWorkflowImpl} 编排 replay（Slice 7B）。用 TestWorkflowEnvironment + FakeActivity。 */
class SettlementReconciliationWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(ApplicationReservationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(SettlementReconciliationWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void delegatesReconciledOutcome() {
        activity.outcome = SettlementReconciliationWorkflow.ReconciliationOutcome.reconciled();

        SettlementReconciliationWorkflow.ReconciliationOutcome result = run();

        assertThat(result.status()).isEqualTo("reconciled");
    }

    @Test
    void delegatesBlockedOutcome() {
        activity.outcome = SettlementReconciliationWorkflow.ReconciliationOutcome.blocked("finance_conflict");

        SettlementReconciliationWorkflow.ReconciliationOutcome result = run();

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reason()).isEqualTo("finance_conflict");
    }

    private SettlementReconciliationWorkflow.ReconciliationOutcome run() {
        WorkflowClient client = env.getWorkflowClient();
        SettlementReconciliationWorkflow stub = client.newWorkflowStub(
                SettlementReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                        .build());
        return stub.reconcile(new SettlementReconciliationWorkflow.ReconciliationInput(
                "src-1", "dispute-1", "app-1", "for_recommender"));
    }

    static final class FakeActivity implements SettlementReconciliationActivity {
        SettlementReconciliationWorkflow.ReconciliationOutcome outcome =
                SettlementReconciliationWorkflow.ReconciliationOutcome.reconciled();

        @Override
        public SettlementReconciliationWorkflow.ReconciliationOutcome reconcile(
                SettlementReconciliationWorkflow.ReconciliationInput input) {
            return outcome;
        }
    }
}
