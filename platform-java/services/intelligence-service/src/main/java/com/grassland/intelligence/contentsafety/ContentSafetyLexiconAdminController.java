package com.grassland.intelligence.contentsafety;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.contentsafety.ContentSafetyLexiconRepository.Version;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Platform-admin operations for creating, inspecting and activating lexicon versions. */
@RestController
@RequestMapping("/api/admin/content-safety/lexicons")
public class ContentSafetyLexiconAdminController {

    private final IntelligenceCallerResolver callers;
    private final ContentSafetyLexicon lexicons;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ContentSafetyLexiconAdminController(
            IntelligenceCallerResolver callers, ContentSafetyLexicon lexicons) {
        this.callers = callers;
        this.lexicons = lexicons;
    }

    @GetMapping
    public Mono<Map<String, Object>> list(ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .then(lexicons.repository().list().map(this::summary).collectList())
                .map(items -> success(Map.of("items", items)));
    }

    @GetMapping("/{id}")
    public Mono<Map<String, Object>> detail(@PathVariable String id, ServerWebExchange exchange) {
        UUID parsed = uuid(id);
        return callers.requireAdmin(exchange.getRequest())
                .then(lexicons.repository().findById(parsed))
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "词库版本不存在")))
                .map(version -> {
                    Map<String, Object> body = new LinkedHashMap<>(summary(version));
                    body.put("payload", payload(version.payload()));
                    return success(body);
                });
    }

    @PostMapping
    public Mono<Map<String, Object>> create(
            @RequestBody CreateRequest body, ServerWebExchange exchange) {
        return callers.requireAdmin(exchange.getRequest())
                .flatMap(caller -> lexicons.createDraft(
                        body == null ? null : body.label(),
                        body == null || body.payload() == null
                                ? null : mapper.valueToTree(body.payload()),
                        caller.accountId()))
                .map(version -> success(detailBody(version)));
    }

    @PostMapping("/{id}/activate")
    public Mono<Map<String, Object>> activate(@PathVariable String id, ServerWebExchange exchange) {
        UUID parsed = uuid(id);
        return callers.requireAdmin(exchange.getRequest())
                .then(lexicons.activate(parsed))
                .map(version -> success(detailBody(version)));
    }

    @PostMapping("/{id}/retire")
    public Mono<Map<String, Object>> retire(@PathVariable String id, ServerWebExchange exchange) {
        UUID parsed = uuid(id);
        return callers.requireAdmin(exchange.getRequest())
                .then(lexicons.retire(parsed))
                .map(version -> success(detailBody(version)));
    }

    private Map<String, Object> detailBody(Version version) {
        Map<String, Object> result = new LinkedHashMap<>(summary(version));
        result.put("payload", payload(version.payload()));
        return result;
    }

    private Map<String, Object> summary(Version version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", version.id());
        result.put("label", version.label());
        result.put("status", version.status());
        result.put("createdBy", version.createdBy());
        result.put("createdAt", version.createdAt());
        result.put("activatedAt", version.activatedAt());
        return result;
    }

    private Object payload(String value) {
        try {
            return mapper.readValue(value, Object.class);
        } catch (Exception error) {
            throw new IntelligenceException(500, "词库 payload 无法读取");
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception error) {
            throw new IntelligenceException(400, "词库版本标识无效");
        }
    }

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    record CreateRequest(String label, Map<String, Object> payload) {}
}
