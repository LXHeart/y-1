package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.orchestration.SelectionPayload;
import com.grassland.intelligence.orchestration.VideoOrchestrationGate;
import com.grassland.intelligence.orchestration.VideoTaskSpec;
import com.grassland.intelligence.orchestration.VideoWorkflowStarter;
import com.grassland.intelligence.videoproduction.export.ExportBundleService;
import com.grassland.intelligence.videoproduction.export.JianyingDraftBuilder;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 分镜成片任务端点（任务书 #64 卡6，§4.4 契约）：建任务、详情（storyboard+shots+takes+audio+
 * selection）、选片、单镜重抽、取消、历史列表。compose/subtitle 端点在卡8 接线。
 */
@RestController
public class VideoProductionTaskController {

    private final IntelligenceCallerResolver callers;
    private final VideoProductionTaskService taskService;
    private final VideoProductionTaskRepository tasks;
    private final VideoStoryboardRepository storyboards;
    private final VideoShotRepository shots;
    private final VideoShotTakeRepository takes;
    private final VideoShotAudioRepository audios;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final long downloadUrlTtlSeconds;
    private final VideoOrchestrationGate orchestration;
    private final VideoWorkflowStarter workflows;
    private final ExportBundleService exports;

    public VideoProductionTaskController(IntelligenceCallerResolver callers,
            VideoProductionTaskService taskService, VideoProductionTaskRepository tasks,
            VideoStoryboardRepository storyboards, VideoShotRepository shots,
            VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
            @Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds,
            VideoOrchestrationGate orchestration, VideoWorkflowStarter workflows,
            ExportBundleService exports) {
        this.callers = callers;
        this.taskService = taskService;
        this.tasks = tasks;
        this.storyboards = storyboards;
        this.shots = shots;
        this.takes = takes;
        this.audios = audios;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
        this.downloadUrlTtlSeconds = Math.max(1L, downloadUrlTtlSeconds);
        this.orchestration = orchestration;
        this.workflows = workflows;
        this.exports = exports;
    }

    public record CreateTaskRequest(UUID storyboardId, String operationId) {}

    public record SelectRequest(List<VideoProductionTaskService.Selection> selections,
            Boolean useRecommended) {}

