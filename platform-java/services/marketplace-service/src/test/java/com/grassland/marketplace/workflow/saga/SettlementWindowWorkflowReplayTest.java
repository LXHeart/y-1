package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 用 {@link TestWorkflowEnvironment}（replay 引擎）验证 {@link SettlementWindowWorkflowImpl} 编排（草场 Epic 5 Slice 5A）：
 * 窗口 Timer 到期 → captureSettlement activity（settled/held/aborted）。纯单元测试，不启动 Spring 上下文。
 */
class SettlementWindowWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(ApplicationReservationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(SettlementWindowWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void windowThenCaptureSettles() {
        activity.outcome = SettlementOutcome.settled();

        SettlementOutcome r = run(input(0));

        assertThat(r.status()).isEqualTo("settled");
        assertThat(activity.captured).isTrue();
    }

    @Test
    void captureHeldOnDispute() {
        activity.outcome = SettlementOutcome.held("open_dispute");

        SettlementOutcome r = run(input(0));

        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("open_dispute");
    }

    @Test
    void captureAbortedWhenNotConfirmed() {
        activity.outcome = SettlementOutcome.aborted();

        SettlementOutcome r = run(input(0));

        assertThat(r.status()).isEqualTo("aborted");
    }

    private SettlementOutcome run(SettlementInput input) {
        WorkflowClient client = env.getWorkflowClient();
        SettlementWindowWorkflow stub = client.newWorkflowStub(
                SettlementWindowWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                        .build());
        return stub.run(input);
    }

    private SettlementInput input(long windowSeconds) {
        return new SettlementInput(
                "22222222-2222-2222-2222-222222222222",
                "11111111-1111-1111-1111-111111111111",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                500L, windowSeconds);
    }

    /** 可控 fake activity——测试按分支配置 captureSettlement 返回值，并记录调用。 */
    static final class FakeActivity implements SettlementActivity {
        SettlementOutcome outcome = SettlementOutcome.aborted();
        boolean captured = false;

        @Override
        public SettlementOutcome captureSettlement(SettlementInput input) {
            captured = true;
            return outcome;
        }
    }
}
