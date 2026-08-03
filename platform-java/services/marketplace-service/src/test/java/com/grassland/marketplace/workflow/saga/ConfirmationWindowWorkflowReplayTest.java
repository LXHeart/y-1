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
 * 用 {@link TestWorkflowEnvironment}（replay 引擎）验证 {@link ConfirmationWindowWorkflowImpl} 编排（D-03）：
 * 确认窗口 Timer 到期 → autoConfirmSettle activity（auto_settled/held/aborted）。纯单元测试，不启动 Spring 上下文。
 */
class ConfirmationWindowWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(ApplicationReservationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ConfirmationWindowWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void windowThenAutoConfirmSettles() {
        activity.outcome = ConfirmationOutcome.autoSettled();

        ConfirmationOutcome r = run(input(0, 0));

        assertThat(r.status()).isEqualTo("auto_settled");
        assertThat(activity.invoked).isTrue();
    }

    @Test
    void autoConfirmHeldOnDispute() {
        activity.outcome = ConfirmationOutcome.held("open_dispute");

        ConfirmationOutcome r = run(input(0, 0));

        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("open_dispute");
    }

    @Test
    void autoConfirmHeldOnVerificationFailed() {
        activity.outcome = ConfirmationOutcome.held("verification_failed");

        ConfirmationOutcome r = run(input(0, 0));

        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("verification_failed");
    }

    @Test
    void autoConfirmAbortedWhenMerchantConfirmedFirst() {
        activity.outcome = ConfirmationOutcome.aborted();

        ConfirmationOutcome r = run(input(0, 0));

        assertThat(r.status()).isEqualTo("aborted");
    }

    /** D-03 slice 2：窗口够长 ⇒ 中段（window - lead）发一次 notifyExpiring，再到期 autoConfirmSettle。 */
    @Test
    void expireReminderFiresMidWindow() {
        activity.outcome = ConfirmationOutcome.autoSettled();

        ConfirmationOutcome r = run(input(10, 4));   // window=10, lead=4 ⇒ sleep(6)→notify→sleep(4)→settle

        assertThat(r.status()).isEqualTo("auto_settled");
        assertThat(activity.notified).isTrue();
        assertThat(activity.invoked).isTrue();
    }

    /** dispatcher 在最后 lead 秒补启时传 lead=remaining（即 lead==window）→ 立即提醒，再等待剩余窗口。 */
    @Test
    void reminderFiresImmediatelyWhenLeadEqualsRemainingWindow() {
        activity.outcome = ConfirmationOutcome.autoSettled();

        ConfirmationOutcome r = run(input(5, 5));

        assertThat(r.status()).isEqualTo("auto_settled");
        assertThat(activity.notified).isTrue();
    }

    /** lead=0（dev 关闭）或 lead > window ⇒ 跳过提醒，仅到期自动结算。 */
    @Test
    void reminderSkippedWhenLeadZero() {
        activity.outcome = ConfirmationOutcome.autoSettled();

        ConfirmationOutcome r = run(input(5, 0));

        assertThat(r.status()).isEqualTo("auto_settled");
        assertThat(activity.notified).isFalse();
    }

    private ConfirmationOutcome run(ConfirmationInput input) {
        WorkflowClient client = env.getWorkflowClient();
        ConfirmationWindowWorkflow stub = client.newWorkflowStub(
                ConfirmationWindowWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                        .build());
        return stub.run(input);
    }

    private ConfirmationInput input(long windowSeconds, long reminderLeadSeconds) {
        return new ConfirmationInput(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                windowSeconds, reminderLeadSeconds);
    }

    /** 可控 fake activity——测试按分支配置 autoConfirmSettle 返回值，并记录调用。 */
    static final class FakeActivity implements ConfirmationActivity {
        ConfirmationOutcome outcome = ConfirmationOutcome.aborted();
        boolean invoked = false;
        boolean notified = false;

        @Override
        public ConfirmationOutcome autoConfirmSettle(ConfirmationInput input) {
            invoked = true;
            return outcome;
        }

        @Override
        public void notifyExpiring(ConfirmationInput input) {
            notified = true;
        }
    }
}
