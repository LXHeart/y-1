package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-11/12（任务书 #100 C100-14）：独立方案派生与列表。
 *
 * <p>派生原子创建新草稿/分镜/镜头（全独立 ID，不复制运行/选择/成片）；同键同参重放返回
 * 原结果。业务裁决在 {@link VideoStoryboardVariantService}，本层只做 wire。
 */
@RestController
public class VideoStoryboardVariantController {

    private final IntelligenceCallerResolver callers;
    private final VideoStoryboardVariantService service;

    public VideoStoryboardVariantController(IntelligenceCallerResolver callers,
            VideoStoryboardVariantService service) {
        this.callers = callers;
        this.service = service;
    }

    @PostMapping("/api/video-production/storyboards/{id}/variants")
    public Mono<ResponseEntity<Map<String, Object>>> derive(@PathVariable String id,
            @RequestBody VideoStoryboardVariantService.CreateVariantRequest body,
            ServerWebExchange exchange) {
        UUID storyboardId;
        try {
            storyboardId = UUID.fromString(id);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> service.derive(caller.accountId(), storyboardId, body))
                .map(result -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                        "variant", result.variant(), "project", result.project(),
                        "shotIdMap", result.shotIdMap()))));
    }

    @GetMapping("/api/video-production/storyboards/{id}/variants")
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String id,
            ServerWebExchange exchange) {
        UUID storyboardId;
        try {
            storyboardId = UUID.fromString(id);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> service.list(caller.accountId(), storyboardId))
                .map(items -> ResponseEntity.ok(Map.of("success", true,
                        "data", Map.of("items", items))));
    }
}
