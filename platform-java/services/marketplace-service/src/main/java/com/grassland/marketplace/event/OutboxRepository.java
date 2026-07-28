package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class OutboxRepository {

    private static final int MAX_ERROR_CODE_LENGTH = 64;

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public OutboxRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> append(EventEnvelope event) {
        try {
            String payload = mapper.writeValueAsString(event.payload());
            return db.sql("""
                            INSERT INTO marketplace_outbox
                                (event_id, event_type, aggregate_type, aggregate_id, payload)
                            VALUES (:eventId, :eventType, :aggType, :aggId, CAST(:payload AS json))
                            ON CONFLICT (event_id) DO NOTHING
                            """)
                    .bind("eventId", event.eventId())
                    .bind("eventType", event.eventType())
                    .bind("aggType", event.aggregateType())
                    .bind("aggId", event.aggregateId())
                    .bind("payload", payload)
                    .then();
        } catch (Exception error) {
            return Mono.error(error);
        }
    }

    public Flux<OutboxRow> claimBatch(String claimToken, int limit, Duration claimDuration) {
        return db.sql("""
                        WITH candidates AS (
                            SELECT candidate.id
                            FROM marketplace_outbox AS candidate
                            WHERE candidate.published_at IS NULL
                              AND NOT EXISTS (
                                  SELECT 1 FROM marketplace_outbox AS earlier
                                  WHERE earlier.aggregate_id = candidate.aggregate_id
                                    AND earlier.published_at IS NULL
                                    AND (earlier.created_at, earlier.id)
                                        < (candidate.created_at, candidate.id)
                              )
                              AND candidate.next_attempt_at <= now()
                              AND (candidate.claimed_until IS NULL OR candidate.claimed_until <= now())
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :limit
                        )
                        UPDATE marketplace_outbox AS outbox
                        SET claim_token = CAST(:claimToken AS uuid),
                            claimed_until = now() + CAST(:claimMillis AS bigint) * interval '1 millisecond',
                            attempt_count = outbox.attempt_count + 1,
                            last_error_code = NULL
                        FROM candidates
                        WHERE outbox.id = candidates.id
                        RETURNING outbox.id::text, outbox.event_id, outbox.event_type,
                                  outbox.aggregate_type, outbox.aggregate_id, outbox.payload::text,
                                  outbox.attempt_count, outbox.claim_token::text, outbox.claimed_until
                        """)
                .bind("limit", limit)
                .bind("claimToken", claimToken)
                .bind("claimMillis", claimDuration.toMillis())
                .map(row -> new OutboxRow(
                        row.get("id", String.class),
                        row.get("event_id", String.class),
                        row.get("event_type", String.class),
                        row.get("aggregate_type", String.class),
                        row.get("aggregate_id", String.class),
                        row.get("payload", String.class),
                        valueOrZero(row.get("attempt_count", Integer.class)),
                        row.get("claim_token", String.class),
                        row.get("claimed_until", Instant.class)))
                .all();
    }

    public Mono<Boolean> markPublished(String id, String claimToken) {
        return db.sql("""
                        UPDATE marketplace_outbox
                        SET published_at = now(), claim_token = NULL, claimed_until = NULL, last_error_code = NULL
                        WHERE id = CAST(:id AS uuid)
                          AND published_at IS NULL
                          AND claim_token = CAST(:claimToken AS uuid)
                        """)
                .bind("id", id)
                .bind("claimToken", claimToken)
                .fetch()
                .rowsUpdated()
                .map(updated -> updated > 0);
    }

    public Mono<Boolean> markFailed(
            String id, String claimToken, String errorCode, Duration retryBackoff) {
        String safeCode = normalizeErrorCode(errorCode);
        return db.sql("""
                        UPDATE marketplace_outbox
                        SET next_attempt_at = now() + CAST(:backoffMillis AS bigint) * interval '1 millisecond',
                            claim_token = NULL,
                            claimed_until = NULL,
                            last_error_code = :errorCode
                        WHERE id = CAST(:id AS uuid)
                          AND published_at IS NULL
                          AND claim_token = CAST(:claimToken AS uuid)
                        """)
                .bind("id", id)
                .bind("claimToken", claimToken)
                .bind("backoffMillis", retryBackoff.toMillis())
                .bind("errorCode", safeCode)
                .fetch()
                .rowsUpdated()
                .map(updated -> updated > 0);
    }

    public record OutboxRow(
            String id,
            String eventId,
            String eventType,
            String aggregateType,
            String aggregateId,
            String payloadJson,
            int attemptCount,
            String claimToken,
            Instant claimedUntil) {}

    public Mono<String> latestReservationFailureReason(String applicationId) {
        return db.sql("""
                        SELECT payload->>'reason' AS reason FROM marketplace_outbox
                        WHERE event_type = 'ApplicationReservationFailed' AND aggregate_id = :appId
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .bind("appId", applicationId)
                .map(row -> row.get("reason", String.class))
                .one()
                .filter(reason -> reason != null && !reason.isBlank());
    }

    public Mono<Map<String, Object>> latestSettlementStatus(String applicationId) {
        return db.sql("""
                        SELECT event_type AS et, payload->>'reason' AS reason FROM marketplace_outbox
                        WHERE event_type IN ('EngagementSettled','SettlementHeld') AND aggregate_id = :appId
                        ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .bind("appId", applicationId)
                .map(row -> {
                    Map<String, Object> status = new LinkedHashMap<>();
                    status.put("status", "EngagementSettled".equals(row.get("et", String.class))
                            ? "settled"
                            : "held");
                    String reason = row.get("reason", String.class);
                    if (reason != null) {
                        status.put("reason", reason);
                    }
                    return status;
                })
                .one();
    }

    private static String normalizeErrorCode(String errorCode) {
        String normalized = errorCode == null || errorCode.isBlank()
                ? "UNKNOWN_PUBLISH_ERROR"
                : errorCode.replaceAll("[^A-Z0-9_]", "_");
        return normalized.length() <= MAX_ERROR_CODE_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_CODE_LENGTH);
    }

    private static int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
