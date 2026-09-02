package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.orchestration.VideoProductionActivities.GenerationStatus;
import com.grassland.intelligence.orchestration.VideoProductionActivities.TaskSnapshot;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;

/**
 * 视频成片管线 workflow 实现（任务书 #66 卡A1）。
 *
 * <p>三段驱动：①生成段——driveGeneration 循环推 take/audio 行到全终态（regenerate 重抽的新
 * 候选在等待段也会被继续驱动，A4 删 legacy worker 后不留死角）；②等待段——选片信号/行 phase=
 * composing 任一到位即放行，48h 上限内未推进则 selectionTimeout（failed+全额退）；③合成段——
 * composeAndSettle 按 id 领单（10 分钟租约地板）走既有合成与结算。
 *
 * <p>状态真相恒在任务行：任何一段看到行已终态（含 legacy worker 收口/用户取消/漂移退款）直接
 * 退出，不重复补偿。cancel 信号只用于叫醒等待段——行的取消与退款由端点既有逻辑完成。
 */
@WorkflowImpl(taskQueues = VideoProductionWorkflowImpl.TASK_QUEUE)
public class VideoProductionWorkflowImpl implements VideoProductionWorkflow {

    public static final String TASK_QUEUE = "intelligence-video-production";

    // queryState 终值（对账映射：succeeded/failed↔done；cancelled↔cancelled；超时↔selection_timeout）
    public static final String STAGE_GENERATING = "generating";
    public static final String STAGE_AWAITING_SELECTION = "awaiting_selection";
    public static final String STAGE_COMPOSING = "composing";
    public static final String STAGE_DONE = "done";
    public static final String STAGE_CANCELLED = "cancelled";
    public static final String STAGE_SELECTION_TIMEOUT = "selection_timeout";

    /** 短活动（行快照/超时收口）：读为主，失败快重试。 */
    private final VideoProductionActivities poll = Workflow.newActivityStub(
            VideoProductionActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setMaximumInterval(Duration.ofSeconds(10))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    /** 长活动（批量生成/ffmpeg 合成）：单拍可能分钟级。 */
    private final VideoProductionActivities work = Workflow.newActivityStub(
            VideoProductionActivities.class,
            ActivityOptions.newBuilder()
                    // 合成上限 600s/镜头段 + 归档；批量驱动含 provider 轮询，统一给到 30 分钟
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(6)
                            .build())
                    .build());

    private VideoTaskSpec spec;
    private String stage = STAGE_GENERATING;
    private boolean selectionSubmitted;
    private boolean cancelRequested;
    private String cancelReason;
    private String lastError;

    @Override
    public void run(VideoTaskSpec spec) {
        this.spec = spec;
        this.stage = STAGE_GENERATING;

        // ① 生成段：推到 take/audio 全终态（行终态即退出——worker/漂移/取消可能已先收口）
        GenerationStatus status = work.driveGeneration(spec.taskId(), spec.accountId());
        while (!status.task().terminal() && !(status.allTakesTerminal() && status.allAudiosTerminal())) {
            Workflow.sleep(spec.pollIntervalMs());
            status = work.driveGeneration(spec.taskId(), spec.accountId());
        }
        if (status.task().terminal()) {
            finish(status.task());
            return;
        }

        // ② 等待段：选片信号或行直接进入 composing（服务端落定选片后用户点合成）任一放行；
        //    等待期间持续驱动 regenerate 新候选。48h 内无推进 → failed + 全额退。
        this.stage = STAGE_AWAITING_SELECTION;
        long deadline = Workflow.currentTimeMillis() + spec.selectionTimeoutSeconds() * 1000L;
        while (true) {
            if (cancelRequested) {
                // 行已被端点置 cancelled 并退款，workflow 仅收口
                this.stage = STAGE_CANCELLED;
                return;
            }
            if (VideoProductionTask.PHASE_COMPOSING.equals(status.task().phase())) {
                break;
            }
            long remaining = deadline - Workflow.currentTimeMillis();
            if (remaining <= 0) {
                poll.selectionTimeout(spec.taskId(), spec.accountId());
                this.stage = STAGE_SELECTION_TIMEOUT;
                this.lastError = "selection_timeout";
                return;
            }
            // 以轮询片为上限等信号（信号到达即提前醒）；醒来后驱动一拍（覆盖 regenerate 新候选）
            Workflow.await(Duration.ofMillis(Math.min(spec.pollIntervalMs(), remaining)),
                    () -> selectionSubmitted || cancelRequested);
            status = work.driveGeneration(spec.taskId(), spec.accountId());
            if (status.task().terminal()) {
                finish(status.task());
                return;
            }
            if (VideoProductionTask.PHASE_COMPOSING.equals(status.task().phase())) {
                break;
            }
        }

        // ③ 合成段：按 id 领单 + 既有合成/结算（内部含失败退款收口）
        this.stage = STAGE_COMPOSING;
        VideoProductionActivities.TaskSnapshot after = work.composeAndSettle(spec.taskId(),
                spec.accountId());
        finish(after);
    }

    private void finish(VideoProductionActivities.TaskSnapshot task) {
        if (VideoProductionTask.PHASE_CANCELLED.equals(task.phase())) {
            this.stage = STAGE_CANCELLED;
        } else {
            this.stage = STAGE_DONE;
        }
        this.lastError = task.errorCode();
    }

    @Override
    public void submitSelections(SelectionPayload selections) {
        this.selectionSubmitted = true;
    }

    @Override
    public void requestReroll(String shotId) {
        // 成片后重抽发生在原 workflow 关闭之后：真正驱动由 reroll 工作流（video-task-{id}-r{n}）
        // 承担；该信号只为在跑工作流留痕，无状态转移。
    }

    @Override
    public void cancel(String reason) {
        this.cancelRequested = true;
        this.cancelReason = reason;
    }

    @Override
    public VideoTaskState queryState() {
        return new VideoTaskState(stage, spec == null ? null : spec.taskId(), selectionSubmitted,
                cancelReason, lastError);
    }
}
