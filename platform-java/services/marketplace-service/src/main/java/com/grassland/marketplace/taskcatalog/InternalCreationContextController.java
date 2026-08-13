package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Authoritative task context handoff for intelligence creation snapshots.
 * The browser never calls this endpoint; only the intelligence service principal may do so.
 */
@RestController
public class InternalCreationContextController {

    public static final String INTELLIGENCE_SERVICE = "intelligence";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MarketplaceCallerResolver callers;
    private final TaskApplicationRepository applications;
    private final TaskRepository tasks;
    private final ObjectMapper mapper = new ObjectMapper();

    public InternalCreationContextController(MarketplaceCallerResolver callers,
                                              TaskApplicationRepository applications,
                                              TaskRepository tasks) {
        this.callers = callers;
        this.applications = applications;
        this.tasks = tasks;
    }

    /** Return the accepted engagement snapshot after checking the caller supplied account. */
    @PostMapping(value = "/internal/marketplace/engagements/{applicationId}/creation-context",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable String applicationId,
            @RequestBody CreationContextRequest body,
            ServerHttpRequest request) {
        return callers.requireServicePrincipal(request, INTELLIGENCE_SERVICE)
                .flatMap(ignored -> applications.findById(applicationId)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "履约不存在")))
                        .flatMap(application -> {
                            if (!"accepted".equals(application.status())) {
                                return Mono.error(new MarketplaceException(409, "该履约尚未接受"));
                            }
                            if (body == null || body.taskId() == null || body.recommenderAccountId() == null
                                    || !application.taskId().equals(body.taskId())
                                    || !application.recommenderAccountId().equals(body.recommenderAccountId())) {
                                return Mono.error(new MarketplaceException(403, "任务上下文参与方不匹配"));
                            }
                            return applications.findTaskContextSnapshot(application.id())
                                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "任务上下文快照尚未生成")))
                                    .flatMap(json -> tasks.findById(application.taskId())
                                            .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                                            .map(task -> response(json, task.organizationId())));
                        }));
    }

    private ResponseEntity<Map<String, Object>> response(String json, String organizationId) {
        try {
            Map<String, Object> context = mapper.readValue(json, MAP_TYPE);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskContext", context);
            data.put("organizationId", organizationId);
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (JsonProcessingException error) {
            throw new MarketplaceException(500, "任务上下文快照损坏");
        }
    }

    public record CreationContextRequest(String taskId, String recommenderAccountId) {}
}
