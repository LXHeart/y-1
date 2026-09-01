package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.ai.run.AiRunRepository;
import com.grassland.intelligence.ai.run.ModelBudgetService;
import com.grassland.intelligence.ai.run.PriceTableService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 成片任务服务（任务书 #64 卡6）：POST /tasks 建任务（幂等、冻结计价、派生 takes/audios）、
 * 选片（selections / useRecommended）、单镜重抽（不追加计费）、取消（释放预留全额退）。
 *
 * <p>计费红线：预留/结算/退款只走 {@link AiExecutionService}；本类只写句柄与冻结参数。
 * P2 一口价：预估 = targetDurationSeconds × 冻结单秒价（video 模式取解析模型价目、
 * slideshow 模式取 slideshow-v1 价目）；多 take/重抽/TTS/BGM 不追加。
 */
@Service
public class VideoProductionTaskService {

    private static final Logger log = LoggerFactory.getLogger(VideoProductionTaskService.class);

    /** slideshow 模式的计价模型（本地渲染，走 sandbox provider 语义的执行环）。 */
    static final String SLIDESHOW_MODEL = "slideshow-v1";

    private final VideoStoryboardRepository storyboards;
    private final VideoShotRepository shots;
    private final VideoShotTakeRepository takes;
    private final VideoShotAudioRepository audios;
    private final VideoProductionTaskRepository tasks;
    private final VideoGenerationProviderResolver resolver;
    private final AiExecutionService executions;
    private final AiRunRepository runs;
    private final PriceTableService priceTable;
    private final VideoProductionPipelineProperties pipeline;

    public VideoProductionTaskService(VideoStoryboardRepository storyboards, VideoShotRepository shots,
            VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            VideoProductionTaskRepository tasks, VideoGenerationProviderResolver resolver,
            AiExecutionService executions, AiRunRepository runs, PriceTableService priceTable,
            VideoProductionPipelineProperties pipeline) {
        this.storyboards = storyboards;
        this.shots = shots;
        this.takes = takes;
        this.audios = audios;
        this.tasks = tasks;
        this.resolver = resolver;
        this.executions = executions;
        this.runs = runs;
        this.priceTable = priceTable;
        this.pipeline = pipeline;
    }

    public record CreateRequest(UUID storyboardId, String operationId) {}

