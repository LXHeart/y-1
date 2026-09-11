package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-10（任务书 #100 C100-11）：PATCH /api/video-production/storyboards/{id}/sources。
 *
 * <p>批量保存每镜制作来源（1～30 项、不重复）；全部校验通过后与 expectedEditVersion
 * 同事务原子应用；返回 {storyboardId, editVersion, sources}（权威视图，缺行补 generated）。
 * 业务规则与校验在 {@link VideoShotSourceService}，本层只做 wire。
 */
@RestController
public class VideoShotSourceController {

    private final IntelligenceCallerResolver callers;
    private final VideoShotSourceService service;

    public VideoShotSourceController(IntelligenceCallerResolver callers, VideoShotSourceService service) {
        this.callers = callers;
        this.service = service;
    }

    public record SaveShotSourcesRequest(Long expectedEditVersion, List<Map<String, Object>> sources) {
    }

    @PatchMapping("/api/video-production/storyboards/{id}/sources")
    public Mono<ResponseEntity<Map<String, Object>>> saveSources(@PathVariable String id,
            @RequestBody SaveShotSourcesRequest body, ServerWebExchange exchange) {
        UUID storyboardId;
        try {
            storyboardId = UUID.fromString(id);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效"));
        }
        if (body == null || body.sources() == null || body.sources().isEmpty()) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "sources 不能为空"));
        }
        List<VideoShotSourceService.SourceItem> items = new ArrayList<>();
        for (Map<String, Object> raw : body.sources()) {
            Object shotId = raw.get("shotId");
            if (!(shotId instanceof String shotIdText)) {
                return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "shotId 必填"));
            }
            try {
                items.add(new VideoShotSourceService.SourceItem(UUID.fromString(shotIdText),
                        castSource(raw.get("source"))));
            } catch (IllegalArgumentException e) {
                return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "shotId 必须是 UUID"));
            }
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> service.saveSources(caller.accountId(), storyboardId,
                        body.expectedEditVersion(), items))
                .map(result -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                        "storyboardId", result.storyboardId().toString(),
                        "editVersion", result.editVersion(),
                        "sources", result.sources()))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSource(Object source) {
        if (source instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", "source 必须是对象");
    }
}
