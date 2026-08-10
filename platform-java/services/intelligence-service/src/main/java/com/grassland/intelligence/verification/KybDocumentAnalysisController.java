package com.grassland.intelligence.verification;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/media")
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class KybDocumentAnalysisController {

    private final IntelligenceCallerResolver callers;
    private final KybDocumentAnalysisService analysis;

    public KybDocumentAnalysisController(
            IntelligenceCallerResolver callers, KybDocumentAnalysisService analysis) {
        this.callers = callers;
        this.analysis = analysis;
    }

    @PostMapping(value = "/{id}/kyb-document-analysis", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> analyze(
            @PathVariable String id, @RequestBody Request body, ServerWebExchange exchange) {
        UUID mediaId;
        try {
            mediaId = UUID.fromString(id);
        } catch (Exception error) {
            throw new IllegalArgumentException("媒体 ID 无效");
        }
        if (body == null || body.attachmentType() == null) {
            throw new IllegalArgumentException("证照类型不能为空");
        }
        UUID finalMediaId = mediaId;
        return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.IDENTITY_SERVICE)
                .flatMap(caller -> analysis.analyze(
                        finalMediaId, caller.organizationId(), body.attachmentType()))
                .map(result -> Map.of("success", true, "data", result));
    }

    public record Request(String attachmentType) {}
}

