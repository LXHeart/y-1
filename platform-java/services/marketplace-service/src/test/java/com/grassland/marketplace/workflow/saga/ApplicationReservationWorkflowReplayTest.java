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
 * 用 {@link TestWorkflowEnvironment}（replay 引擎）验证 {@link ApplicationReservationWorkflowImpl} 的编排分支
 * （草场 Epic 4 Slice 4F / HLD 532 Replay）：success→activate / 余额不足→compensate / activate 失败→release+compensate /
 * beginAcceptance abort→不进资金流。注册一个可控 FakeActivity 驱动各分支，纯单元测试不启动 Spring 上下文。
 */
class ApplicationReservationWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivity activity;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(ApplicationReservationWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ApplicationReservationWorkflowImpl.class);
        activity = new FakeActivity();
        worker.registerActivitiesImplementations(activity);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void reserveSuccessActivates() {
        activity.beginResult = true;
        activity.reserveResult = ReserveResult.reserved(500);

        ReservationOutcome outcome = run(input());

        assertThat(outcome.status()).isEqualTo("accepted");
        assertThat(activity.activated).isTrue();
        assertThat(activity.compensated).isFalse();
    }

    @Test
    void insufficientFundsCompensates() {
        activity.beginResult = true;
        activity.reserveResult = ReserveResult.insufficientFunds();

        ReservationOutcome outcome = run(input());

        assertThat(outcome.status()).isEqualTo("compensated");
        assertThat(outcome.reason()).isEqualTo("insufficient_funds");
        assertThat(activity.compensatedReason).isEqualTo("insufficient_funds");
        assertThat(activity.activated).isFalse();
    }

    @Test
    void activateFailureReleasesAndCompensates() {
        activity.beginResult = true;
        activity.reserveResult = ReserveResult.reserved(500);
        activity.activateShouldThrow = new IllegalStateException("activate boom");

        ReservationOutcome outcome = run(input());

        assertThat(outcome.status()).isEqualTo("compensated");
        assertThat(outcome.reason()).isEqualTo("activate_failed");
        assertThat(activity.compensatedReason).isEqualTo("activate_failed");
        assertThat(activity.reservePassedToCompensate.reserved()).isTrue();  // release 退还分支
    }

    @Test
    void beginAbortsBeforeAnyFundsCall() {
        activity.beginResult = false;

        ReservationOutcome outcome = run(input());

        assertThat(outcome.status()).isEqualTo("aborted");
        assertThat(activity.reserveCalled).isFalse();
    }

    private ReservationOutcome run(AcceptanceInput input) {
        WorkflowClient client = env.getWorkflowClient();
        ApplicationReservationWorkflow stub = client.newWorkflowStub(
                ApplicationReservationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ApplicationReservationWorkflowImpl.TASK_QUEUE)
                        .build());
        return stub.run(input);  // 阻塞至完成（test env 虚拟时间驱动 activity 重试）
    }

    private AcceptanceInput input() {
        return new AcceptanceInput(
                "22222222-2222-2222-2222-222222222222",
                "11111111-1111-1111-1111-111111111111",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                500L);
    }

    /** 可控 fake activity——测试按分支配置 begin/reserve/activate 行为，并记录调用。 */
    static final class FakeActivity implements ApplicationReservationActivity {
        boolean beginResult = false;
        boolean reserveCalled = false;
        ReserveResult reserveResult = ReserveResult.insufficientFunds();
        boolean activated = false;
        RuntimeException activateShouldThrow;
        boolean compensated = false;
        String compensatedReason;
        ReserveResult reservePassedToCompensate;

        @Override
        public boolean beginAcceptance(AcceptanceInput input) {
            return beginResult;
        }

        @Override
        public ReserveResult reserveFunds(AcceptanceInput input) {
            reserveCalled = true;
            return reserveResult;
        }

        @Override
        public void activateEngagement(AcceptanceInput input) {
            if (activateShouldThrow != null) {
                throw activateShouldThrow;
            }
            activated = true;
        }

        @Override
        public void compensateAcceptance(AcceptanceInput input, ReserveResult reserve, String reason) {
            compensated = true;
            compensatedReason = reason;
            reservePassedToCompensate = reserve;
        }
    }
}
