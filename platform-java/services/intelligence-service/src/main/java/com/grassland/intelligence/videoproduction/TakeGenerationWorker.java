package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.videoproduction.VideoGenerationProviderResolver.VideoProviderResolution;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 逐镜候选 worker（任务书 #64 卡6）：claim {@code video_shot_take} → 冻结配置校验（漂移拒绝）→
 * 锚定图映射（storyboard 请求快照的 base64 图，1 基，0=纯文生）→ provider submit/poll（跨领单
 * 周期，任务号落行）→ 归档私有存储（purpose=video_take、≤200MB、同 origin）→ 进度与全终态推进。
 *
 * <p>单 take 失败到限只标 failed 不阻塞他镜（§4.4）；全部终态且任一镜无可选候选 → 任务 failed +
 * handleFailure 全额退。slideshow 模式的 take 行由卡8（zoompan 渲染）派生与处理。
 */
@Component
public class TakeGenerationWorker {

    private static final Logger log = LoggerFactory.getLogger(TakeGenerationWorker.class);

    private final VideoShotTakeRepository takes;
    private final VideoShotRepository shots;
    private final VideoStoryboardRepository storyboards;
    private final VideoProductionTaskRepository tasks;
    private final VideoGenerationProviderResolver resolver;
    private final VideoAssetArchiveService archives;
    private final AiExecutionService executions;
    private final VideoProductionTaskService taskService;
    private final VideoGenerationProperties properties;
    private final com.grassland.intelligence.mediaplatform.MediaProcessRunner runner;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final ObjectMapper mapper = new ObjectMapper();

    public TakeGenerationWorker(VideoShotTakeRepository takes, VideoShotRepository shots,
            VideoStoryboardRepository storyboards, VideoProductionTaskRepository tasks,
            VideoGenerationProviderResolver resolver, VideoAssetArchiveService archives,
            AiExecutionService executions, VideoProductionTaskService taskService,
            VideoGenerationProperties properties,
            com.grassland.intelligence.mediaplatform.MediaProcessRunner runner,
            MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider) {
        this.takes = takes;
        this.shots = shots;
        this.storyboards = storyboards;
        this.tasks = tasks;
        this.resolver = resolver;
        this.archives = archives;
        this.executions = executions;
        this.taskService = taskService;
        this.properties = properties;
        this.runner = runner;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
    }

