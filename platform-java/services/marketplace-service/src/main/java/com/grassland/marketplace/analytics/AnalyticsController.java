package com.grassland.marketplace.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.analytics.AnalyticsModels.Event;
import com.grassland.marketplace.analytics.AnalyticsModels.EventRegistration;
import com.grassland.marketplace.analytics.AnalyticsModels.RecordEventRequest;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class AnalyticsController {
    private final MarketplaceCallerResolver callers;
    private final TaskResourceAuthorization authorization;
    private final TaskRepository tasks;
    private final AnalyticsRepository analytics;
    private final ObjectMapper mapper;

    public AnalyticsController(MarketplaceCallerResolver callers, TaskResourceAuthorization authorization,
                               TaskRepository tasks, AnalyticsRepository analytics, ObjectMapper mapper) {
        this.callers = callers;
        this.authorization = authorization;
        this.tasks = tasks;
        this.analytics = analytics;
        this.mapper = mapper;
    }

    /** Sandbox/manual collection path. It only affects analytics and is never consumed by payment or settlement. */
    @PostMapping("/api/analytics/events")
    public Mono<ResponseEntity<Map<String, Object>>> record(
            @RequestBody RecordEventRequest body, ServerHttpRequest request) {
        return Mono.defer(() -> {
            AnalyticsRepository.validate(body);
            return callers.requireUser(request)
                .flatMap(caller -> authorization.requireScope(caller, body.organizationId(), body.storeId(), "staff")
                        .then(validateTaskScope(body))
                        .then(analytics.record(body, caller.accountId())))
                .flatMap(result -> validateReplay(result, body))
                .map(result -> ResponseEntity.status(result.created() ? 201 : 200)
                        .body(success(Map.of("created", result.created(), "event", eventBody(result.event())))));
        });
    }

    @GetMapping("/api/admin/analytics/business")
    public Mono<ResponseEntity<Map<String, Object>>> business(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(analytics.report(organizationId, blank(storeId), from, to))
                .map(report -> ResponseEntity.ok(success(report)));
    }

    @GetMapping("/api/admin/analytics/recommenders")
    public Mono<ResponseEntity<Map<String, Object>>> recommenders(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .thenMany(analytics.recommenderReport(organizationId, blank(storeId), from, to))
                .collectList().map(items -> ResponseEntity.ok(success(items)));
    }

    private Mono<Void> validateTaskScope(RecordEventRequest body) {
        if (body.taskId() == null || body.taskId().isBlank()) return Mono.empty();
        return tasks.findById(body.taskId())
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                .flatMap(task -> Objects.equals(task.organizationId(), body.organizationId())
                                && Objects.equals(task.storeId(), blank(body.storeId()))
                        ? Mono.empty() : Mono.error(new MarketplaceException(409, "任务与分析范围不一致")));
    }

    private Mono<EventRegistration> validateReplay(EventRegistration result, RecordEventRequest request) {
        Event event = result.event();
        if (!result.created() && (!Objects.equals(event.organizationId(), request.organizationId())
                || !Objects.equals(event.storeId(), blank(request.storeId()))
                || !Objects.equals(event.taskId(), blank(request.taskId()))
                || !Objects.equals(event.eventType(), request.eventType())
                || event.valueCents() != (request.valueCents() == null ? 0L : request.valueCents()))) {
            return Mono.error(new MarketplaceException(409, "idempotencyKey 已被不同事件使用"));
        }
        return Mono.just(result);
    }

    private Map<String, Object> eventBody(Event event) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", event.id()); body.put("idempotencyKey", event.idempotencyKey());
        body.put("sourceEventId", event.sourceEventId()); body.put("source", event.source());
        body.put("eventType", event.eventType()); body.put("organizationId", event.organizationId());
        body.put("storeId", event.storeId()); body.put("taskId", event.taskId());
        body.put("recommenderAccountId", event.recommenderAccountId()); body.put("occurredAt", event.occurredAt());
        body.put("valueCents", event.valueCents());
        try { body.put("metadata", mapper.readTree(event.metadataJson())); }
        catch (Exception ignored) { body.put("metadata", Map.of()); }
        body.put("createdAt", event.createdAt());
        return body;
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value; }
    private static Map<String, Object> success(Object data) { return Map.of("success", true, "data", data); }
}
