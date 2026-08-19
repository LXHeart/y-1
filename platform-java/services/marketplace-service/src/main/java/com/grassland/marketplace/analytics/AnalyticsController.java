package com.grassland.marketplace.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.analytics.AnalyticsModels.Event;
import com.grassland.marketplace.analytics.AnalyticsModels.EventRegistration;
import com.grassland.marketplace.analytics.AnalyticsModels.RecordEventRequest;
import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.CampaignRequest;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
    private final MarketingAttributionRepository marketing;
    private final ObjectMapper mapper;

    public AnalyticsController(MarketplaceCallerResolver callers, TaskResourceAuthorization authorization,
                               TaskRepository tasks, AnalyticsRepository analytics,
                               MarketingAttributionRepository marketing, ObjectMapper mapper) {
        this.callers = callers;
        this.authorization = authorization;
        this.tasks = tasks;
        this.analytics = analytics;
        this.marketing = marketing;
        this.mapper = mapper;
    }

    @PostMapping("/api/analytics/campaigns")
    public Mono<ResponseEntity<Map<String, Object>>> createCampaign(
            @RequestBody CampaignRequest body, ServerHttpRequest request) {
        return Mono.defer(() -> {
            CampaignRequest normalized = validateCampaign(body);
            return callers.requireUser(request)
                    .flatMap(caller -> authorization.requireScope(caller, normalized.organizationId(),
                                    normalized.storeId(), "manager")
                            .then(validateTaskScope(normalized))
                            .then(marketing.create(normalized, caller.accountId())))
                    .map(campaign -> ResponseEntity.status(201).body(success(campaign)))
                    .onErrorMap(DataIntegrityViolationException.class,
                            error -> new MarketplaceException(409, "Provider Campaign 已绑定"));
        });
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
        validateScope(organizationId, storeId, from, to);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(analytics.report(organizationId, blank(storeId), from, to))
                .flatMap(this::enrich)
                .map(report -> ResponseEntity.ok(success(report)));
    }

    @GetMapping("/api/admin/analytics/recommenders")
    public Mono<ResponseEntity<Map<String, Object>>> recommenders(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .thenMany(analytics.recommenderReport(organizationId, blank(storeId), from, to))
                .collectList().map(items -> ResponseEntity.ok(success(items)));
    }

    @GetMapping("/api/admin/analytics/alerts")
    public Mono<ResponseEntity<Map<String, Object>>> alerts(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "false") boolean includeResolved, ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        String effectiveStore = blank(storeId);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(analytics.report(organizationId, effectiveStore, from, to))
                .flatMap(report -> {
                    var evaluated = AnalyticsAdvice.evaluate(report);
                    return marketing.syncAlerts(organizationId, effectiveStore, evaluated.alerts())
                            .thenMany(marketing.listAlerts(organizationId, effectiveStore, includeResolved))
                            .collectList();
                }).map(items -> ResponseEntity.ok(success(items)));
    }

    @PostMapping("/api/admin/analytics/alerts/{id}/acknowledge")
    public Mono<ResponseEntity<Map<String, Object>>> acknowledge(
            @org.springframework.web.bind.annotation.PathVariable String id, ServerHttpRequest request) {
        requireUuid(id, "id");
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .flatMap(caller -> marketing.acknowledge(id, caller.accountId()))
                .flatMap(updated -> updated
                        ? Mono.just(ResponseEntity.ok(success(Map.of("acknowledged", true, "id", id))))
                        : Mono.error(new MarketplaceException(409, "告警不存在或状态不可确认")));
    }

    @GetMapping("/api/admin/analytics/business/export.csv")
    public Mono<ResponseEntity<byte[]>> exportAdmin(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(export(organizationId, blank(storeId), from, to));
    }

    @GetMapping("/api/analytics/export.csv")
    public Mono<ResponseEntity<byte[]>> exportMerchant(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireScope(caller, organizationId, blank(storeId), "staff"))
                .flatMap(access -> export(access.organizationId(), access.storeId(), from, to));
    }

    private Mono<ResponseEntity<byte[]>> export(String organizationId, String storeId, Instant from, Instant to) {
        return analytics.report(organizationId, storeId, from, to).map(report -> {
            byte[] csv = MarketingAttributionCsv.render(report, AnalyticsAdvice.evaluate(report).advice(), from, to);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("marketing-attribution.csv").build().toString())
                    .body(csv);
        });
    }

    private Mono<Map<String, Object>> enrich(BusinessReport report) {
        var evaluated = AnalyticsAdvice.evaluate(report);
        return marketing.syncAlerts(report.organizationId(), report.storeId(), evaluated.alerts())
                .thenMany(marketing.listAlerts(report.organizationId(), report.storeId(), false))
                .collectList()
                .map(items -> reportBody(report, evaluated.advice(), items));
    }

    private static Map<String, Object> reportBody(BusinessReport report, List<?> advice, List<?> alerts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", report.organizationId()); body.put("storeId", report.storeId());
        body.put("orders", report.orders()); body.put("paidOrders", report.paidOrders());
        body.put("redeemedOrders", report.redeemedOrders()); body.put("refundedOrders", report.refundedOrders());
        body.put("grossGmvCents", report.grossGmvCents()); body.put("refundedGmvCents", report.refundedGmvCents());
        body.put("netGmvCents", report.netGmvCents()); body.put("merchantRevenueCents", report.merchantRevenueCents());
        body.put("platformFeeCents", report.platformFeeCents());
        body.put("recommenderRevenueCents", report.recommenderRevenueCents());
        body.put("settledBountyCents", report.settledBountyCents()); body.put("attribution", report.attribution());
        body.put("advice", advice); body.put("alerts", alerts);
        return body;
    }

    private Mono<Void> validateTaskScope(RecordEventRequest body) {
        if (body.taskId() == null || body.taskId().isBlank()) return Mono.empty();
        return tasks.findById(body.taskId())
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                .flatMap(task -> Objects.equals(task.organizationId(), body.organizationId())
                                && Objects.equals(task.storeId(), blank(body.storeId()))
                        ? Mono.empty() : Mono.error(new MarketplaceException(409, "任务与分析范围不一致")));
    }

    private Mono<Void> validateTaskScope(CampaignRequest body) {
        if (body.taskId() == null) return Mono.empty();
        return tasks.findById(body.taskId())
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "任务不存在")))
                .flatMap(task -> Objects.equals(task.organizationId(), body.organizationId())
                                && Objects.equals(task.storeId(), body.storeId())
                        ? Mono.empty() : Mono.error(new MarketplaceException(409, "任务与 Campaign 范围不一致")));
    }

    private static CampaignRequest validateCampaign(CampaignRequest body) {
        if (body == null) throw new IllegalArgumentException("Campaign 请求体不能为空");
        String provider = body.provider() == null ? "" : body.provider().trim().toLowerCase(Locale.ROOT);
        String externalId = body.externalCampaignId() == null ? "" : body.externalCampaignId().trim();
        if (provider.length() > 48 || !provider.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("provider 格式错误");
        }
        if (externalId.isEmpty() || externalId.length() > 160) {
            throw new IllegalArgumentException("externalCampaignId 长度必须为 1-160 字符");
        }
        requireUuid(body.organizationId(), "organizationId");
        requireOptionalUuid(body.storeId(), "storeId");
        requireOptionalUuid(body.taskId(), "taskId");
        requireOptionalUuid(body.recommenderAccountId(), "recommenderAccountId");
        return new CampaignRequest(provider, externalId, body.organizationId(), blank(body.storeId()),
                blank(body.taskId()), blank(body.recommenderAccountId()));
    }

    private static void validateScope(String organizationId, String storeId, Instant from, Instant to) {
        requireUuid(organizationId, "organizationId");
        requireOptionalUuid(storeId, "storeId");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("to 必须晚于 from");
        }
    }

    private static void requireOptionalUuid(String value, String name) {
        if (value != null && !value.isBlank()) requireUuid(value, name);
    }

    private static void requireUuid(String value, String name) {
        try { UUID.fromString(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException(name + " 格式错误"); }
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
