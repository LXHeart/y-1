package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * 任务审核决定流水（GL-P2-ADMIN-003，V19 task_review 表）。只追加不改不删——审计流水一旦可改就不再是审计。
 */
@Component
public class TaskReviewRepository {

    private final DatabaseClient db;

    public TaskReviewRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 追加一条审核决定。action=submitted/approved/rejected。 */
    public Mono<Void> append(String taskId, String action, String reviewerAccountId, String note) {
        var spec = db.sql("""
                INSERT INTO task_review(task_id, action, reviewer_account_id, review_note)
                VALUES (CAST(:taskId AS uuid), :action, CAST(:reviewer AS uuid), :note)
                """)
                .bind("taskId", taskId).bind("action", action);
        spec = reviewerAccountId == null ? spec.bindNull("reviewer", String.class) : spec.bind("reviewer", reviewerAccountId);
        spec = (note == null || note.isBlank()) ? spec.bindNull("note", String.class) : spec.bind("note", note);
        return spec.then();
    }

    public Flux<TaskReviewEntry> findHistory(String taskId, int limit, int offset) {
        return db.sql("""
                SELECT id::text, task_id::text, action, reviewer_account_id::text, review_note, created_at
                FROM task_review WHERE task_id = CAST(:taskId AS uuid)
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """)
                .bind("taskId", taskId).bind("limit", Math.max(1, Math.min(limit, 200)))
                .bind("offset", Math.max(0, offset))
                .map((row, metadata) -> new TaskReviewEntry(
                        row.get("id", String.class), row.get("task_id", String.class),
                        row.get("action", String.class), row.get("reviewer_account_id", String.class),
                        row.get("review_note", String.class),
                        row.get("created_at", java.time.OffsetDateTime.class).toInstant()))
                .all();
    }

    public Mono<ReviewQueueStats> queueStats() {
        return db.sql("""
                SELECT COUNT(*) FILTER (WHERE status = 'pending_review')::int AS pending,
                       COUNT(*) FILTER (WHERE status = 'pending_review' AND updated_at < now() - interval '24 hours')::int AS overdue,
                       (SELECT COUNT(*)::int FROM task_review WHERE action = 'approved' AND created_at >= now() - interval '24 hours') AS approved_24h,
                       (SELECT COUNT(*)::int FROM task_review WHERE action = 'rejected' AND created_at >= now() - interval '24 hours') AS rejected_24h
                FROM task
                """)
                .map(row -> new ReviewQueueStats(
                        value(row.get("pending", Integer.class)), value(row.get("overdue", Integer.class)),
                        value(row.get("approved_24h", Integer.class)), value(row.get("rejected_24h", Integer.class))))
                .one();
    }

    public record TaskReviewEntry(String id, String taskId, String action, String reviewerAccountId,
                                  String note, java.time.Instant createdAt) {}

    public record ReviewQueueStats(int pending, int overdue, int approvedLast24Hours, int rejectedLast24Hours) {}

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
