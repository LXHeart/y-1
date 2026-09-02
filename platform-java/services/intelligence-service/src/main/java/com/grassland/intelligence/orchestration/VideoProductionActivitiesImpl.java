package com.grassland.intelligence.orchestration;

import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.orchestration.VideoProductionActivities.GenerationStatus;
import com.grassland.intelligence.orchestration.VideoProductionActivities.TaskSnapshot;
import com.grassland.intelligence.videoproduction.TakeGenerationWorker;
import com.grassland.intelligence.videoproduction.TtsWorker;
import com.grassland.intelligence.videoproduction.VideoCompositionService;
import com.grassland.intelligence.videoproduction.VideoGenerationProperties;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import com.grassland.intelligence.videoproduction.VideoProductionTaskService;
import com.grassland.intelligence.videoproduction.VideoShotAudio;
import com.grassland.intelligence.videoproduction.VideoShotAudioRepository;
import com.grassland.intelligence.videoproduction.VideoShotTake;
import com.grassland.intelligence.videoproduction.VideoShotTakeRepository;
import com.grassland.intelligence.videoproduction.VideoTaskEventStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 视频成片管线 activity 实现（任务书 #66 卡A1）：既有 worker/服务的薄壳。
 *
 * <p>领单走与 legacy worker 完全相同的行级租约（SKIP LOCKED + claimed_until），灰度期两路
 * 并跑不重复处理；activity 边界 block（Reactive 链在内部照旧事件循环跑，block 只发生在
 * activity 线程——trust AdjudicationActivityImpl 同款）。业务决策（重试到限/漂移拒绝/全终态
 * 收口退款）全在既有 worker 代码里，本类不复制。
 */
@Component
@io.temporal.spring.boot.ActivityImpl(workers = VideoProductionWorkflowImpl.TASK_QUEUE)
public class VideoProductionActivitiesImpl implements VideoProductionActivities {

    private static final Logger log = LoggerFactory.getLogger(VideoProductionActivitiesImpl.class);
    private static final Duration DB_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GENERATION_CYCLE_TIMEOUT = Duration.ofMinutes(25);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofMinutes(29);
    /** 合成租约地板：防长合成被二次领单并发执行。 */
    private static final Duration COMPOSE_LEASE_FLOOR = Duration.ofMinutes(10);

    private final VideoShotTakeRepository takes;
    private final VideoShotAudioRepository audios;
    private final TakeGenerationWorker takeWorker;
    private final TtsWorker ttsWorker;
    private final VideoProductionTaskRepository tasks;
    private final VideoCompositionService composition;
    private final VideoProductionTaskService taskService;
    private final AiExecutionService executions;
    private final VideoTaskEventStream events;
    private final VideoGenerationProperties properties;

    public VideoProductionActivitiesImpl(VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            TakeGenerationWorker takeWorker, TtsWorker ttsWorker, VideoProductionTaskRepository tasks,
            VideoCompositionService composition, VideoProductionTaskService taskService,
            AiExecutionService executions, VideoTaskEventStream events,
            VideoGenerationProperties properties) {
        this.takes = takes;
        this.audios = audios;
        this.takeWorker = takeWorker;
        this.ttsWorker = ttsWorker;
        this.tasks = tasks;
        this.composition = composition;
        this.taskService = taskService;
        this.executions = executions;
        this.events = events;
        this.properties = properties;
    }

    @Override
    public TaskSnapshot loadTask(String taskId, String accountId) {
        return tasks.findById(UUID.fromString(taskId), accountId)
                .map(VideoProductionActivitiesImpl::snapshot)
                .defaultIfEmpty(MISSING)
                .block(DB_TIMEOUT);
    }

