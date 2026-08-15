package com.grassland.marketplace.matching;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Immutable invitation score snapshot plus the single applied-at lifecycle transition. */
@Component
public class TaskRecommenderInvitationRepository {

    private static final String COLUMNS = """
            id::text, task_id::text, recommender_account_id::text, invited_by_account_id::text,
            scoring_version, score_snapshot::text, created_at, applied_at
            """;

    private final DatabaseClient db;

    public TaskRecommenderInvitationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<TaskRecommenderInvitation> create(
            String taskId, String recommenderAccountId, String invitedByAccountId,
            String scoringVersion, String scoreSnapshotJson) {
        return db.sql("""
                INSERT INTO task_recommender_invitation(
                    task_id, recommender_account_id, invited_by_account_id, scoring_version, score_snapshot)
                VALUES (CAST(:taskId AS uuid), CAST(:candidate AS uuid), CAST(:actor AS uuid),
                        :version, CAST(:snapshot AS jsonb))
                ON CONFLICT(task_id, recommender_account_id) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS))
                .bind("taskId", taskId).bind("candidate", recommenderAccountId)
                .bind("actor", invitedByAccountId).bind("version", scoringVersion)
                .bind("snapshot", scoreSnapshotJson)
                .map(TaskRecommenderInvitationRepository::map).one();
    }

    public Mono<TaskRecommenderInvitation> find(String taskId, String recommenderAccountId) {
        return db.sql("SELECT " + COLUMNS + " FROM task_recommender_invitation"
                        + " WHERE task_id=CAST(:taskId AS uuid) AND recommender_account_id=CAST(:candidate AS uuid)")
                .bind("taskId", taskId).bind("candidate", recommenderAccountId)
                .map(TaskRecommenderInvitationRepository::map).one();
    }

    public Mono<Boolean> markApplied(String taskId, String recommenderAccountId) {
        return db.sql("""
                UPDATE task_recommender_invitation SET applied_at=COALESCE(applied_at, now())
                WHERE task_id=CAST(:taskId AS uuid)
                  AND recommender_account_id=CAST(:candidate AS uuid)
                  AND applied_at IS NULL
                """)
                .bind("taskId", taskId).bind("candidate", recommenderAccountId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private static TaskRecommenderInvitation map(Readable row) {
        return new TaskRecommenderInvitation(
                row.get("id", String.class), row.get("task_id", String.class),
                row.get("recommender_account_id", String.class), row.get("invited_by_account_id", String.class),
                row.get("scoring_version", String.class), row.get("score_snapshot", String.class),
                toInstant(row.get("created_at", java.time.OffsetDateTime.class)),
                toInstant(row.get("applied_at", java.time.OffsetDateTime.class)));
    }

    private static java.time.Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
