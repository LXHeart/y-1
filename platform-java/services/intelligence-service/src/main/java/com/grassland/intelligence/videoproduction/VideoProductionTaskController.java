package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
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

    public VideoProductionTaskController(IntelligenceCallerResolver callers,
            VideoProductionTaskService taskService, VideoProductionTaskRepository tasks,
            VideoStoryboardRepository storyboards, VideoShotRepository shots,
            VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
            @Value("${media.download-url-ttl-seconds:300}") long downloadUrlTtlSeconds) {
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
                .map(task -> Map.of("success", true, "data", summary(task)));
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
                .flatMap(caller -> taskService.select(id, caller.accountId(), selections, useRecommended))
                .map(chosen -> Map.of("success", true, "data", Map.of("selection", chosen)));
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
                .flatMap(caller -> taskService.reroll(id, caller.accountId(), shotId))
                .map(created -> ResponseEntity.accepted().body(Map.of("success", true,
                        "data", Map.of("takes", created.stream().map(this::takeView).toList()))));
    }

    private Map<String, Object> takeView(VideoShotTake take) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", take.id().toString());
        view.put("takeNo", take.takeNo());
        view.put("status", take.status());
        view.put("provider", take.provider());
        view.put("model", take.model());
        view.put("selectable", take.isSelectable());
        return view;
    }

    @PostMapping("/api/video-production/tasks/{id}/cancel")
    public Mono<Map<String, Object>> cancel(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> taskService.cancel(id, caller.accountId()))
                .flatMap(ok -> ok
                        ? Mono.just(Map.of("success", true, "data", Map.of("cancelled", true)))
                        : Mono.error(new IntelligenceException(409, "任务正在执行，请稍后再试")));
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
                                null, task.targetDurationSeconds(), null, null, "missing", null, null))),
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