    @PostMapping("/api/video-production/tasks")
    public Mono<Map<String, Object>> create(@RequestBody CreateTaskRequest body, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.create(caller.accountId(), caller.organizationId(),
                        new VideoProductionTaskService.CreateRequest(
                                body == null ? null : body.storyboardId(),
                                body == null ? null : body.operationId())))
                .flatMap(task -> startWorkflowAfterCreate(task, VideoTaskSpec.KIND_INITIAL, 0)
                        .thenReturn(task))
                .map(task -> Map.of("success", true, "data", summary(task)));
    }

    /** 卡A1 双入口：开关 temporal 时新任务起 workflow（legacy 旧行为零变化；A4 后唯一驱动路径）。 */
    private Mono<Void> startWorkflowAfterCreate(VideoProductionTask task, String kind, int recomposeSeq) {
        if (!orchestration.temporal()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> workflows.startForTask(task, kind, recomposeSeq))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    // 起流失败不吞创建：行在，收养清扫（下一拍）兜底补起
                    return Mono.<Void>empty();
                });
    }

    @GetMapping("/api/video-production/tasks/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> tasks.findById(id, caller.accountId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(this::detailBody)
                .map(data -> Map.of("success", true, "data", data));
    }

    @PostMapping("/api/video-production/tasks/{id}/takes/select")
    public Mono<Map<String, Object>> select(@PathVariable UUID id, @RequestBody SelectRequest body,
            ServerWebExchange exchange) {
        boolean useRecommended = body != null && Boolean.TRUE.equals(body.useRecommended());
        List<VideoProductionTaskService.Selection> selections = body == null ? List.of() : body.selections();
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.select(id, caller.accountId(), selections, useRecommended)
                        .flatMap(chosen -> signalSelection(id, chosen)
                                .thenReturn(Map.of("success", true,
                                        "data", Map.of("selection", chosen)))));
    }

    /** 卡A1：选片落库后向 workflow 发 submitSelections 信号（尽力而为，行是真相源）。 */
    private Mono<Void> signalSelection(UUID taskId, Map<String, UUID> chosen) {
        if (!orchestration.temporal()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> workflows.signalSelection(
                        VideoWorkflowStarter.workflowId(taskId), SelectionPayload.of(chosen)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> Mono.<Void>empty());
    }

    @PostMapping("/api/video-production/tasks/{id}/shots/{shotId}/regenerate")
    public Mono<Map<String, Object>> regenerate(@PathVariable UUID id, @PathVariable UUID shotId,
            ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.regenerate(id, caller.accountId(), shotId))
                .map(created -> Map.of("success", true, "data", Map.of("createdTakes", created)));
    }

    /** 成片后单镜重抽（#65 卡6，§3 契约）：202 + 新候选列表；之后走既有 select + compose。 */
    @PostMapping("/api/video-production/tasks/{id}/shots/{shotId}/reroll")
    public Mono<ResponseEntity<Map<String, Object>>> reroll(@PathVariable UUID id, @PathVariable UUID shotId,
            ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.reroll(id, caller.accountId(), shotId)
                        .flatMap(created -> rerollWorkflow(id, shotId, caller.accountId())
                                .thenReturn(created)))
                .map(created -> ResponseEntity.accepted().body(Map.of("success", true,
                        "data", Map.of("takes", created.stream().map(this::takeView).toList()))));
    }

    /** 卡A1：原工作流已随 succeeded 关闭——重抽由第二春工作流 video-task-{id}-r{n} 驱动。 */
    private Mono<Void> rerollWorkflow(UUID taskId, UUID shotId, String accountId) {
        if (!orchestration.temporal()) {
            return Mono.empty();
        }
        return tasks.findById(taskId, accountId)
                .flatMap(task -> startWorkflowAfterCreate(task, VideoTaskSpec.KIND_REROLL,
                        task.recomposeSeq()))
                .then(Mono.<Void>fromRunnable(() -> workflows.signalReroll(
                                VideoWorkflowStarter.workflowId(taskId), shotId.toString()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .onErrorResume(error -> Mono.<Void>empty()));
    }

    private Map<String, Object> takeView(VideoShotTake take) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", take.id().toString());
        view.put("takeNo", take.takeNo());
        view.put("status", take.status());
        view.put("provider", take.provider());
        view.put("model", take.model());
        view.put("selectable", take.isSelectable());
        view.put("score", take.score());
        view.put("scoreLabels", parseScoreLabels(take.scoreLabels()));
        return view;
    }

    /** 评分标签 jsonb 原文 → 字符串数组（解析失败给空数组，前端不渲染角标）。 */
    private static List<String> parseScoreLabels(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (java.io.IOException parseFailure) {
            return List.of();
        }
    }

    @PostMapping("/api/video-production/tasks/{id}/cancel")
    public Mono<Map<String, Object>> cancel(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.cancel(id, caller.accountId())
                        .flatMap(ok -> ok
                                ? signalCancel(id).thenReturn(cancelledBody())
                                : Mono.error(new IntelligenceException(409, "任务正在执行，请稍后再试"))));
    }

    private static Map<String, Object> cancelledBody() {
        return Map.of("success", true, "data", Map.of("cancelled", true));
    }

    /** 卡A1：行已取消退款，信号只叫醒等待中的 workflow。 */
    private Mono<Void> signalCancel(UUID taskId) {
        if (!orchestration.temporal()) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> workflows.signalCancel(
                        VideoWorkflowStarter.workflowId(taskId), "user cancelled"))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> Mono.<Void>empty());
    }

    /** 合成（卡8）：校验选片 → phase=composing（异步执行，前端轮询详情）。 */
    @PostMapping("/api/video-production/tasks/{id}/compose")
    public Mono<Map<String, Object>> compose(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.requestCompose(id, caller.accountId()))
                .map(task -> Map.of("success", true, "data", summary(task)));
    }

    /** SRT 下载（卡8，P4）：presign 短链（带附件文件名）。 */
    @GetMapping("/api/video-production/tasks/{id}/subtitle")
    public Mono<Map<String, Object>> subtitle(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> tasks.findById(id, caller.accountId()))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .map(task -> {
                    if (task.srtMediaId() == null) {
                        throw new IntelligenceException(404, "字幕尚未生成");
                    }
                    return mediaRefs.findById(task.srtMediaId())
                            .switchIfEmpty(Mono.error(new IntelligenceException(404, "字幕尚未生成")))
                            .map(reference -> {
                                ObjectStorageAdapter storage = storageProvider.getIfAvailable();
                                if (storage == null) {
                                    throw new IntelligenceException(503, "对象存储未启用");
                                }
                                java.net.URI url = storage.presignDownload(reference.objectKey(),
                                        downloadUrlTtlSeconds,
                                        "attachment; filename=\"video-subtitle-" + task.id() + ".srt\"");
                                return Map.<String, Object>of("success", true, "data", Map.of(
                                        "downloadUrl", url.toString(),
                                        "expiresInSeconds", downloadUrlTtlSeconds));
                            });
                })
                .flatMap(mono -> mono);
    }

    /**
     * B 轨通用素材包导出（任务书 #66 卡B1）：zip 汇集（分镜稿/逐镜音频/SRT/逐镜段/成片）
     * 写对象存储后一次性 presign。属主校验与服务内（非属主 404 同详情端点口径）。
     */
    @GetMapping("/api/video-production/tasks/{id}/export/bundle")
    public Mono<Map<String, Object>> exportBundle(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> exports.exportBundle(id, caller.accountId(), downloadUrlTtlSeconds))
                .map(artifact -> Map.of("success", true, "data", Map.of(
                        "downloadUrl", artifact.downloadUrl(),
                        "expiresInSeconds", artifact.expiresInSeconds(),
                        "kind", "bundle",
                        "entryCount", artifact.entryCount())));
    }

    /**
     * A 轨剪映草稿导出（任务书 #66 卡B2）：draft_content.json 三轨最小集 + meta + 媒体副本。
     * 响应携带支持版本区间供前端展示「建议剪映专业版 {range}」。
     */
    @GetMapping("/api/video-production/tasks/{id}/export/jianying")
    public Mono<Map<String, Object>> exportJianying(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> exports.exportJianying(id, caller.accountId(), downloadUrlTtlSeconds))
                .map(artifact -> Map.of("success", true, "data", Map.of(
                        "downloadUrl", artifact.downloadUrl(),
                        "expiresInSeconds", artifact.expiresInSeconds(),
                        "kind", "jianying",
                        "supportedVersionRange", JianyingDraftBuilder.SUPPORTED_JIANYING_RANGE,
                        "draftName", artifact.draftName())));
    }

    @GetMapping("/api/video-production/tasks")
    public Mono<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize, ServerWebExchange exchange) {
        int safePageSize = Math.min(50, Math.max(1, pageSize));
        int safePage = Math.max(1, page);
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> tasks.countByAccount(caller.accountId())
                        .flatMap(total -> tasks.findByAccount(caller.accountId(), safePageSize,
                                        (safePage - 1) * (long) safePageSize)
                                .map(this::historyItem)
                                .collectList()
                                .map(items -> {
                                    Map<String, Object> data = new LinkedHashMap<>();
                                    data.put("items", items);
                                    data.put("total", total);
                                    data.put("page", safePage);
                                    data.put("pageSize", safePageSize);
                                    return Map.of("success", true, "data", data);
                                })));
    }

    // ---------------- 载荷装配 ----------------

    private Map<String, Object> historyItem(VideoProductionTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.id().toString());
        item.put("storyboardId", task.storyboardId().toString());
        item.put("mode", task.mode());
        item.put("phase", task.phase());
        item.put("progress", task.progress());
        item.put("targetDurationSeconds", task.targetDurationSeconds());
        item.put("actualDurationSeconds", task.actualDurationSeconds());
        item.put("estimatedCostCents", task.estimatedCostCents());
        item.put("actualCostCents", task.actualCostCents());
        item.put("unitPriceCents", task.unitPriceCents());
        item.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        item.put("completedAt", task.completedAt() == null ? null : task.completedAt().toString());
        item.put("errorCode", task.errorCode());
        item.put("errorMessage", task.errorMessage());
        return item;
    }

    private Map<String, Object> summary(VideoProductionTask task) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", task.id().toString());
        data.put("storyboardId", task.storyboardId().toString());
        data.put("mode", task.mode());
        data.put("phase", task.phase());
        data.put("progress", task.progress());
        data.put("targetDurationSeconds", task.targetDurationSeconds());
        data.put("estimatedCostCents", task.estimatedCostCents());
        data.put("unitPriceCents", task.unitPriceCents());
        data.put("operationId", task.operationId());
        return data;
    }

    private Mono<Map<String, Object>> detailBody(VideoProductionTask task) {
        return Mono.zip(
                storyboards.findById(task.storyboardId()).switchIfEmpty(Mono.just(
                        new VideoStoryboard(task.storyboardId(), task.accountId(), task.organizationId(),
                                null, task.targetDurationSeconds(), null, null, "missing", null, null, null))),
                shots.findByStoryboard(task.storyboardId()).collectList(),
                takes.findByStoryboard(task.storyboardId()).collectList(),
                audios.findByStoryboard(task.storyboardId()).collectList())
                .flatMap(tuple -> mediaReferences(task, tuple.getT2(), tuple.getT3())
                        .map(refs -> assemble(task, tuple.getT2(), tuple.getT3(), tuple.getT4(), refs)));
    }

    /** 媒体引用一次取齐（事件循环上不能逐个 block 查库）；AI 锚定图（#65 卡2）一并入表供 presign。 */
    private Mono<Map<UUID, MediaReference>> mediaReferences(VideoProductionTask task,
            List<VideoShot> shotList, List<VideoShotTake> takeList) {
        List<UUID> mediaIds = new ArrayList<>();
        takeList.stream().filter(VideoShotTake::isSelectable).map(VideoShotTake::mediaId).forEach(mediaIds::add);
        if (task.finalMediaId() != null) {
            mediaIds.add(task.finalMediaId());
        }
        if (task.srtMediaId() != null) {
            mediaIds.add(task.srtMediaId());
        }
        shotList.stream().filter(VideoShot::isAiAnchored).map(VideoShot::anchorMediaId).forEach(mediaIds::add);
        return Flux.fromIterable(mediaIds)
                .flatMap(mediaId -> mediaRefs.findById(mediaId)
                        .map(reference -> Map.entry(mediaId, reference)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Map<String, Object> assemble(VideoProductionTask task, List<VideoShot> shotList,
            List<VideoShotTake> takeList, List<VideoShotAudio> audioList,
            Map<UUID, MediaReference> references) {
        Map<String, Object> data = summary(task);
        data.put("actualCostCents", task.actualCostCents());
        data.put("actualDurationSeconds", task.actualDurationSeconds());
        data.put("finalMediaId", task.finalMediaId() == null ? null : task.finalMediaId().toString());
        data.put("srtMediaId", task.srtMediaId() == null ? null : task.srtMediaId().toString());
        data.put("createdAt", task.createdAt() == null ? null : task.createdAt().toString());
        data.put("completedAt", task.completedAt() == null ? null : task.completedAt().toString());
        data.put("errorCode", task.errorCode());
        data.put("errorMessage", task.errorMessage());
        data.put("selection", parseSelection(task.selection()));
        data.put("finalUrl", presignUrl(task.finalMediaId(), references));
        data.put("subtitleUrl", presignUrl(task.srtMediaId(), references));

        Map<String, VideoShotAudio> audioByShot = new LinkedHashMap<>();
        for (VideoShotAudio audio : audioList) {
            audioByShot.put(audio.shotId().toString(), audio);
        }
        Map<String, List<VideoShotTake>> takesByShot = new LinkedHashMap<>();
        for (VideoShotTake take : takeList) {
            takesByShot.computeIfAbsent(take.shotId().toString(), key -> new ArrayList<>()).add(take);
        }
        Map<String, UUID> recommended = new LinkedHashMap<>();
        for (VideoShot shot : shotList) {
            takesByShot.getOrDefault(shot.id().toString(), List.of()).stream()
                    .filter(VideoShotTake::isSelectable)
                    .findFirst()
                    .ifPresent(first -> recommended.put(shot.id().toString(), first.id()));
        }
        data.put("recommended", recommended);

        List<Map<String, Object>> shotPayloads = new ArrayList<>();
        for (VideoShot shot : shotList) {
            Map<String, Object> shotPayload = new LinkedHashMap<>();
            shotPayload.put("id", shot.id().toString());
            shotPayload.put("seq", shot.seq());
            shotPayload.put("visual", shot.visual());
            shotPayload.put("narration", shot.narration());
            shotPayload.put("plannedSeconds", shot.plannedSeconds());
            shotPayload.put("cameraMove", shot.cameraMove());
            shotPayload.put("anchorImageIndex", shot.anchorImageIndex());
            shotPayload.put("prompt", shot.prompt());
            shotPayload.put("status", shot.status());
            // #65 卡2 契约：anchorSource / anchorMediaId（+ 预览 presign）
            shotPayload.put("anchorSource",
                    shot.anchorSource() == null ? VideoShot.ANCHOR_SOURCE_USER : shot.anchorSource());
            shotPayload.put("anchorMediaId",
                    shot.anchorMediaId() == null ? null : shot.anchorMediaId().toString());
            shotPayload.put("anchorUrl", presignUrl(shot.anchorMediaId(), references));
            VideoShotAudio audio = audioByShot.get(shot.id().toString());
            Map<String, Object> audioPayload = new LinkedHashMap<>();
            audioPayload.put("status", audio == null ? null : audio.status());
            audioPayload.put("provider", audio == null ? null : audio.provider());
            audioPayload.put("model", audio == null ? null : audio.model());
            audioPayload.put("durationMs", audio == null ? null : audio.durationMs());
            shotPayload.put("audio", audioPayload);
            List<Map<String, Object>> takePayloads = new ArrayList<>();
            for (VideoShotTake take : takesByShot.getOrDefault(shot.id().toString(), List.of())) {
                Map<String, Object> takePayload = new LinkedHashMap<>();
                takePayload.put("id", take.id().toString());
                takePayload.put("takeNo", take.takeNo());
                takePayload.put("status", take.status());
                takePayload.put("attempts", take.attempts());
                takePayload.put("provider", take.provider());
                takePayload.put("model", take.model());
                takePayload.put("mediaId", take.mediaId() == null ? null : take.mediaId().toString());
                takePayload.put("durationMs", take.durationMs());
                takePayload.put("errorCode", take.errorCode());
                takePayload.put("errorMessage", take.errorMessage());
                takePayload.put("selectable", take.isSelectable());
                takePayload.put("url", take.isSelectable()
                        ? presignUrl(take.mediaId(), references) : null);
                takePayloads.add(takePayload);
            }
            shotPayload.put("takes", takePayloads);
            shotPayloads.add(shotPayload);
        }
        data.put("shots", shotPayloads);
        return data;
    }

    private static Map<String, String> parseSelection(String selectionJson) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (selectionJson == null || selectionJson.isBlank()) {
            return parsed;
        }
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(selectionJson)
                    .fields().forEachRemaining(entry -> parsed.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception ignored) {
            // 脏 selection 只降级为空展示，不炸详情
        }
        return parsed;
    }

    /** 本地 presign（无网络 IO）：无存储/未归档返回 null，前端按未就绪处理。 */
    private String presignUrl(UUID mediaId, Map<UUID, MediaReference> references) {
        if (mediaId == null) {
            return null;
        }
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        MediaReference reference = references.get(mediaId);
        if (storage == null || reference == null) {
            return null;
        }
        try {
            return storage.presignDownload(reference.objectKey(), downloadUrlTtlSeconds).toString();
        } catch (RuntimeException error) {
            return null;
        }
    }
}
