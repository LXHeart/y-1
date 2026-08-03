package com.grassland.trust.dispute;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 延后异议持久化与自动审判 durable dispatch intent。 */
@Component
public class DeferredDisputeRequestRepository {

    private static final String SELECT_COLS =
            "id::text, source_dispute_id::text, engagement_ref, organization_id::text,"
                    + " recommender_account_id::text, reason, status, promoted_dispute_id::text,"
                    + " adjudication_workflow_id, adjudication_workflow_started_at, created_at, updated_at";

    private final DatabaseClient db;

    public DeferredDisputeRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 同一客服案/推荐官只保留一份逐字 reason；首次返回新行，重复返回既有行。 */
    public Mono<DeferredDisputeRequest> createOrFind(
            DisputeCase source, String recommenderAccountId, String reason) {
        String id = UUID.randomUUID().toString();
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO deferred_dispute_request(
                    id, source_dispute_id, engagement_ref, organization_id, recommender_account_id, reason)
                VALUES (CAST(:id AS uuid), CAST(:source AS uuid), :ref, CAST(:org AS uuid),
                        CAST(:account AS uuid), :reason)
                ON CONFLICT (source_dispute_id, recommender_account_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("source", source.id()).bind("ref", source.engagementRef())
                .bind("org", source.organizationId()).bind("account", recommenderAccountId);
        spec = bindNullable(spec, "reason", reason);
        return spec.map(DeferredDisputeRequestRepository::map).one()
                .switchIfEmpty(findBySourceAndRecommender(source.id(), recommenderAccountId));
    }

    public Mono<DeferredDisputeRequest> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM deferred_dispute_request WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(DeferredDisputeRequestRepository::map).one();
    }

    public Mono<DeferredDisputeRequest> findBySource(String sourceDisputeId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM deferred_dispute_request"
                        + " WHERE source_dispute_id = CAST(:source AS uuid) ORDER BY created_at LIMIT 1")
                .bind("source", sourceDisputeId).map(DeferredDisputeRequestRepository::map).one();
    }

    public Mono<DeferredDisputeRequest> findPendingBySource(String sourceDisputeId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM deferred_dispute_request"
                        + " WHERE source_dispute_id = CAST(:source AS uuid) AND status = 'pending'"
                        + " ORDER BY created_at LIMIT 1")
                .bind("source", sourceDisputeId).map(DeferredDisputeRequestRepository::map).one();
    }

    public Mono<DeferredDisputeRequest> findBySourceAndRecommender(String sourceDisputeId, String recommenderAccountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM deferred_dispute_request"
                        + " WHERE source_dispute_id = CAST(:source AS uuid)"
                        + " AND recommender_account_id = CAST(:account AS uuid)")
                .bind("source", sourceDisputeId).bind("account", recommenderAccountId)
                .map(DeferredDisputeRequestRepository::map).one();
    }

    /** source dispute 已 final 且 successor 已 insert 后，在同一事务中消费 pending request。 */
    public Mono<DeferredDisputeRequest> markPromoted(String requestId, String promotedDisputeId) {
        String workflowId = "adjudicate-" + promotedDisputeId;
        return db.sql("""
                UPDATE deferred_dispute_request
                SET status = 'promoted', promoted_dispute_id = CAST(:dispute AS uuid),
                    adjudication_workflow_id = :workflow, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", requestId).bind("dispute", promotedDisputeId).bind("workflow", workflowId)
                .map(DeferredDisputeRequestRepository::map).one();
    }

    public Flux<DeferredDisputeRequest> findAdjudicationDispatchable(int limit) {
        return db.sql("SELECT " + SELECT_COLS + " FROM deferred_dispute_request"
                        + " WHERE status = 'promoted' AND adjudication_workflow_started_at IS NULL"
                        + " ORDER BY updated_at LIMIT :limit")
                .bind("limit", Math.max(1, limit))
                .map(DeferredDisputeRequestRepository::map).all();
    }

    public Mono<Boolean> markAdjudicationWorkflowStarted(String requestId, String promotedDisputeId) {
        return db.sql("""
                UPDATE deferred_dispute_request
                SET adjudication_workflow_started_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND promoted_dispute_id = CAST(:dispute AS uuid)
                  AND adjudication_workflow_started_at IS NULL
                """)
                .bind("id", requestId).bind("dispute", promotedDisputeId)
                .fetch().rowsUpdated().map(n -> n > 0).defaultIfEmpty(false);
    }

    private static DeferredDisputeRequest map(Readable row) {
        return new DeferredDisputeRequest(
                row.get("id", String.class), row.get("source_dispute_id", String.class),
                row.get("engagement_ref", String.class), row.get("organization_id", String.class),
                row.get("recommender_account_id", String.class), row.get("reason", String.class),
                row.get("status", String.class), row.get("promoted_dispute_id", String.class),
                row.get("adjudication_workflow_id", String.class),
                toInstant(row.get("adjudication_workflow_started_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