    public Mono<VideoProductionTask> create(String accountId, String organizationId, CreateRequest request) {
        if (request == null || request.storyboardId() == null) {
            throw new IntelligenceException(400, "storyboardId 必填");
        }
        String operationId = request.operationId() == null || request.operationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.operationId().trim();
        return storyboards.findById(request.storyboardId(), accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
                .flatMap(storyboard -> createForStoryboard(accountId, organizationId, storyboard, operationId));
    }

    private Mono<VideoProductionTask> createForStoryboard(String accountId, String organizationId,
            VideoStoryboard storyboard, String operationId) {
        return shots.findByStoryboard(storyboard.id()).collectList()
                .flatMap(shotList -> {
                    if (shotList.isEmpty()) {
                        return Mono.error(new IntelligenceException(400, "分镜没有镜头，无法生成成片"));
                    }
                    return frozenBilling(accountId).flatMap(frozen -> reserveAndSpawn(accountId, organizationId,
                            storyboard, shotList, operationId, frozen));
                });
    }

    /** 冻结计价四元组 + 执行环 provider 解析（video 模式取控制面、slideshow 走 sandbox 语义）。 */
    private Mono<FrozenBilling> frozenBilling(String accountId) {
        return resolver.resolveVideoGeneration().map(video -> {
            if (video.available()) {
                var plan = video.plan();
                return new FrozenBilling(VideoProductionTask.MODE_VIDEO, plan.resolution().provider(),
                        plan.resolution().model(), plan.resolution().platformModelVersion(),
                        plan.unitPriceCents(), plan.priceTableVersion(), plan.resolution(), false);
            }
            // slideshow：本地渲染，按 slideshow-v1 价目一口价（缺价目行 fail-closed，不误配 0 价）
            int unitPriceCents;
            String priceTableVersion;
            try {
                unitPriceCents = priceOf(SLIDESHOW_MODEL);
                priceTableVersion = priceTableVersionLabel();
            } catch (RuntimeException error) {
                throw new IntelligenceException(503, "图文成片缺少价目配置（slideshow-v1）");
            }
            ProviderResolution resolution = ProviderResolution.platform(null, "sandbox",
                    "https://sandbox.invalid", SLIDESHOW_MODEL, 1, null);
            return new FrozenBilling(VideoProductionTask.MODE_SLIDESHOW, "sandbox", SLIDESHOW_MODEL,
                    1, unitPriceCents, priceTableVersion, resolution, true);
        });
    }

    private int priceOf(String model) {
        return priceTable.priceFor(null, model).centsPerSecond();
    }

    private String priceTableVersionLabel() {
        return priceTable.currentVersionLabel();
    }

    private Mono<VideoProductionTask> reserveAndSpawn(String accountId, String organizationId,
            VideoStoryboard storyboard, List<VideoShot> shotList, String operationId, FrozenBilling frozen) {
        int target = storyboard.targetDurationSeconds();
        int estimated = Math.multiplyExact(target, frozen.unitPriceCents());
        return tasks.create(storyboard.id(), accountId, organizationId, storyboard.contextSnapshotId(),
                        operationId, frozen.mode(), null, null, target, frozen.priceTableVersion(),
                        frozen.unitPriceCents(), estimated, frozen.provider(), frozen.model(),
                        frozen.platformModelVersion())
                .flatMap(created -> reserve(accountId, organizationId, storyboard, created, frozen, estimated)
                        .then(spawnRows(storyboard, shotList, created, frozen))
                        .thenReturn(created))
                .switchIfEmpty(Mono.defer(() -> tasks.findByAccountAndOperationId(accountId, operationId)
                        .flatMap(existing -> validateIdempotentReplay(existing, storyboard))));
    }

    private static Mono<VideoProductionTask> validateIdempotentReplay(VideoProductionTask existing,
            VideoStoryboard storyboard) {
        if (!existing.storyboardId().equals(storyboard.id())) {
            return Mono.error(new IntelligenceException(409, "幂等键已绑定到其他分镜"));
        }
        return Mono.just(existing);
    }

    /** P2 预留：video_generation + VIDEO_PRODUCTION_VIDEO，operationId=任务 id（幂等）。 */
    private Mono<Void> reserve(String accountId, String organizationId, VideoStoryboard storyboard,
            VideoProductionTask created, FrozenBilling frozen, int estimated) {
        return executions.prepareMediaExecution(accountId, organizationId,
                        VideoGenerationProviderResolver.CAPABILITY_VIDEO_GENERATION,
                        CreditFeature.VIDEO_PRODUCTION_VIDEO, frozen.resolution(), created.id(), estimated,
                        frozen.priceTableVersion(), storyboard.contextSnapshotId())
                .flatMap(result -> {
                    if (!result.allowed()) {
                        // 预留被拒：任务行立即终态失败，不留无 run 的僵尸行占幂等键
                        return tasks.markFailed(created.id(), "reservation_denied", result.denialReason())
                                .then(Mono.error(new IntelligenceException(402, result.denialReason())));
                    }
                    var budget = result.context().budgetReservation();
                    return tasks.attachRun(created.id(), result.context().runId(), budget.budgetId(),
                            budget.reservationDate(), budget.reservedCents());
                })
                .then();
    }

    /** 冻结分镜 + 派生候选/配音行。slideshow 的 take 行在卡8（zoompan 渲染）接入时派生。 */
    private Mono<Void> spawnRows(VideoStoryboard storyboard, List<VideoShot> shotList,
            VideoProductionTask created, FrozenBilling frozen) {
        Mono<Boolean> committed = storyboards.markCommitted(storyboard.id());
        Flux<VideoShotTake> takeRows = frozen.mode().equals(VideoProductionTask.MODE_VIDEO)
                ? Flux.fromIterable(shotList).concatMap(shot -> spawnTakes(shot, frozen, pipeline.getDefaultTakes()))
                : Flux.empty();
        Flux<VideoShotAudio> audioRows = Flux.fromIterable(shotList)
                .concatMap(shot -> audios.create(shot.id(), null, null));
        return committed.thenMany(takeRows).thenMany(audioRows)
                .then(tasks.updatePhase(created.id(), VideoProductionTask.PHASE_GENERATING, 1)).then();
    }

    private Flux<VideoShotTake> spawnTakes(VideoShot shot, FrozenBilling frozen, int count) {
        return Flux.range(1, count)
                .concatMap(takeNo -> takes.create(shot.id(), takeNo, frozen.provider(), frozen.model()));
    }

    /** 单镜重抽一批（不追加计费）；take_no 从该镜现有最大值续排。 */
    public Mono<Integer> regenerate(UUID taskId, String accountId, UUID shotId) {
        return ownedShotTask(taskId, accountId, shotId)
                .flatMap(task -> {
                    if (task.isTerminal()) {
                        return Mono.error(new IntelligenceException(409, "任务已结束，不能重抽"));
                    }
                    return takes.findByShot(shotId).collectList().flatMap(existing -> {
                        int base = existing.stream().mapToInt(VideoShotTake::takeNo).max().orElse(0);
                        return Flux.range(1, pipeline.getDefaultTakes())
                                .concatMap(offset -> takes.create(shotId, base + offset, task.provider(),
                                        task.model()))
                                .then(shots.updateStatus(shotId, VideoShot.STATUS_GENERATING))
                                .then(Mono.just(pipeline.getDefaultTakes()));
                    });
                });
    }

    /** 选片：selections 逐项校验归属与可选性；useRecommended 一键全选首成功候选。 */
    public Mono<Map<String, UUID>> select(UUID taskId, String accountId, List<Selection> selections,
            boolean useRecommended) {
        return tasks.findById(taskId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(task -> {
                    if (task.isTerminal()) {
                        return Mono.error(new IntelligenceException(409, "任务已结束，不能选片"));
                    }
                    return takes.findByStoryboard(task.storyboardId()).collectList().flatMap(all -> {
                        Map<String, UUID> chosen = useRecommended
                                ? recommendationFrom(all)
                                : validateSelections(selections, all);
                        String selectionJson = selectionJson(chosen);
                        return tasks.setSelection(taskId, accountId, selectionJson).thenReturn(chosen);
                    });
                });
    }

    /** 推荐预选 = 每镜首个成功且已归档的候选（§4.4）。 */
    public Map<String, UUID> recommendationFrom(List<VideoShotTake> allTakes) {
        Map<String, UUID> chosen = new LinkedHashMap<>();
        for (VideoShotTake take : allTakes) {
            if (take.isSelectable() && !chosen.containsKey(take.shotId().toString())) {
                chosen.put(take.shotId().toString(), take.id());
            }
        }
        return chosen;
    }

    private Map<String, UUID> validateSelections(List<Selection> selections, List<VideoShotTake> allTakes) {
        if (selections == null || selections.isEmpty()) {
            throw new IntelligenceException(400, "选片列表不能为空");
        }
        Map<String, UUID> chosen = new LinkedHashMap<>();
        for (Selection selection : selections) {
            if (selection == null || selection.shotId() == null || selection.takeId() == null) {
                throw new IntelligenceException(400, "选片项缺少 shotId 或 takeId");
            }
            VideoShotTake take = allTakes.stream()
                    .filter(candidate -> candidate.id().equals(selection.takeId())
                            && candidate.shotId().equals(selection.shotId()))
                    .findFirst()
                    .orElseThrow(() -> new IntelligenceException(404, "候选不存在或不属于该镜头"));
            if (!take.isSelectable()) {
                throw new IntelligenceException(409, "候选未成功归档，不能选用");
            }
            chosen.put(selection.shotId().toString(), selection.takeId());
        }
        return chosen;
    }

    /** 取消：任务行先落 cancelled（与 worker 的 lease 互斥），再收口候选、释放预留（全额退）。 */
    public Mono<Boolean> cancel(UUID taskId, String accountId) {
        return tasks.findById(taskId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(task -> {
                    if (task.isTerminal()) {
                        return Mono.error(new IntelligenceException(409, "任务已结束"));
                    }
                    return tasks.cancel(taskId, accountId)
                            .flatMap(cancelled -> cancelled
                                    ? takes.cancelPendingByStoryboard(task.storyboardId())
                                            .then(releaseReservation(task, "video production task cancelled"))
                                            .thenReturn(true)
                                    : Mono.just(false));
                });
    }

    private Mono<Void> releaseReservation(VideoProductionTask task, String reason) {
        if (!task.isBilled()) {
            return Mono.empty();
        }
        return rebuildContext(task)
                .flatMap(ctx -> executions.handleFailure(ctx, reason))
                .onErrorResume(error -> {
                    log.error("task cancel reservation release failed taskId={}", task.id(), error);
                    return Mono.empty();
                })
                .then();
    }

    /** worker 结算/退款共用：从任务行句柄重建 ExecutionContext（video worker 同款）。 */
    public Mono<AiExecutionService.ExecutionContext> rebuildContext(VideoProductionTask task) {
        if (task.runId() == null) {
            return Mono.empty();
        }
        return runs.findById(task.runId()).map(run -> {
            ProviderResolution resolution = ProviderResolution.platform(null, task.provider(),
                    null, task.model(),
                    task.platformModelVersion() == null ? 0 : task.platformModelVersion(), null);
            ModelBudgetService.BudgetCheckResult budget = ModelBudgetService.BudgetCheckResult.allowed(
                    task.budgetId(), task.budgetReservationDate(), 0,
                    task.reservedCostCents() == null ? 0 : task.reservedCostCents());
            return new AiExecutionService.ExecutionContext(
                    task.runId(), task.organizationId(), task.accountId(),
                    VideoGenerationProviderResolver.CAPABILITY_VIDEO_GENERATION, resolution, budget,
                    task.id(), null, CreditFeature.VIDEO_PRODUCTION_VIDEO, true, null,
                    task.pricingVersion(), 0, 0, run.creditsCentsPolicyVersion());
        });
    }

    /** 归属校验：任务属账号 + 镜头属任务分镜。 */
    private Mono<VideoProductionTask> ownedShotTask(UUID taskId, String accountId, UUID shotId) {
        return tasks.findById(taskId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(task -> shots.findByIdForAccount(shotId, accountId)
                        .filter(shot -> shot.storyboardId().equals(task.storyboardId()))
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "镜头不属于该任务分镜")))
                        .thenReturn(task));
    }

    private static String selectionJson(Map<String, UUID> chosen) {
        try {
            Map<String, String> raw = new LinkedHashMap<>();
            chosen.forEach((shotId, takeId) -> raw.put(shotId, takeId.toString()));
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception error) {
            throw new IllegalStateException("选片序列化失败", error);
        }
    }

    public record Selection(UUID shotId, UUID takeId) {}

    private record FrozenBilling(
            String mode, String provider, String model, Integer platformModelVersion,
            int unitPriceCents, String priceTableVersion, ProviderResolution resolution,
            boolean sandboxProvider) {
    }
}
