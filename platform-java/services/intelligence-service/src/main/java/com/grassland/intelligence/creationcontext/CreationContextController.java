package com.grassland.intelligence.creationcontext;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** User-facing creation context snapshot API. There is deliberately no update endpoint. */
@RestController
@RequestMapping("/api/creation-contexts")
public class CreationContextController {
    private final IntelligenceCallerResolver callers;
    private final CreationContextService service;
    private final CreationContextSnapshotRepository snapshots;

    public CreationContextController(IntelligenceCallerResolver callers, CreationContextService service,
                                     CreationContextSnapshotRepository snapshots) {
        this.callers = callers;
        this.service = service;
        this.snapshots = snapshots;
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(
            @RequestBody CreationContextService.CreateCreationContextRequest body,
            ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> service.create(caller.accountId(), body))
                .map(snapshot -> success(toResponse(snapshot)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> snapshots.findById(id)
                        .filter(snapshot -> caller.accountId().equals(snapshot.accountId()))
                        .switchIfEmpty(Mono.error(new IntelligenceException(404, "创作上下文不存在"))))
                .map(snapshot -> success(toResponse(snapshot)));
    }

    static Map<String, Object> toResponse(CreationContextSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", snapshot.id());
        data.put("accountId", snapshot.accountId());
        data.put("organizationId", snapshot.organizationId());
        data.put("taskId", snapshot.taskId());
        data.put("applicationId", snapshot.applicationId());
        data.put("taskVersion", snapshot.taskVersion());
        data.put("platformId", snapshot.platformId());
        data.put("contentFormId", snapshot.contentFormId());
        data.put("taskSnapshot", snapshot.taskSnapshot());
        data.put("platformRulesSnapshot", snapshot.platformRulesSnapshot());
        data.put("materialSnapshot", snapshot.materialSnapshot());
        data.put("aiConfigSnapshot", snapshot.aiConfigSnapshot());
        // 任务书 #24：门店品牌块（无门店任务为空对象），前端 AI 中心上下文预览展示。
        data.put("storeBrandingSnapshot", snapshot.storeBrandingSnapshot());
        data.put("createdAt", snapshot.createdAt());
        return data;
    }

    private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