    @Scheduled(fixedDelayString = "${ai.video-generation.poll-interval:3s}")
    public void dispatch() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        takes.claimBatch(properties.getBatchSize(), properties.getClaimLease())
                .flatMap(this::process)
                .onErrorContinue((error, value) -> log.warn("take dispatch item failed value={}", value, error))
                .subscribe();
    }

    /** 部署调度器/测试以具体行驱动（VideoGenerationWorker.process 同款确定性入口）。 */
    public Mono<Void> process(VideoShotTake take) {
        if (take.isTerminal()) {
            return Mono.empty();
        }
        return shots.findById(take.shotId())
                .flatMap(shot -> storyboards.findById(shot.storyboardId())
                        .flatMap(storyboard -> drive(take, shot, storyboard)))
                .onErrorResume(error -> {
                    log.warn("take processing failed takeId={}", take.id(), error);
                    return takes.scheduleRetry(take.id(), "take_worker_error",
                            error.getMessage() == null ? "take worker failed" : error.getMessage(),
                            Duration.ofSeconds(10)).then();
                });
    }

    private Mono<Void> drive(VideoShotTake take, VideoShot shot, VideoStoryboard storyboard) {
        return tasks.findLatestByStoryboard(storyboard.id(), storyboard.accountId())
                .flatMap(task -> {
                    if (task == null || task.isTerminal()) {
                        return takes.markFailed(take.id(), "task_missing", "任务不存在或已结束").then();
                    }
                    if (task.isSlideshow()) {
                        // 卡8 接入 zoompan 渲染前，图文成片任务不派生候选行；防御性兜底不 spin。
                        return takes.markFailed(take.id(), "slideshow_take_unsupported",
                                "图文成片候选在卡8接入").then();
                    }
                    return resolver.resolveVideoGeneration().flatMap(video -> {
                        Mono<Void> guard = frozenGuard(task, video);
                        return guard.then(Mono.defer(() -> {
                            if (take.attempts() > properties.getMaxAttempts()) {
                                return takes.markFailed(take.id(), "take_max_attempts",
                                        "候选超过最大重试次数").then(afterTerminal(shot, storyboard));
                            }
                            var plan = video.plan();
                            return anchorImages(storyboard, shot)
                                    .map(images -> new VideoGenerationProvider.ProviderCommand(
                                            take.id(), task.model(), shot.prompt(), images,
                                            shot.plannedSeconds(),
                                            VideoResolution.aspectRatioOf(storyboard.resolutionOrDefault())))
                                    .flatMap(command -> {
                                        Mono<VideoGenerationProvider.ProviderResult> call =
                                                take.providerTaskId() == null
                                                        ? plan.adapter().submit(command)
                                                        : plan.adapter().poll(take.providerTaskId(),
                                                                shot.plannedSeconds());
                                        return call.flatMap(result -> switch (result.state()) {
                                            case SUCCEEDED -> complete(take, shot, storyboard, video, result);
                                            case FAILED -> fail(take, shot, storyboard, result);
                                            case QUEUED -> takes.updateProviderState(take.id(),
                                                    VideoShotTake.STATUS_SUBMITTED, result.providerTaskId()).then();
                                            case PROCESSING -> takes.updateProviderState(take.id(),
                                                    VideoShotTake.STATUS_PROCESSING, result.providerTaskId()).then();
                                            default -> takes.scheduleRetry(take.id(), "take_unknown_state",
                                                    "未知 provider 状态", Duration.ofSeconds(5)).then();
                                        });
                                    });
                        }));
                    });
                });
    }

    /** 冻结校验：任务创建后控制面漂移（provider/模型/版本/价）→ 拒绝执行，等待全终态退款。 */
    private Mono<Void> frozenGuard(VideoProductionTask task, VideoProviderResolution video) {
        if (!video.available()) {
            return failAllPending(task, "provider_unavailable", video.unavailableReason());
        }
        var plan = video.plan();
        boolean matches = plan.resolution().provider().equalsIgnoreCase(task.provider())
                && plan.resolution().model().equals(task.model())
                && plan.unitPriceCents() == task.unitPriceCents()
                && plan.priceTableVersion().equals(task.pricingVersion())
                && (task.platformModelVersion() == null
                        || plan.resolution().platformModelVersion() == task.platformModelVersion());
        if (matches) {
            return Mono.empty();
        }
        return failAllPending(task, "provider_config_drift", "任务创建后视频渠道配置已变化，拒绝执行");
    }

    private Mono<Void> complete(VideoShotTake take, VideoShot shot, VideoStoryboard storyboard,
            VideoProviderResolution video, VideoGenerationProvider.ProviderResult result) {
        boolean sandbox = "sandbox".equalsIgnoreCase(video.plan().resolution().provider());
        Mono<String> archived = sandbox
                // 沙箱渠道：SandboxMedia lavfi 产真实 mp4（卡8 可合成）；ffmpeg 缺席回落存根
                ? Mono.fromCallable(() -> SandboxMedia.testsrcMp4(runner))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(bytes -> archives.archiveGeneratedBytes(storyboard.accountId(),
                                storyboard.organizationId(), take.id(), bytes, MediaPurpose.VIDEO_TAKE,
                                "video_shot_take", take.id(), "media/video_take/"))
                : archives.archiveGenerated(storyboard.accountId(), storyboard.organizationId(), take.id(),
                        video.plan().resolution().baseUrl(), result.resultUrl(), MediaPurpose.VIDEO_TAKE,
                        "video_shot_take", take.id(), "media/video_take/", false);
        long startedAt = System.currentTimeMillis();
        return archived
                .flatMap(reference -> takes.attachMedia(take.id(),
                        UUID.fromString(reference.substring("/api/media/".length())), result.durationSeconds()))
                .then(shots.updateStatus(shot.id(), VideoShot.STATUS_READY))
                .doOnSuccess(ignored -> log.info(
                        "video take completed metric=take_completed takeId={} provider={} attempts={} "
                                + "durationMs={} status=succeeded",
                        take.id(), take.provider(), take.attempts(), System.currentTimeMillis() - startedAt))
                .then(afterTerminal(shot, storyboard));
    }

    private Mono<Void> fail(VideoShotTake take, VideoShot shot, VideoStoryboard storyboard,
            VideoGenerationProvider.ProviderResult result) {
        if (take.attempts() < properties.getMaxAttempts()) {
            return takes.scheduleRetry(take.id(),
                    result.errorCode() == null ? "take_provider_failed" : result.errorCode(),
                    result.errorMessage(), properties.getPollInterval()).then();
        }
        return takes.markFailed(take.id(),
                result.errorCode() == null ? "take_provider_failed" : result.errorCode(),
                result.errorMessage())
                .doOnSuccess(ignored -> log.warn(
                        "video take failed metric=take_failed takeId={} provider={} attempts={} code={}",
                        take.id(), take.provider(), take.attempts(),
                        result.errorCode() == null ? "take_provider_failed" : result.errorCode()))
                .then(afterTerminal(shot, storyboard));
    }

    /** 终态推进：进度刷新 + 全终态检查（任一镜无可选候选 → 任务失败全额退）。全响应式——事件循环上禁 block。 */
    private Mono<Void> afterTerminal(VideoShot shot, VideoStoryboard storyboard) {
        return takes.findByStoryboard(storyboard.id()).collectList()
                .flatMap(all -> {
                    long terminal = all.stream().filter(VideoShotTake::isTerminal).count();
                    int progress = all.isEmpty() ? 0 : (int) Math.floor(80.0 * terminal / all.size());
                    Mono<Void> progressUpdate = tasks.findLatestByStoryboard(storyboard.id(),
                                    storyboard.accountId())
                            .flatMap(task -> tasks.updatePhase(task.id(),
                                    VideoProductionTask.PHASE_GENERATING, Math.max(progress, 1)).then());
                    if (terminal < all.size() || all.isEmpty()) {
                        return progressUpdate;
                    }
                    return everyShotSelectable(all, storyboard.id())
                            .flatMap(covered -> covered
                                    ? progressUpdate
                                    : failTask(storyboard, all, "take_all_failed",
                                            "全部候选失败，无法合成成片").then(progressUpdate));
                });
    }

    private Mono<Boolean> everyShotSelectable(List<VideoShotTake> allTakes, UUID storyboardId) {
        long covered = allTakes.stream().filter(VideoShotTake::isSelectable)
                .map(VideoShotTake::shotId).distinct().count();
        return shots.findByStoryboard(storyboardId).count()
                .map(shotCount -> covered >= shotCount);
    }

    private Mono<Void> failTask(VideoStoryboard storyboard, List<VideoShotTake> allTakes, String code,
            String message) {
        return tasks.findLatestByStoryboard(storyboard.id(), storyboard.accountId())
                .flatMap(task -> taskService.rebuildContext(task)
                        .flatMap(ctx -> executions.handleFailure(ctx, message))
                        .then(tasks.markFailed(task.id(), code, message)).then())
                .then();
    }

    private Mono<Void> failAllPending(VideoProductionTask task, String code, String message) {
        // 漂移让本批全部候选直接终态失败；afterTerminal 的全终态检查会收口任务并退款。
        return takes.findByStoryboard(task.storyboardId())
                .filter(take -> !take.isTerminal())
                .concatMap(take -> takes.markFailed(take.id(), code, message))
                .then(shots.findByStoryboard(task.storyboardId())
                        .filter(shot -> !VideoShot.STATUS_READY.equals(shot.status()))
                        .concatMap(shot -> shots.updateStatus(shot.id(), VideoShot.STATUS_FAILED)).then())
                .then(afterTerminalByIds(task));
    }

    private Mono<Void> afterTerminalByIds(VideoProductionTask task) {
        return takes.findByStoryboard(task.storyboardId()).collectList()
                .flatMap(all -> {
                    boolean allTerminal = !all.isEmpty()
                            && all.stream().allMatch(VideoShotTake::isTerminal);
                    boolean anySelectable = all.stream().anyMatch(VideoShotTake::isSelectable);
                    if (allTerminal && !anySelectable) {
                        return failTaskDirect(task, "provider_config_drift",
                                "任务创建后视频渠道配置已变化，拒绝执行");
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> failTaskDirect(VideoProductionTask task, String code, String message) {
        return taskService.rebuildContext(task)
                .flatMap(ctx -> executions.handleFailure(ctx, message))
                .then(tasks.markFailed(task.id(), code, message)).then();
    }

    /**
     * 首帧取图（#65 卡2）：AI 锚定图（anchor_media_id）优先——对象字节转 data URL；
     * 取不到（媒体被清理等）回落用户图。无锚定图时走请求快照 base64 图（1 基；0/越界=纯文生）。
     */
    private Mono<List<String>> anchorImages(VideoStoryboard storyboard, VideoShot shot) {
        if (shot.isAiAnchored()) {
            return mediaRefs.findById(shot.anchorMediaId())
                    .flatMap(reference -> Mono
                            .fromCallable(() -> storageProvider.getIfAvailable()
                                    .getObject(reference.objectKey()))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .map(bytes -> List.of("data:" + sniffImageMime(bytes) + ";base64,"
                            + Base64.getEncoder().encodeToString(bytes)))
                    .onErrorResume(error -> {
                        log.warn("ai anchor image fetch failed, fallback to payload image shotId={}",
                                shot.id(), error);
                        return Mono.just(payloadAnchorImages(storyboard, shot));
                    })
                    .defaultIfEmpty(payloadAnchorImages(storyboard, shot));
        }
        return Mono.just(payloadAnchorImages(storyboard, shot));
    }

    /** 锚定图：storyboard 请求快照 base64 图列表，1 基；0/越界=纯文生（§4.4）。 */
    private List<String> payloadAnchorImages(VideoStoryboard storyboard, VideoShot shot) {
        if (shot.isWithoutAnchorImage()) {
            return List.of();
        }
        try {
            JsonNode payload = mapper.readTree(storyboard.requestPayload());
            JsonNode images = payload.path("images");
            int index = shot.anchorImageIndex() - 1;
            if (!images.isArray() || index < 0 || index >= images.size()) {
                return List.of();
            }
            String image = images.get(index).asText("");
            return image.isEmpty() ? List.of() : List.of(image.startsWith("data:") ? image
                    : "data:image/jpeg;base64," + image);
        } catch (Exception error) {
            log.warn("anchor image extraction failed storyboardId={} shotId={}", storyboard.id(), shot.id(),
                    error);
            return List.of();
        }
    }

    private static String sniffImageMime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "image/webp";
        }
        return "image/png";
    }
}
