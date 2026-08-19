package com.grassland.marketplace.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.analytics.AnalyticsModels.AttributionSummary;
import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.AnalyticsModels.Event;
import com.grassland.marketplace.analytics.AnalyticsModels.EventRegistration;
import com.grassland.marketplace.analytics.AnalyticsModels.RecordEventRequest;
import com.grassland.marketplace.analytics.AnalyticsModels.RecommenderReport;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AnalyticsRepository {
    private static final String EVENT_COLS = "id::text, idempotency_key, source_event_id, source, event_type, "
            + "organization_id::text, store_id::text, task_id::text, recommender_account_id::text, occurred_at, "
            + "value_cents, metadata::text, recorded_by::text, created_at";
    private final DatabaseClient db;
    private final ObjectMapper mapper;

    public AnalyticsRepository(DatabaseClient db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    public Mono<EventRegistration> record(RecordEventRequest request, String accountId) {
        return record(request, accountId, "sandbox_manual");
    }

    public Mono<EventRegistration> record(RecordEventRequest request, String accountId, String source) {
        validate(request);
        if (blank(source) || source.length() > 48 || !source.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("source 格式错误");
        }
        String id = UUID.randomUUID().toString();
        String metadata = json(request.metadata() == null ? Map.of() : request.metadata());
        Instant occurred = request.occurredAt() == null ? Instant.now() : request.occurredAt();
        var spec = db.sql("""
                INSERT INTO marketing_attribution_event(
                    id, idempotency_key, source_event_id, source, event_type, organization_id, store_id, task_id,
                    recommender_account_id, occurred_at, value_cents, metadata, recorded_by)
                VALUES(CAST(:id AS uuid), :key, :sourceEventId, :source, :type, CAST(:org AS uuid),
                       CAST(:store AS uuid), CAST(:task AS uuid), CAST(:recommender AS uuid), :occurred,
                       :value, CAST(:metadata AS jsonb), CAST(:recordedBy AS uuid))
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING %s
                """.formatted(EVENT_COLS))
                .bind("id", id).bind("key", request.idempotencyKey()).bind("source", source)
                .bind("type", request.eventType())
                .bind("occurred", occurred.atOffset(ZoneOffset.UTC)).bind("value", value(request.valueCents()))
                .bind("metadata", metadata);
        spec = bindNullableText(spec, "sourceEventId", request.sourceEventId());
        spec = bindNullable(spec, "org", request.organizationId());
        spec = bindNullable(spec, "store", request.storeId());
        spec = bindNullable(spec, "task", request.taskId());
        spec = bindNullable(spec, "recommender", request.recommenderAccountId());
        spec = bindNullable(spec, "recordedBy", accountId);
        return spec.map(AnalyticsRepository::mapEvent).one().map(event -> new EventRegistration(event, true))
                .switchIfEmpty(findByIdempotencyKey(request.idempotencyKey())
                        .map(event -> new EventRegistration(event, false)));
    }

    public Mono<Event> findByIdempotencyKey(String key) {
        return db.sql("SELECT " + EVENT_COLS + " FROM marketing_attribution_event WHERE idempotency_key=:key")
                .bind("key", key).map(AnalyticsRepository::mapEvent).one();
    }

    public Mono<AttributionSummary> attribution(String organizationId, String storeId, Instant from, Instant to) {
        var spec = db.sql("""
                SELECT COUNT(*) FILTER (WHERE event_type='exposure')::int exposures,
                       COUNT(*) FILTER (WHERE event_type='interaction')::int interactions,
                       COUNT(*) FILTER (WHERE event_type='conversion')::int conversions,
                       COALESCE(SUM(value_cents) FILTER (WHERE event_type='conversion'),0)::bigint revenue,
                       COALESCE(SUM(value_cents) FILTER (WHERE event_type='conversion_refund'),0)::bigint refunds,
                       COUNT(*)::int total,
                       COUNT(*) FILTER (WHERE source <> 'sandbox_manual')::int verified
                FROM marketing_attribution_event
                WHERE organization_id=CAST(:org AS uuid)
                  AND (:store IS NULL OR store_id=CAST(:store AS uuid))
                  AND (:fromAt IS NULL OR occurred_at >= :fromAt)
                  AND (:toAt IS NULL OR occurred_at < :toAt)
                """).bind("org", organizationId);
        spec = bindNullable(spec, "store", storeId);
        spec = bindNullableInstant(spec, "fromAt", from);
        spec = bindNullableInstant(spec, "toAt", to);
        return spec.map(row -> {
            int exposures = integer(row.get("exposures", Integer.class));
            int interactions = integer(row.get("interactions", Integer.class));
            int conversions = integer(row.get("conversions", Integer.class));
            long revenue = value(row.get("revenue", Long.class));
            long refunds = value(row.get("refunds", Long.class));
            int verified = integer(row.get("verified", Integer.class));
            int total = integer(row.get("total", Integer.class));
            String status = conversions == 0 ? (exposures + interactions == 0 ? "not_collected" : "conversion_not_collected") : "collected";
            String dataQuality = total == 0 ? "none"
                    : verified == 0 ? "sandbox" : verified == total ? "verified" : "mixed";
            return new AttributionSummary(exposures, interactions, conversions, revenue, refunds,
                    dataQuality, status, null);
        }).one().defaultIfEmpty(new AttributionSummary(0, 0, 0, 0, 0, "none", "not_collected", null));
    }

    public Mono<BusinessReport> report(String organizationId, String storeId, Instant from, Instant to) {
        var spec = db.sql("""
                WITH orders AS (
                    SELECT status, price_cents, merchant_amount_cents, platform_fee_cents, recommender_amount_cents
                    FROM consumer_order
                    WHERE organization_id=CAST(:org AS uuid)
                      AND (:store IS NULL OR store_id=CAST(:store AS uuid))
                      AND (:fromAt IS NULL OR created_at >= :fromAt)
                      AND (:toAt IS NULL OR created_at < :toAt)
                ), settled AS (
                    SELECT COALESCE(SUM(f.bounty_cents),0)::bigint value FROM (
                    SELECT DISTINCT a.id, a.bounty_cents
                    FROM task_application a JOIN task t ON t.id=a.task_id
                    JOIN marketplace_outbox o ON o.aggregate_id=a.id::text AND o.event_type='EngagementSettled'
                    WHERE t.organization_id=CAST(:org AS uuid)
                      AND (:store IS NULL OR t.store_id=CAST(:store AS uuid))
                      AND (:fromAt IS NULL OR o.created_at >= :fromAt)
                      AND (:toAt IS NULL OR o.created_at < :toAt)
                    ) f
                )
                SELECT COUNT(*)::int orders,
                       COUNT(*) FILTER (WHERE status IN ('paid','redeeming','redeemed','refunded'))::int paid,
                       COUNT(*) FILTER (WHERE status='redeemed')::int redeemed,
                       COUNT(*) FILTER (WHERE status='refunded')::int refunded,
                       COALESCE(SUM(price_cents) FILTER (WHERE status IN ('paid','redeeming','redeemed','refunded')),0)::bigint gross,
                       COALESCE(SUM(price_cents) FILTER (WHERE status='refunded'),0)::bigint refund_gmv,
                       COALESCE(SUM(merchant_amount_cents) FILTER (WHERE status <> 'refunded'),0)::bigint merchant_revenue,
                       COALESCE(SUM(platform_fee_cents) FILTER (WHERE status <> 'refunded'),0)::bigint platform_fee,
                       COALESCE(SUM(recommender_amount_cents) FILTER (WHERE status <> 'refunded'),0)::bigint recommender_revenue,
                       (SELECT value FROM settled) settled_bounty
                FROM orders
                """).bind("org", organizationId);
        spec = bindNullable(spec, "store", storeId);
        spec = bindNullableInstant(spec, "fromAt", from);
        spec = bindNullableInstant(spec, "toAt", to);
        return spec.map(row -> {
            int paid = integer(row.get("paid", Integer.class));
            int refunded = integer(row.get("refunded", Integer.class));
            long gross = value(row.get("gross", Long.class));
            long refunds = value(row.get("refund_gmv", Long.class));
            return new BusinessReport(organizationId, storeId, integer(row.get("orders", Integer.class)), paid,
                    integer(row.get("redeemed", Integer.class)), refunded, gross, refunds, gross - refunds,
                    value(row.get("merchant_revenue", Long.class)), value(row.get("platform_fee", Long.class)),
                    value(row.get("recommender_revenue", Long.class)), value(row.get("settled_bounty", Long.class)), null);
        }).one().flatMap(report -> attribution(organizationId, storeId, from, to).map(attribution -> {
            long cost = report.settledBountyCents();
            long returns = attribution.attributedRevenueCents() - attribution.attributedRefundCents();
            Double roi = cost > 0 && attribution.conversions() > 0 ? ((double) returns - cost) / cost : null;
            String status = attribution.conversions() > 0 && cost > 0
                    ? "estimated_" + attribution.dataQuality() : attribution.status();
            return new BusinessReport(report.organizationId(), report.storeId(), report.orders(), report.paidOrders(),
                    report.redeemedOrders(), report.refundedOrders(), report.grossGmvCents(), report.refundedGmvCents(),
                    report.netGmvCents(), report.merchantRevenueCents(), report.platformFeeCents(),
                    report.recommenderRevenueCents(), cost, new AttributionSummary(attribution.exposures(),
                    attribution.interactions(), attribution.conversions(), attribution.attributedRevenueCents(),
                    attribution.attributedRefundCents(), attribution.dataQuality(), status, roi));
        }));
    }

    public Flux<RecommenderReport> recommenderReport(String organizationId, String storeId, Instant from, Instant to) {
        var spec = db.sql("""
                SELECT recommender_account_id::text id,
                       COUNT(*) FILTER (WHERE event_type='conversion')::int conversions,
                       COALESCE(SUM(value_cents) FILTER (WHERE event_type='conversion'),0)::bigint attributed,
                       COALESCE((SELECT SUM(o.recommender_amount_cents) FROM consumer_order o
                                 WHERE o.organization_id=CAST(:org AS uuid)
                                   AND o.recommender_account_id=e.recommender_account_id
                                   AND o.status <> 'refunded'
                                   AND (:store IS NULL OR o.store_id=CAST(:store AS uuid))
                                   AND (:fromAt IS NULL OR o.created_at >= :fromAt)
                                   AND (:toAt IS NULL OR o.created_at < :toAt)),0)::bigint revenue
                FROM marketing_attribution_event e
                WHERE organization_id=CAST(:org AS uuid) AND recommender_account_id IS NOT NULL
                  AND (:store IS NULL OR store_id=CAST(:store AS uuid))
                  AND (:fromAt IS NULL OR occurred_at >= :fromAt) AND (:toAt IS NULL OR occurred_at < :toAt)
                GROUP BY recommender_account_id ORDER BY attributed DESC
                """).bind("org", organizationId);
        spec = bindNullable(spec, "store", storeId);
        spec = bindNullableInstant(spec, "fromAt", from);
        spec = bindNullableInstant(spec, "toAt", to);
        return spec.map(row -> new RecommenderReport(row.get("id", String.class), integer(row.get("conversions", Integer.class)),
                value(row.get("attributed", Long.class)), value(row.get("revenue", Long.class)))).all();
    }

    static void validate(RecordEventRequest request) {
        if (request == null || blank(request.idempotencyKey()) || blank(request.eventType()) || blank(request.organizationId())) {
            throw new IllegalArgumentException("idempotencyKey、eventType、organizationId 不能为空");
        }
        if (request.idempotencyKey().length() > 160) throw new IllegalArgumentException("idempotencyKey 最长 160 字符");
        if (request.sourceEventId() != null && request.sourceEventId().length() > 160) {
            throw new IllegalArgumentException("sourceEventId 最长 160 字符");
        }
        if (!java.util.Set.of("exposure", "interaction", "conversion", "conversion_refund").contains(request.eventType())) {
            throw new IllegalArgumentException("eventType 不受支持");
        }
        requireUuid(request.organizationId(), "organizationId");
        requireOptionalUuid(request.storeId(), "storeId");
        requireOptionalUuid(request.taskId(), "taskId");
        requireOptionalUuid(request.recommenderAccountId(), "recommenderAccountId");
        long value = value(request.valueCents());
        if (value < 0) throw new IllegalArgumentException("valueCents 不能为负数");
        if (!"conversion".equals(request.eventType()) && !"conversion_refund".equals(request.eventType()) && value != 0) {
            throw new IllegalArgumentException("曝光/互动事件 valueCents 必须为 0");
        }
    }
    private static void requireOptionalUuid(String value, String name) { if (!blank(value)) requireUuid(value, name); }
    private static void requireUuid(String value, String name) { try { UUID.fromString(value); } catch (RuntimeException error) { throw new IllegalArgumentException(name + " 格式错误"); } }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private String json(Map<String, Object> value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("metadata 不是合法 JSON"); } }
    private static Event mapEvent(Readable row) { return new Event(row.get("id", String.class), row.get("idempotency_key", String.class),
            row.get("source_event_id", String.class), row.get("source", String.class), row.get("event_type", String.class),
            row.get("organization_id", String.class), row.get("store_id", String.class), row.get("task_id", String.class),
            row.get("recommender_account_id", String.class), instant(row.get("occurred_at", OffsetDateTime.class)),
            value(row.get("value_cents", Long.class)), row.get("metadata", String.class), row.get("recorded_by", String.class),
            instant(row.get("created_at", OffsetDateTime.class))); }
    private static long value(Long value) { return value == null ? 0L : value; }
    private static int integer(Integer value) { return value == null ? 0 : value; }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) { return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value); }
    private static GenericExecuteSpec bindNullableText(GenericExecuteSpec spec, String name, String value) { return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value); }
    private static GenericExecuteSpec bindNullableInstant(GenericExecuteSpec spec, String name, Instant value) { return value == null ? spec.bindNull(name, OffsetDateTime.class) : spec.bind(name, value.atOffset(ZoneOffset.UTC)); }
}
