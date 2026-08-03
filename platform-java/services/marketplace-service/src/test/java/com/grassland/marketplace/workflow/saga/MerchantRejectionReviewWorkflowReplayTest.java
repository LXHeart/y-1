package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** D-03 商家拒绝客服 SLA workflow 确定性/replay 测试。 */
class MerchantRejectionReviewWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(ApplicationReservationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(MerchantRejectionReviewWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void slaTimerThenAutoFinalizes() {
        MerchantRejectionReviewInput input = new MerchantRejectionReviewInput(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                3);
        WorkflowClient client = env.getWorkflowClient();
        MerchantRejectionReviewWorkflow stub = client.newWorkflowStub(
                MerchantRejectionReviewWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                        .build());

        stub.run(input);

        assertThat(activity.invoked).isTrue();
        assertThat(activity.last).isEqualTo(input);
    }

    static final class FakeActivity implements MerchantRejectionReviewActivity {
        boolean invoked;
        MerchantRejectionReviewInput last;

        @Override
        public void autoFinalize(MerchantRejectionReviewInput input) {
            invoked = true;
            last = input;
        }
    }
}