    @Override
    public GenerationStatus driveGeneration(String taskId, String accountId) {
        // worker-enabled=false 时只做快照不领单（镜像 legacy dispatch 同款隔离语义，
        // 供行级 worker IT 确定性手动驱动；A4 后该闸同时约束两路）。
        if (!properties.isWorkerEnabled()) {
            VideoProductionTask quiet = tasks.findById(UUID.fromString(taskId), accountId)
                    .block(DB_TIMEOUT);
            return new GenerationStatus(quiet == null ? MISSING : snapshot(quiet), false, true, true);
        }
        // 与 legacy dispatch 同拍：take 与 audio 各领一批并行推进（租约互斥，空批无害）。
        // 注意用 Mono.when 而非 zip——两 cycle 都是空完成的 Mono<Void>，zip 会在先完成者
        // 空完成时立刻取消另一侧（实测：TTS 空批秒完成把 take 处理整个取消掉，领单却已发生）。
        Mono<Void> takeCycle = takes.claimBatch(properties.getBatchSize(), properties.getClaimLease())
                .flatMap(takeWorker::process)
                .then();
        Mono<Void> audioCycle = audios.claimBatch(properties.getBatchSize(), properties.getClaimLease())
                .flatMap(ttsWorker::process)
                .then();
        Mono.when(takeCycle, audioCycle).block(GENERATION_CYCLE_TIMEOUT);

        VideoProductionTask task = tasks.findById(UUID.fromString(taskId), accountId)
                .block(DB_TIMEOUT);
        if (task == null) {
            return new GenerationStatus(MISSING, false, true, true);
        }
        List<VideoShotTake> takeRows = takes.findByStoryboard(task.storyboardId())
                .collectList().block(DB_TIMEOUT);
        List<VideoShotAudio> audioRows = audios.findByStoryboard(task.storyboardId())
                .collectList().block(DB_TIMEOUT);
        boolean allTakesTerminal = takeRows.stream().allMatch(VideoShotTake::isTerminal);
        boolean allAudiosTerminal = audioRows.stream()
                .allMatch(audio -> audio.isSettled()
                        || VideoShotAudio.STATUS_FAILED.equals(audio.status()));
        return new GenerationStatus(snapshot(task), !takeRows.isEmpty(), allTakesTerminal,
                allAudiosTerminal);
    }

    @Override
    public TaskSnapshot composeAndSettle(String taskId, String accountId) {
        VideoProductionTask task = tasks.findById(UUID.fromString(taskId), accountId)
                .block(DB_TIMEOUT);
        if (task == null || task.isTerminal()) {
            return task == null ? MISSING : snapshot(task);
        }
        if (!VideoProductionTask.PHASE_COMPOSING.equals(task.phase())) {
            return snapshot(task);
        }
        if (!properties.isWorkerEnabled()) {
            // 同上隔离语义：IT 手动驱动合成时 activity 不抢领单
            return snapshot(task);
        }
        // 按 id 领单（租约下限同既有合成地板 10 分钟），防长合成被二次领单并发执行
        Duration lease = properties.getClaimLease().compareTo(COMPOSE_LEASE_FLOOR) > 0
                ? properties.getClaimLease()
                : COMPOSE_LEASE_FLOOR;
        VideoProductionTask claimed = tasks.claimById(task.id(), lease).block(DB_TIMEOUT);
        if (claimed == null) {
            // legacy worker 已持租约在合成——交给它，workflow 等终态
            log.info("compose claim missed (legacy holder?) taskId={}", taskId);
            return snapshot(tasks.findById(task.id(), accountId).block(DB_TIMEOUT));
        }
        composition.compose(claimed).block(COMPOSE_TIMEOUT);
        return tasks.findById(task.id(), accountId).map(VideoProductionActivitiesImpl::snapshot)
                .block(DB_TIMEOUT);
    }

    @Override
    public void selectionTimeout(String taskId, String accountId) {
        VideoProductionTask task = tasks.findById(UUID.fromString(taskId), accountId)
                .block(DB_TIMEOUT);
        if (task == null || task.isTerminal()) {
            return;
        }
        // 镜像 taskService.cancel 的退款语义：rebuildContext → handleFailure 全额退，再落终态
        taskService.rebuildContext(task)
                .flatMap(ctx -> executions.handleFailure(ctx, "选片等待超时（48h）"))
                .onErrorResume(error -> {
                    log.error("selection timeout refund failed taskId={}", taskId, error);
                    return Mono.empty();
                })
                .then(tasks.markFailed(task.id(), "selection_timeout", "选片等待超时（48h）"))
                .doOnSuccess(ignored -> events.emitPhase(task.id(), VideoProductionTask.PHASE_FAILED))
                .block(DB_TIMEOUT);
    }

    private static final TaskSnapshot MISSING = new TaskSnapshot("missing", 0, true, null, null,
            "task_missing");

    private static TaskSnapshot snapshot(VideoProductionTask task) {
        return new TaskSnapshot(task.phase(), task.progress(),
                task.isTerminal(), task.finalMediaId() == null ? null : task.finalMediaId().toString(),
                task.actualCostCents(), task.errorCode());
    }
}
