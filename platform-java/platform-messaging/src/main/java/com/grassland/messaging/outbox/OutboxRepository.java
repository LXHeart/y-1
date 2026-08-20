package com.grassland.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.messaging.EventEnvelope;
import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 事务 outbox 表仓储：append 幂等（event_id 唯一）、SKIP LOCKED + per-aggregate 顺序 claim、
 * 租约（claimed_until/claim_token）、退避（attempt_count/next_attempt_at）。
 *
 * <p>
 * 原 identity/finance/trust/intelligence 四份逐字相同（仅表名与 trust 的 id/payload 方言不同），
 * 2026-08-20 下沉；marketplace 链为真分叉（String claimToken、fail-fast 校验、业务查询方法），暂不并入。
 */
public class OutboxRepository {

	private static final int MAX_ERROR_CODE_LENGTH = 64;

	private final DatabaseClient db;
	private final OutboxSchema schema;
	// 服务本地实例：刻意不注入 ObjectMapper bean（intelligence 无该 bean，注入会破坏整个上下文）。
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	public OutboxRepository(DatabaseClient db, OutboxSchema schema) {
		this.db = db;
		this.schema = schema;
	}

	public Mono<Void> append(EventEnvelope event) {
		try {
			String payload = mapper.writeValueAsString(event.payload());
			return db.sql("""
					INSERT INTO %s (event_id, event_type, aggregate_type, aggregate_id, payload)
					VALUES (:eventId, :eventType, :aggregateType, :aggregateId, CAST(:payload AS %s))
					ON CONFLICT (event_id) DO NOTHING
					""".formatted(schema.table(), schema.payloadCast())).bind("eventId", event.eventId())
					.bind("eventType", event.eventType()).bind("aggregateType", event.aggregateType())
					.bind("aggregateId", event.aggregateId()).bind("payload", payload).then();
		} catch (JsonProcessingException error) {
			return Mono.error(error);
		}
	}

	public Flux<OutboxRow> claimBatch(int limit, UUID claimToken, Duration leaseDuration) {
		long leaseMillis = Math.max(leaseDuration.toMillis(), 1L);
		return db.sql("""
				WITH candidates AS (
				    SELECT candidate.id
				    FROM %1$s AS candidate
				    WHERE published_at IS NULL
				      AND NOT EXISTS (
				          SELECT 1 FROM %1$s AS earlier
				          WHERE earlier.aggregate_id = candidate.aggregate_id
				            AND earlier.published_at IS NULL
				            AND (earlier.created_at, earlier.id) < (candidate.created_at, candidate.id)
				      )
				      AND next_attempt_at <= now()
				      AND (claimed_until IS NULL OR claimed_until <= now())
				    ORDER BY created_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT :limit
				)
				UPDATE %1$s AS target
				SET claimed_until = now() + (:leaseMillis * interval '1 millisecond'),
				    claim_token = CAST(:claimToken AS uuid),
				    attempt_count = target.attempt_count + 1,
				    last_error_code = NULL
				FROM candidates
				WHERE target.id = candidates.id
				RETURNING target.id::text, target.event_id, target.event_type,
				          target.aggregate_type, target.aggregate_id, target.payload::text,
				          target.claim_token::text, target.attempt_count
				""".formatted(schema.table())).bind("leaseMillis", leaseMillis)
				.bind("claimToken", claimToken.toString()).bind("limit", Math.max(limit, 1))
				.map(row -> new OutboxRow(row.get("id", String.class), row.get("event_id", String.class),
						row.get("event_type", String.class), row.get("aggregate_type", String.class),
						row.get("aggregate_id", String.class), row.get("payload", String.class),
						UUID.fromString(row.get("claim_token", String.class)),
						value(row.get("attempt_count", Integer.class), 0)))
				.all();
	}

	public Mono<Boolean> markPublished(String id, UUID claimToken) {
		return db.sql("""
				UPDATE %1$s
				SET published_at = now(), claimed_until = NULL, claim_token = NULL
				WHERE id = CAST(:id AS %2$s)
				  AND claim_token = CAST(:claimToken AS uuid)
				  AND published_at IS NULL
				""".formatted(schema.table(), schema.idCast())).bind("id", id).bind("claimToken", claimToken.toString())
				.fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
	}

	public Mono<Boolean> markFailure(String id, UUID claimToken, Duration retryDelay, String errorCode) {
		return db.sql("""
				UPDATE %1$s
				SET claimed_until = NULL,
				    claim_token = NULL,
				    next_attempt_at = now() + (:retryDelayMillis * interval '1 millisecond'),
				    last_error_code = :lastErrorCode
				WHERE id = CAST(:id AS %2$s)
				  AND claim_token = CAST(:claimToken AS uuid)
				  AND published_at IS NULL
				""".formatted(schema.table(), schema.idCast()))
				.bind("retryDelayMillis", Math.max(retryDelay.toMillis(), 1L))
				.bind("lastErrorCode", normalizeErrorCode(errorCode)).bind("id", id)
				.bind("claimToken", claimToken.toString()).fetch().rowsUpdated().map(updated -> updated > 0)
				.defaultIfEmpty(false);
	}

	public Mono<Long> pendingCount() {
		return db
				.sql(("SELECT COUNT(*)::bigint AS pending_count FROM %s WHERE published_at IS NULL")
						.formatted(schema.table()))
				.map(row -> value(row.get("pending_count", Long.class), 0L)).one().defaultIfEmpty(0L);
	}

	public Mono<Long> oldestPendingAgeSeconds() {
		return db.sql("""
				SELECT COALESCE(
				    GREATEST(FLOOR(EXTRACT(EPOCH FROM (now() - MIN(created_at)))), 0),
				    0)::bigint AS age_seconds
				FROM %s
				WHERE published_at IS NULL
				""".formatted(schema.table())).map(row -> value(row.get("age_seconds", Long.class), 0L)).one()
				.defaultIfEmpty(0L);
	}

	private static String normalizeErrorCode(String errorCode) {
		String normalized = errorCode == null || errorCode.isBlank() ? "Unknown" : errorCode;
		return normalized.length() <= MAX_ERROR_CODE_LENGTH
				? normalized
				: normalized.substring(0, MAX_ERROR_CODE_LENGTH);
	}

	private static long value(Long value, long fallback) {
		return value == null ? fallback : value;
	}

	private static int value(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	public record OutboxRow(String id, String eventId, String eventType, String aggregateType, String aggregateId,
			String payloadJson, UUID claimToken, int attemptCount) {
	}
}
