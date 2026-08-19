package com.grassland.intelligence.creationlineage;

import com.grassland.intelligence.creationlineage.CreationGeneration.Kind;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Owner-scoped read API for immutable generation lineage. */
@RestController
@RequestMapping("/api/creation-generations")
public class CreationGenerationController {

    private final IntelligenceCallerResolver callers;
    private final CreationGenerationRepository generations;
    private final MediaReferenceRepository media;

    public CreationGenerationController(
            IntelligenceCallerResolver callers,
            CreationGenerationRepository generations,
            MediaReferenceRepository media) {
        this.callers = callers;
        this.generations = generations;
        this.media = media;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String before,
            ServerWebExchange exchange) {
        Kind parsedKind = parseKind(kind);
        UUID cursor = parseUuid(before, "分页游标无效");
        int safeLimit = Math.max(1, Math.min(limit, 50));
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> generations.listForOwner(
                                caller.accountId(), parsedKind, safeLimit + 1, cursor)
                        .collectList())
                .map(items -> {
                    boolean hasMore = items.size() > safeLimit;
                    List<CreationGeneration> page = hasMore ? items.subList(0, safeLimit) : items;
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("items", page.stream().map(CreationGenerationController::summary).toList());
                    data.put("nextBefore", hasMore && !page.isEmpty()
                            ? page.getLast().id().toString() : null);
                    return success(data);
                });
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable String id, ServerWebExchange exchange) {
        UUID generationId = parseRequiredUuid(id, "生成记录标识无效");
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> generations.findByIdAndOwner(generationId, caller.accountId())
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "生成记录不存在"))))
                .flatMap(value -> media.findAvailableIds(value.resultMediaIds()).collectList()
                        .map(available -> success(detail(value, Set.copyOf(available)))));
    }

    private static Map<String, Object> summary(CreationGeneration value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.id());
        item.put("kind", value.kind().db());
        item.put("mode", value.mode().db());
        item.put("provider", value.provider());
        item.put("model", value.model());
        item.put("resultTitle", resultTitle(value));
        item.put("createdAt", value.createdAt());
        return item;
    }

    private static Map<String, Object> detail(CreationGeneration value, Set<UUID> available) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", value.id());
        item.put("kind", value.kind().db());
        item.put("mode", value.mode().db());
        item.put("contextSnapshotId", value.contextSnapshotId());
        item.put("aiRunId", value.aiRunId());
        item.put("resolution", value.resolution().db());
        item.put("provider", value.provider());
        item.put("model", value.model());
        item.put("platformModelVersion", value.platformModelVersion());
        item.put("upstreamRunId", value.upstreamRunId());
        item.put("promptText", value.promptText());
        item.put("inputSummary", value.inputSummary());
        item.put("result", value.result());
        item.put("resultMedia", resultMedia(value, available));
        item.put("createdAt", value.createdAt());
        return item;
    }

    private static List<Map<String, Object>> resultMedia(
            CreationGeneration value, Set<UUID> available) {
        List<?> images = value.result().get("images") instanceof List<?> list ? list : List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < value.resultMediaIds().size(); index++) {
            UUID mediaId = value.resultMediaIds().get(index);
            Object image = index < images.size() ? images.get(index) : null;
            String imageUrl = image instanceof Map<?, ?> map && map.get("imageUrl") != null
                    ? String.valueOf(map.get("imageUrl")) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mediaId", mediaId);
            item.put("imageUrl", imageUrl);
            item.put("available", available.contains(mediaId));
            result.add(item);
        }
        return List.copyOf(result);
    }

    private static String resultTitle(CreationGeneration value) {
        if (value.kind() == Kind.VIDEO_ADAPTATION) {
            Object title = value.result().get("adaptedTitle");
            if (title == null || String.valueOf(title).isBlank()) title = value.result().get("adaptedSummary");
            String text = title == null ? "视频改编" : String.valueOf(title);
            return text.length() <= 80 ? text : text.substring(0, 80);
        }
        Object raw = value.result().get("images");
        int count = raw instanceof List<?> list ? list.size() : value.resultMediaIds().size();
        return count + " 张图片";
    }

    private static Kind parseKind(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Kind.fromDb(value.trim());
        } catch (IllegalArgumentException error) {
            throw new IntelligenceException(400, "生成类型无效");
        }
    }

    private static UUID parseUuid(String value, String message) {
        if (value == null || value.isBlank()) return null;
        return parseRequiredUuid(value, message);
    }

    private static UUID parseRequiredUuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (Exception error) {
            throw new IntelligenceException(400, message);
        }
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }
}
