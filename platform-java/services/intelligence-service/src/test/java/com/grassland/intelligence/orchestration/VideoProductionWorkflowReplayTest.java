package com.grassland.intelligence.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.orchestration.VideoProductionActivities.GenerationStatus;
import com.grassland.intelligence.orchestration.VideoProductionActivities.TaskSnapshot;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 卡A1 workflow 重放测试（TestWorkflowEnvironment + 假 activity，照 trust DisputeAdjudication
 * WorkflowReplayTest）。
 *
 * <p>确定性约定：TestWorkflowEnvironment 的虚拟时钟会在 workflow 只剩定时器时自动跳跃——
 * 等待用例一律在起流前把假 activity 状态与信号备好（信号由服务端缓冲，worker 上岗即投递），
 * 避免「真实测试线程拼不过虚拟时钟」的竞态。
 */
@DisplayName("Video production workflow (replay)")
class VideoProductionWorkflowReplayTest {

    private TestWorkflowEnvironment env;
    private FakeActivities fake;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        fake = new FakeActivities();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** pollInterval 取大块（10 分钟虚拟）——等待超时用例只需少数几跳即可走完 48h 上限。 */
    private VideoTaskSpec spec() {
        return new VideoTaskSpec(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "account", null, "video", VideoTaskSpec.KIND_INITIAL, 0, 600_000, 3600);
    }

    private VideoProductionWorkflow stub(String workflowId) {
        return env.getWorkflowClient().newWorkflowStub(VideoProductionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(VideoProductionWorkflowImpl.TASK_QUEUE)
                        .setWorkflowIdReusePolicy(
                                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                        .build());
    }

    /** 等工作流跑完（虚拟时间跳过等待），返回终态 queryState。 */
    private VideoTaskState awaitDone(VideoProductionWorkflow stub) {
        WorkflowStub.fromTyped(stub).getResult(Void.class);
        return stub.queryState();
    }

    @Test
    @DisplayName("全链：生成全终态 → 行进入 composing（服务端落定选片后点合成）→ 合成结算 → done")
    void happyPathRowEntersComposing() {
        Worker worker = env.newWorker(VideoProductionWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(VideoProductionWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();

        fake.phase = "composing";
        fake.allTerminal = true;
        VideoProductionWorkflow stub = stub("video-task-" + UUID.randomUUID());
        WorkflowClient.start(stub::run, spec());

        VideoTaskState done = awaitDone(stub);
        assertThat(done.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_DONE);
        assertThat(fake.composeCalls.get()).isEqualTo(1);
        assertThat(fake.drives.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("生成段行终态失败（worker 已收口退款）→ workflow 直接退出，不合成不重复补偿")
    void rowFailedByWorkerExitsWithoutCompose() {
        Worker worker = env.newWorker(VideoProductionWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(VideoProductionWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();

        fake.terminalPhase = "failed";
        VideoProductionWorkflow stub = stub("video-task-" + UUID.randomUUID());
        WorkflowClient.start(stub::run, spec());

        VideoTaskState done = awaitDone(stub);
        assertThat(done.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_DONE);
        assertThat(done.lastError()).isEqualTo("take_all_failed");
        assertThat(fake.composeCalls.get()).isZero();
        assertThat(fake.timeoutCalls.get()).isZero();
    }

    @Test
    @DisplayName("未选片到上限 → selectionTimeout（failed+退款 activity）→ selection_timeout 终态")
    void selectionWaitTimesOut() {
        Worker worker = env.newWorker(VideoProductionWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(VideoProductionWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();

        fake.phase = "generating";
        fake.allTerminal = true;
        VideoProductionWorkflow stub = stub("video-task-" + UUID.randomUUID());
        WorkflowClient.start(stub::run, spec());

        VideoTaskState done = awaitDone(stub);
        assertThat(done.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_SELECTION_TIMEOUT);
        assertThat(fake.timeoutCalls.get()).isEqualTo(1);
        assertThat(fake.composeCalls.get()).isZero();
    }

    @Test
    @DisplayName("等待段 cancel 信号（起流前缓冲）→ 直接收口，不合成不超时")
    void cancelDuringSelectionWaitExits() {
        Worker worker = env.newWorker(VideoProductionWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(VideoProductionWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();

        fake.phase = "generating";
        fake.allTerminal = true;
        VideoProductionWorkflow stub = stub("video-task-" + UUID.randomUUID());
        WorkflowClient.start(stub::run, spec());
        stub.cancel("user cancelled");

        VideoTaskState done = awaitDone(stub);
        assertThat(done.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_CANCELLED);
        assertThat(fake.composeCalls.get()).isZero();
        assertThat(fake.timeoutCalls.get()).isZero();
    }

    @Test
    @DisplayName("挑选前 worker 不在岗：起流+选片信号缓冲，worker 上岗后恢复继续收口")
    void workerJoinsAfterWorkflowStartedAndSignalBuffered() {
        // 先备状态：全终态 + 行已 composing；起流（无 worker 在岗）并缓冲选片信号
        fake.phase = "composing";
        fake.allTerminal = true;
        VideoProductionWorkflow stub = stub("video-task-" + UUID.randomUUID());
        WorkflowClient.start(stub::run, spec());
        stub.submitSelections(SelectionPayload.of(Map.of("shot-1", "take-1")));

        // worker 恢复在岗：注册后启动，workflow 从缓冲信号处继续推进
        Worker worker = env.newWorker(VideoProductionWorkflowImpl.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(VideoProductionWorkflowImpl.class);
        worker.registerActivitiesImplementations(fake);
        env.start();

        VideoTaskState done = awaitDone(stub);
        assertThat(done.stage()).isEqualTo(VideoProductionWorkflowImpl.STAGE_DONE);
        assertThat(done.selectionSubmitted()).isTrue();
        assertThat(fake.composeCalls.get()).isEqualTo(1);
    }

    /** 自旋等 queryState 到目标阶段（调试辅助；主断言走 awaitDone）。 */
    @SuppressWarnings("unused")
    private void awaitStage(VideoProductionWorkflow stub, String stage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!stub.queryState().stage().equals(stage)) {
            assertThat(System.nanoTime() < deadline).as("stage 未到达: " + stage).isTrue();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** 假 activity：状态由测试预设（activity 线程读、测试线程写，volatile 可见性足够）。 */
    static final class FakeActivities implements VideoProductionActivities {

        volatile String phase = "generating";
        volatile boolean allTerminal = false;
        volatile String terminalPhase = null;
        final AtomicInteger drives = new AtomicInteger();
        final AtomicInteger composeCalls = new AtomicInteger();
        final AtomicInteger timeoutCalls = new AtomicInteger();

        @Override
        public TaskSnapshot loadTask(String taskId, String accountId) {
            return snap();
        }

        @Override
        public GenerationStatus driveGeneration(String taskId, String accountId) {
            drives.incrementAndGet();
            return new GenerationStatus(snap(), true, allTerminal, true);
        }

        @Override
        public TaskSnapshot composeAndSettle(String taskId, String accountId) {
            composeCalls.incrementAndGet();
            return new TaskSnapshot("succeeded", 100, true, "media-1", 1200, null);
        }

        @Override
        public void selectionTimeout(String taskId, String accountId) {
            timeoutCalls.incrementAndGet();
        }

        private TaskSnapshot snap() {
            if (terminalPhase != null) {
                return new TaskSnapshot(terminalPhase, 100, true, null, null,
                        "failed".equals(terminalPhase) ? "take_all_failed" : null);
            }
            return new TaskSnapshot(phase, 40, false, null, null, null);
        }
    }
}
