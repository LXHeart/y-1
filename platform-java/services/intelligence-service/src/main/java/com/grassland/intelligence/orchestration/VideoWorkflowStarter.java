package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.videoproduction.VideoProductionTask;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 视频管线 workflow 启动/信号门面（任务书 #66 卡A1，照 marketplace AdjudicationWorkflowStarter）。
 *
 * <p>workflowId 约定：首发 {@code video-task-{taskId}}；成片后重抽 {@code video-task-{taskId}-r{n}}
 * （原工作流已关闭，重抽由第二春工作流驱动）。信号尽力而为——行才是真相源，工作流不存在/已关闭
 * 时静默（例如重放请求打到已收口的工作流）。
 */
@Component
public class VideoWorkflowStarter {

    private static final Logger log = LoggerFactory.getLogger(VideoWorkflowStarter.class);

    public static final String SELECTION_TIMEOUT_SECONDS = "172800"; // 48h（§4.4 拍板）

    private final WorkflowClient client;
    private final long pollIntervalMs;
    private final long selectionTimeoutSeconds;

    public VideoWorkflowStarter(WorkflowClient client,
            @Value("${ai.video-generation.poll-interval:3s}") Duration pollInterval,
            @Value("${ai.video-production.selection-timeout-seconds:172800}") long selectionTimeoutSeconds) {
        this.client = client;
        this.pollIntervalMs = Math.max(500L, pollInterval.toMillis());
        this.selectionTimeoutSeconds = selectionTimeoutSeconds;
    }

    public static String workflowId(UUID taskId) {
        return "video-task-" + taskId;
    }

    public static String rerollWorkflowId(UUID taskId, int recomposeSeq) {
        return "video-task-" + taskId + "-r" + recomposeSeq;
    }

    /** 幂等启动：同 workflowId 已在跑/已完结不重复起（创建端点本身有 operationId 幂等兜底）。 */
    public void start(VideoTaskSpec spec, String workflowId) {
        VideoProductionWorkflow stub = client.newWorkflowStub(VideoProductionWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(VideoProductionWorkflowImpl.TASK_QUEUE)
                        .setWorkflowIdReusePolicy(
                                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                        .build());
        try {
            WorkflowClient.start(stub::run, spec);
        } catch (io.temporal.client.WorkflowExecutionAlreadyStarted already) {
            log.info("video workflow already started workflowId={}", workflowId);
        }
    }

    /** 从任务行构建 spec 并启动（首发/重抽由 kind+recomposeSeq 区分；控制器与收养清扫共用）。 */
    public void startForTask(VideoProductionTask task, String kind, int recomposeSeq) {
        VideoTaskSpec spec = new VideoTaskSpec(task.id().toString(), task.storyboardId().toString(),
                task.accountId(), task.organizationId(), task.mode(), kind, recomposeSeq,
                pollIntervalMs, selectionTimeoutSeconds);
        String workflowId = VideoTaskSpec.KIND_REROLL.equals(kind)
                ? rerollWorkflowId(task.id(), recomposeSeq)
                : workflowId(task.id());
        start(spec, workflowId);
    }

    public void signalSelection(String workflowId, SelectionPayload payload) {
        trySignal(workflowId, "submitSelections",
                () -> client.newWorkflowStub(VideoProductionWorkflow.class, workflowId)
                        .submitSelections(payload));
    }

    public void signalCancel(String workflowId, String reason) {
        trySignal(workflowId, "cancel",
                () -> client.newWorkflowStub(VideoProductionWorkflow.class, workflowId)
                        .cancel(reason));
    }

    public void signalReroll(String workflowId, String shotId) {
        trySignal(workflowId, "requestReroll",
                () -> client.newWorkflowStub(VideoProductionWorkflow.class, workflowId)
                        .requestReroll(shotId));
    }

    /** 查询工作流内存态（对账用）；工作流不存在返回 empty。 */
    public Optional<VideoTaskState> queryState(String workflowId) {
        try {
            VideoProductionWorkflow stub = client.newWorkflowStub(VideoProductionWorkflow.class,
                    workflowId);
            return Optional.of(WorkflowStub.fromTyped(stub).query("queryState", VideoTaskState.class));
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    private void trySignal(String workflowId, String signal, Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException error) {
            // 行才是真相源：工作流不存在/已关闭（重放、legacy 期任务）静默降级
            log.debug("video workflow signal skipped workflowId={} signal={} cause={}", workflowId,
                    signal, String.valueOf(error.getMessage()));
        }
    }
}
