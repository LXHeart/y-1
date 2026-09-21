package com.grassland.marketplace.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.analytics.AnalyticsModels.AttributionSummary;
import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.AnalyticsModels.Event;
import com.grassland.marketplace.analytics.AnalyticsModels.EventRegistration;
import com.grassland.marketplace.analytics.AnalyticsModels.RecordEventRequest;
import com.grassland.marketplace.analytics.AnalyticsModels.RecommenderReport;
import com.grassland.marketplace.security.MarketplaceException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class AnalyticsRepository {
	private static final String EVENT_COLS = "id::text, idempotency_key, source_event_id, source, event_type, "
			+ "organization_id::text, store_id::text, task_id::text, recommender_account_id::text, occurred_at, "
			+ "value_cents, metadata::text, recorded_by::text, created_at";
	private final DatabaseClient db;
	private final ObjectMapper mapper;
	private final CommerceFactsRepository facts;

	public AnalyticsRepository(DatabaseClient db, ObjectMapper mapper, CommerceFactsRepository facts) {
		this.db = db;
		this.mapper = mapper;
		this.facts = facts;
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
				""".formatted(EVENT_COLS)).bind("id", id).bind("key", request.idempotencyKey()).bind("source", source)
				.bind("type", request.eventType()).bind("occurred", occurred.atOffset(ZoneOffset.UTC))
				.bind("value", value(request.valueCents())).bind("metadata", metadata);
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
		return attribution(organizationId, storeId, from, to, Instant.now());
	}

	private Mono<AttributionSummary> attribution(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
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
				  AND occurred_at < :asOf
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
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
			String status = conversions == 0
					? (exposures + interactions == 0 ? "not_collected" : "conversion_not_collected")
					: "collected";
			String dataQuality = total == 0
					? "none"
					: verified == 0 ? "sandbox" : verified == total ? "verified" : "mixed";
			return new AttributionSummary(exposures, interactions, conversions, revenue, refunds, dataQuality, status,
					null);
		}).one().defaultIfEmpty(new AttributionSummary(0, 0, 0, 0, 0, "none", "not_collected", null));
	}

	/**
	 * 任务书 #103 C103-15：经营报表改走统一事实（§6.6 commerce-facts-v2）—— 订单 cohort 按
	 * created_at，支付/退款/核销/已结收入全部取权威事实，弃用状态白名单； 待结按 NetSplitAllocation 逐单预估；分账缺投影 →
	 * dataCompleteness=partial（C16 据此 503）。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Mono<BusinessReport> report(String organizationId, String storeId, Instant from, Instant to) {
		return facts.query(organizationId, storeId, from, to, null)
				.flatMap(result -> attribution(organizationId, storeId, from, to, result.asOf()).map(attribution -> {
					long cost = result.settledBountyCents();
					long returns = attribution.attributedRevenueCents() - attribution.attributedRefundCents();
					Double roi = cost > 0 && attribution.conversions() > 0 ? ((double) returns - cost) / cost : null;
					String status = attribution.conversions() > 0 && cost > 0
							? "estimated_" + attribution.dataQuality()
							: attribution.status();
					AttributionSummary summary = new AttributionSummary(attribution.exposures(),
							attribution.interactions(), attribution.conversions(), attribution.attributedRevenueCents(),
							attribution.attributedRefundCents(), attribution.dataQuality(), status, roi);
					return new BusinessReport(organizationId, storeId, result.orders(), result.paidOrders(),
							result.redeemedOrders(), result.refundedOrders(), result.grossGmvCents(),
							result.refundedGmvCents(), result.netGmvCents(), result.merchantRevenueCents(),
							result.platformFeeCents(), result.recommenderRevenueCents(), cost, summary,
							result.netRedeemedCents(), result.pendingMerchantCents(), result.pendingPlatformCents(),
							result.pendingRecommenderCents(), result.pendingOrders(), result.settledOrders(),
							result.missingSettlementFactCount(), CommerceFactsRepository.METRIC_VERSION,
							CommerceFactsRepository.WINDOW_BASIS, result.asOf(), result.dataCompleteness());
				}));
	}

	/**
	 * 推荐官排行（§6.6）：attributed 继续按归因事件；收入 = 每人 settlement allocation 聚合
	 * （事实表逐单快照，多推荐官不挂主推荐官；无已结事实的收入为 0，不用冻结预估冒充）。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Flux<RecommenderReport> recommenderReport(String organizationId, String storeId, Instant from, Instant to) {
		return recommenderReport(organizationId, storeId, from, to, Instant.now());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Flux<RecommenderReport> recommenderReport(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
		var spec = db.sql("""
				SELECT recommender_account_id::text id,
				       COUNT(*) FILTER (WHERE event_type='conversion')::int conversions,
				       COALESCE(SUM(value_cents) FILTER (WHERE event_type='conversion'),0)::bigint attributed
				FROM marketing_attribution_event
				WHERE organization_id=CAST(:org AS uuid) AND recommender_account_id IS NOT NULL
				  AND (:store IS NULL OR store_id=CAST(:store AS uuid))
				  AND (:fromAt IS NULL OR occurred_at >= :fromAt) AND (:toAt IS NULL OR occurred_at < :toAt)
				  AND occurred_at < :asOf
				GROUP BY recommender_account_id ORDER BY attributed DESC
				""").bind("org", organizationId).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		spec = bindNullable(spec, "store", storeId);
		spec = bindNullableInstant(spec, "fromAt", from);
		spec = bindNullableInstant(spec, "toAt", to);
		// conversions/attributed 仍来自事件表——与事实收入合并：先取事件行，再补齐只有事实的推荐官。
		Mono<Map<String, Long>> settledMap = facts.recommenderSettled(organizationId, storeId, from, to, asOf)
				.collectMap(CommerceFactsRepository.RecommenderAllocation::recommenderAccountId,
						CommerceFactsRepository.RecommenderAllocation::settledCents);
		Flux<RecommenderReport> eventRows = spec.map(row -> {
			String id = row.get("id", String.class);
			return new RecommenderReport(id, integer(row.get("conversions", Integer.class)),
					value(row.get("attributed", Long.class)), 0L);
		}).all();
		return requireCompleteFacts(organizationId, storeId, from, to, asOf).then(settledMap).flatMapMany(settled -> {
			Flux<RecommenderReport> factOnly = Flux.fromIterable(settled.entrySet())
					.map(entry -> new RecommenderReport(entry.getKey(), 0, 0, entry.getValue()));
			return eventRows
					.map(row -> new RecommenderReport(row.recommenderAccountId(), row.conversions(),
							row.attributedRevenueCents(), settled.getOrDefault(row.recommenderAccountId(), 0L)))
					.concatWith(factOnly).distinct(RecommenderReport::recommenderAccountId);
		});
	}

	/**
	 * 营销看板时间序列（PRD §2.4 按日/周/月）：consumer_order（按 created_at）与归因事件 （按 occurred_at）在
	 * {@code field}（day/week/month）粒度上按北京时间切桶对齐； 只返回有数据的桶，空桶补零由 controller
	 * 层完成。settled bounty 不入序列（结算时间与 经营时间轴错位，仍只在总量报表体现）。
	 */
	/**
	 * 营销看板时间序列（PRD §2.4 按日/周/月；C103-15 事实口径）：订单桶来自统一事实 （支付=paid_at、退款=refund
	 * 事实按订单创建桶重述、已结收入=settlement fact）； 归因事件（按 occurred_at）在 field
	 * 粒度上按北京时间切桶对齐；只返回有数据的桶， 空桶补零由 controller 层完成。settled bounty
	 * 不入序列（结算时间与经营时间轴错位）。
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Flux<AnalyticsModels.SeriesBucket> series(String organizationId, String storeId, Instant from, Instant to,
			String field) {
		return series(organizationId, storeId, from, to, field, Instant.now());
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Flux<AnalyticsModels.SeriesBucket> series(String organizationId, String storeId, Instant from, Instant to,
			String field, Instant asOf) {
		var eventSpec = db
				.sql("""
						SELECT to_char(date_trunc(CAST(:field AS text), occurred_at AT TIME ZONE 'Asia/Shanghai'), 'YYYY-MM-DD') bucket,
						       COUNT(*) FILTER (WHERE event_type='exposure')::int exposures,
						       COUNT(*) FILTER (WHERE event_type='interaction')::int interactions,
						       COUNT(*) FILTER (WHERE event_type='conversion')::int conversions
						FROM marketing_attribution_event
						WHERE organization_id=CAST(:org AS uuid)
						  AND (:store IS NULL OR store_id=CAST(:store AS uuid))
						  AND occurred_at >= :fromAt AND occurred_at < :toAt AND occurred_at < :asOf
						GROUP BY 1
						""")
				.bind("org", organizationId).bind("field", field).bind("asOf", asOf.atOffset(ZoneOffset.UTC));
		eventSpec = bindNullable(eventSpec, "store", storeId);
		eventSpec = eventSpec.bind("fromAt", from.atOffset(ZoneOffset.UTC)).bind("toAt", to.atOffset(ZoneOffset.UTC));
		record BucketCounters(String bucket, int[] counters) {
		}
		Mono<Map<String, int[]>> events = eventSpec
				.map(row -> new BucketCounters(row.get("bucket", String.class),
						new int[]{integer(row.get("exposures", Integer.class)),
								integer(row.get("interactions", Integer.class)),
								integer(row.get("conversions", Integer.class))}))
				.all().collectMap(BucketCounters::bucket, BucketCounters::counters);
		return requireCompleteFacts(organizationId, storeId, from, to, asOf).then(events).flatMapMany(
				eventMap -> facts.seriesBuckets(organizationId, storeId, from, to, field, asOf).map(bucket -> {
					int[] counters = eventMap.getOrDefault(bucket.bucket(), new int[3]);
					return new AnalyticsModels.SeriesBucket(bucket.bucket(), bucket.orders(), bucket.paid(),
							bucket.redeemed(), bucket.refunded(), bucket.grossGmvCents(), bucket.refundedGmvCents(),
							bucket.merchantRevenueCents(), bucket.recommenderRevenueCents(), counters[0], counters[1],
							counters[2]);
				}).concatWith(Flux.fromIterable(eventMap.entrySet()).filter(entry -> true)
						.map(entry -> new AnalyticsModels.SeriesBucket(entry.getKey(), 0, 0, 0, 0, 0L, 0L, 0L, 0L,
								entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))))
				.distinct(AnalyticsModels.SeriesBucket::bucket).sort((a, b) -> a.bucket().compareTo(b.bucket()));
	}

	private Mono<Void> requireCompleteFacts(String organizationId, String storeId, Instant from, Instant to,
			Instant asOf) {
		return facts.query(organizationId, storeId, from, to, asOf).flatMap(result -> {
			if ("complete".equals(result.dataCompleteness()))
				return Mono.empty();
			return Mono
					.error(new MarketplaceException(503,
							"结算数据待核对（缺失分账事实 " + result.missingSettlementFactCount()
									+ " 条，dataCompleteness=partial，asOf=" + result.asOf() + "），请稍后重试",
							"analytics_facts_incomplete"));
		});
	}

	static void validate(RecordEventRequest request) {
		if (request == null || blank(request.idempotencyKey()) || blank(request.eventType())
				|| blank(request.organizationId())) {
			throw new IllegalArgumentException("idempotencyKey、eventType、organizationId 不能为空");
		}
		if (request.idempotencyKey().length() > 160)
			throw new IllegalArgumentException("idempotencyKey 最长 160 字符");
		if (request.sourceEventId() != null && request.sourceEventId().length() > 160) {
			throw new IllegalArgumentException("sourceEventId 最长 160 字符");
		}
		if (!java.util.Set.of("exposure", "interaction", "conversion", "conversion_refund")
				.contains(request.eventType())) {
			throw new IllegalArgumentException("eventType 不受支持");
		}
		requireUuid(request.organizationId(), "organizationId");
		requireOptionalUuid(request.storeId(), "storeId");
		requireOptionalUuid(request.taskId(), "taskId");
		requireOptionalUuid(request.recommenderAccountId(), "recommenderAccountId");
		long value = value(request.valueCents());
		if (value < 0)
			throw new IllegalArgumentException("valueCents 不能为负数");
		if (!"conversion".equals(request.eventType()) && !"conversion_refund".equals(request.eventType())
				&& value != 0) {
			throw new IllegalArgumentException("曝光/互动事件 valueCents 必须为 0");
		}
	}
	private static void requireOptionalUuid(String value, String name) {
		if (!blank(value))
			requireUuid(value, name);
	}
	private static void requireUuid(String value, String name) {
		try {
			UUID.fromString(value);
		} catch (RuntimeException error) {
			throw new IllegalArgumentException(name + " 格式错误");
		}
	}
	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
	private String json(Map<String, Object> value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("metadata 不是合法 JSON");
		}
	}
	private static Event mapEvent(Readable row) {
		return new Event(row.get("id", String.class), row.get("idempotency_key", String.class),
				row.get("source_event_id", String.class), row.get("source", String.class),
				row.get("event_type", String.class), row.get("organization_id", String.class),
				row.get("store_id", String.class), row.get("task_id", String.class),
				row.get("recommender_account_id", String.class), instant(row.get("occurred_at", OffsetDateTime.class)),
				value(row.get("value_cents", Long.class)), row.get("metadata", String.class),
				row.get("recorded_by", String.class), instant(row.get("created_at", OffsetDateTime.class)));
	}
	private static long value(Long value) {
		return value == null ? 0L : value;
	}
	private static int integer(Integer value) {
		return value == null ? 0 : value;
	}
	private static Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
	private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	private static GenericExecuteSpec bindNullableText(GenericExecuteSpec spec, String name, String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
	private static GenericExecuteSpec bindNullableInstant(GenericExecuteSpec spec, String name, Instant value) {
		return value == null
				? spec.bindNull(name, OffsetDateTime.class)
				: spec.bind(name, value.atOffset(ZoneOffset.UTC));
	}
}
