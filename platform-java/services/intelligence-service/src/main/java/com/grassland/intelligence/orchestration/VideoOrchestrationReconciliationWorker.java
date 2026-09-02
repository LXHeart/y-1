package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 双写对账（任务书 #66 卡A1）：窗口内已完结任务逐个对照其（可能存在的）workflow 终态。
 *
 * <p>口径：legacy 期任务没有 workflow 属正常（queryState 落 empty 跳过）；对**找到** workflow
 * 的任务断言终态一致——row succeeded/failed ↔ stage done；row cancelled ↔ stage cancelled；
 * row failed(selection_timeout) ↔ stage selection_timeout 也算一致。抽样字段：
 * phase / finalMediaId / settledCents（行上即 actualCostCents，workflow 不持币值，只对阶段）。
 * 零差异 = runOnce 返回 0；任何不一致 ERROR 打点并计数。
 */
@Component
public class VideoOrchestrationReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoOrchestrationReconciliationWorker.class);
    private static final int WINDOW_HOURS = 24;
    private static final int BATCH_LIMIT = 200;

    private final VideoProductionTaskRepository tasks;
    private final VideoWorkflowStarter starter;

    public VideoOrchestrationReconciliationWorker(VideoProductionTaskRepository tasks,
            VideoWorkflowStarter starter) {
        this.tasks = tasks;
        this.starter = starter;
    }

    @Scheduled(fixedDelayString = "${ai.video-production.orchestration-reconcile-interval-ms:3600000}")
    public void reconcile() {
        runOnce().subscribeOn(Schedulers.boundedElastic()).subscribe(
                mismatches -> log.info(
                        "video orchestration reconciled metric=video_orchestration_reconcile "
                        + "mismatches={} windowHours={}", mismatches, WINDOW_HOURS),
                error -> log.error("video orchestration reconciliation failed", error));
    }

    /** 返回窗口内不一致数（0=对账零差异；IT 直接断言）。 */
    public Mono<Long> runOnce() {
        OffsetDateTime since = OffsetDateTime.now().minusHours(WINDOW_HOURS);
        return tasks.findTerminalCompletedSince(since, BATCH_LIMIT)
                .map(this::mismatchesOf)
                .reduce(0L, Long::sum);
    }

    private long mismatchesOf(VideoProductionTask task) {
        List<String> candidates = new ArrayList<>();
        candidates.add(VideoWorkflowStarter.workflowId(task.id()));
        for (int seq = 1; seq <= task.recomposeSeq(); seq++) {
            candidates.add(VideoWorkflowStarter.rerollWorkflowId(task.id(), seq));
        }
        long mismatches = 0;
        for (String workflowId : candidates) {
            Optional<VideoTaskState> state = starter.queryState(workflowId);
            if (state.isEmpty()) {
                continue;
            }
            if (!consistent(task, state.get())) {
                mismatches++;
                log.error("video orchestration mismatch metric=video_orchestration_mismatch "
                        + "taskId={} workflowId={} rowPhase={} finalMediaId={} settledCents={} "
                        + "stage={}", task.id(), workflowId, task.phase(), task.finalMediaId(),
                        task.actualCostCents(), state.get().stage());
            }
        }
        return mismatches;
    }

    private static boolean consistent(VideoProductionTask task, VideoTaskState state) {
        return switch (task.phase()) {
            case VideoProductionTask.PHASE_SUCCEEDED, VideoProductionTask.PHASE_FAILED
                    -> VideoProductionWorkflowImpl.STAGE_DONE.equals(state.stage())
                    || (VideoProductionTask.PHASE_FAILED.equals(task.phase())
                    && VideoProductionWorkflowImpl.STAGE_SELECTION_TIMEOUT.equals(state.stage()));
            case VideoProductionTask.PHASE_CANCELLED ->
                    VideoProductionWorkflowImpl.STAGE_CANCELLED.equals(state.stage())
                    || VideoProductionWorkflowImpl.STAGE_DONE.equals(state.stage());
            default -> false;
        };
    }
}
