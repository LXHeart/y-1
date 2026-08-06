package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
}
