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
import com.grassland.reporting.ReportFormat;
import com.grassland.reporting.ReportRenderer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
                .then(export(organizationId, blank(storeId), from, to, ReportFormat.CSV));
    }

    @GetMapping("/api/admin/analytics/business/export")
    public Mono<ResponseEntity<byte[]>> exportAdminReport(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "csv") String format, ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        ReportFormat reportFormat = ReportFormat.parse(format);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(export(organizationId, blank(storeId), from, to, reportFormat));
    }

    @GetMapping("/api/analytics/export.csv")
    public Mono<ResponseEntity<byte[]>> exportMerchant(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireScope(caller, organizationId, blank(storeId), "staff"))
                .flatMap(access -> export(access.organizationId(), access.storeId(), from, to, ReportFormat.CSV));
    }

    @GetMapping("/api/analytics/export")
    public Mono<ResponseEntity<byte[]>> exportMerchantReport(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "csv") String format, ServerHttpRequest request) {
        validateScope(organizationId, storeId, from, to);
        ReportFormat reportFormat = ReportFormat.parse(format);
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireScope(caller, organizationId, blank(storeId), "staff"))
                .flatMap(access -> export(access.organizationId(), access.storeId(), from, to, reportFormat));
    }

    private Mono<ResponseEntity<byte[]>> export(
            String organizationId, String storeId, Instant from, Instant to, ReportFormat format) {
        return analytics.report(organizationId, storeId, from, to).map(report -> {
            byte[] body = ReportRenderer.render(
                    MarketingAttributionCsv.report(report, AnalyticsAdvice.evaluate(report).advice(), from, to), format);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(format.mediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("business-analytics." + format.extension())
                                    .build().toString())
                    .body(body);
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

    /** 营销看板时间序列（商家视角，PRD §2.4 按日/周/月）。 */
    @GetMapping("/api/analytics/series")
    public Mono<ResponseEntity<Map<String, Object>>> seriesMerchant(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "day") String granularity, ServerHttpRequest request) {
        SeriesQuery query = SeriesQuery.parse(organizationId, blank(storeId), from, to, granularity);
        return callers.requireUser(request)
                .flatMap(caller -> authorization.requireScope(caller, organizationId, blank(storeId), "staff")
                        .then(seriesResponse(query)));
    }

    /** 营销看板时间序列（运营视角，FINANCE/RISK）。 */
    @GetMapping("/api/admin/analytics/series")
    public Mono<ResponseEntity<Map<String, Object>>> seriesAdmin(
            @RequestParam String organizationId, @RequestParam(required = false) String storeId,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "day") String granularity, ServerHttpRequest request) {
        SeriesQuery query = SeriesQuery.parse(organizationId, blank(storeId), from, to, granularity);
        return callers.requireRole(request, BackendRole.FINANCE, BackendRole.RISK)
                .then(seriesResponse(query));
    }

    private Mono<ResponseEntity<Map<String, Object>>> seriesResponse(SeriesQuery query) {
        return analytics.series(query.organizationId(), query.storeId(), query.from(), query.to(),
                        query.sqlField())
                .collectList()
                .map(found -> ResponseEntity.ok(success(seriesBody(query, found))));
    }

    private static Map<String, Object> seriesBody(SeriesQuery query, List<AnalyticsModels.SeriesBucket> found) {
        Map<String, AnalyticsModels.SeriesBucket> byBucket = new LinkedHashMap<>();
        found.forEach(bucket -> byBucket.put(bucket.bucket(), bucket));
        List<Map<String, Object>> buckets = new ArrayList<>();
        for (LocalDate cursor = query.firstBucket(); !cursor.isAfter(query.lastBucket());
                cursor = query.next(cursor)) {
            String label = cursor.toString();
            AnalyticsModels.SeriesBucket bucket = byBucket.get(label);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", label);
            row.put("orders", bucket == null ? 0 : bucket.orders());
            row.put("paidOrders", bucket == null ? 0 : bucket.paidOrders());
            row.put("redeemedOrders", bucket == null ? 0 : bucket.redeemedOrders());
            row.put("refundedOrders", bucket == null ? 0 : bucket.refundedOrders());
            row.put("grossGmvCents", bucket == null ? 0L : bucket.grossGmvCents());
            row.put("refundedGmvCents", bucket == null ? 0L : bucket.refundedGmvCents());
            row.put("netGmvCents", bucket == null ? 0L
                    : bucket.grossGmvCents() - bucket.refundedGmvCents());
            row.put("merchantRevenueCents", bucket == null ? 0L : bucket.merchantRevenueCents());
            row.put("recommenderRevenueCents", bucket == null ? 0L : bucket.recommenderRevenueCents());
            row.put("exposures", bucket == null ? 0 : bucket.exposures());
            row.put("interactions", bucket == null ? 0 : bucket.interactions());
            row.put("conversions", bucket == null ? 0 : bucket.conversions());
            buckets.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", query.organizationId());
        if (query.storeId() != null) body.put("storeId", query.storeId());
        body.put("granularity", query.granularity());
        body.put("from", query.from().toString());
        body.put("to", query.to().toString());
        body.put("buckets", buckets);
        return body;
    }

    /** 序列查询参数：粒度解析、窗口校验（from < to、桶数上限）与北京时间桶轴遍历。 */
    record SeriesQuery(String organizationId, String storeId, Instant from, Instant to,
                       String granularity, LocalDate firstBucket, LocalDate lastBucket) {
        private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
        private static final int MAX_BUCKETS = 400;

        static SeriesQuery parse(String organizationId, String storeId, Instant from, Instant to,
                String granularity) {
            requireUuid(organizationId, "organizationId");
            if (!java.util.Set.of("day", "week", "month").contains(granularity)) {
                throw new MarketplaceException(400, "granularity 仅支持 day/week/month");
            }
            if (from == null || to == null) {
                throw new MarketplaceException(400, "时间序列必须提供 from 与 to");
            }
            if (!from.isBefore(to)) {
                throw new MarketplaceException(400, "to 必须晚于 from");
            }
            LocalDate first = from.atZone(ZONE).toLocalDate();
            // [from, to) 左闭右开：最后一个可能含数据的桶是 to-1ns 所在日期（to 恰为当地零点时归属前一天）。
            LocalDate last = to.minusNanos(1).atZone(ZONE).toLocalDate();
            int count = bucketCount(first, last, granularity);
            if (count > MAX_BUCKETS) {
                throw new MarketplaceException(400, "时间跨度的桶数超过上限（" + MAX_BUCKETS + "）");
            }
            return new SeriesQuery(organizationId, storeId, from, to, granularity,
                    truncate(first, granularity), truncate(last, granularity));
        }

        String sqlField() { return granularity; }

        LocalDate next(LocalDate cursor) {
            return switch (granularity) {
                case "month" -> cursor.plusMonths(1);
                case "week" -> cursor.plusWeeks(1);
                default -> cursor.plusDays(1);
            };
        }

        /** 对齐到粒度桶起点（周对齐周一，与 Postgres date_trunc('week') 一致）。 */
        private static LocalDate truncate(LocalDate date, String granularity) {
            return switch (granularity) {
                case "month" -> date.withDayOfMonth(1);
                case "week" -> date.with(java.time.DayOfWeek.MONDAY);
                default -> date;
            };
        }

        private static int bucketCount(LocalDate first, LocalDate last, String granularity) {
            long count = switch (granularity) {
                case "month" -> java.time.temporal.ChronoUnit.MONTHS.between(truncate(first, granularity),
                        truncate(last, granularity)) + 1;
                case "week" -> java.time.temporal.ChronoUnit.WEEKS.between(truncate(first, granularity),
                        truncate(last, granularity)) + 1;
                default -> java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
            };
            return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        }
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
