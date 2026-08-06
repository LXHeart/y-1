package com.grassland.marketplace.ops;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * 「待判定」核验查询（GL-P1-OPS-001 + GL-P2-ADMIN-004）。
 *
 * <p>查询自动核验为 inconclusive、交付物仍 submitted 且尚未有人工作出 override 的记录。
 * 人工改判写入 marketplace 的 {@code verification_override}，不改自动核验真相表。
 */
@Repository
public class OpsPendingVerificationRepository {

    private final DatabaseClient db;

    public OpsPendingVerificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 列出待人工复核核验，最近核验的在前。 */
    public Flux<OpsPendingVerification> list(int limit) {
        return db.sql("""
                        SELECT v.id::text AS verification_id, s.id::text AS submission_id,
                               a.id::text AS application_id, t.id::text AS task_id, t.title AS task_title,
                               t.organization_id::text AS organization_id,
                               s.recommender_account_id::text AS recommender_account_id,
                               s.content_url, v.checks::text AS checks_json,
                               v.last_checked_at, s.created_at AS submitted_at
                        FROM engagement_verification v
                        JOIN engagement_submission s ON s.id = v.submission_id
                        JOIN task_application a ON a.id = s.application_id
                        JOIN task t ON t.id = a.task_id
                        WHERE v.status = 'inconclusive'
                          AND s.status = 'submitted'
                          AND NOT EXISTS (
                              SELECT 1 FROM verification_override vo
                              WHERE vo.submission_id = v.submission_id
                          )
                        ORDER BY v.last_checked_at DESC, v.id
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, meta) -> new OpsPendingVerification(
                        row.get("verification_id", String.class),
                        row.get("submission_id", String.class),
                        row.get("application_id", String.class),
                        row.get("task_id", String.class),
                        row.get("task_title", String.class),
                        row.get("organization_id", String.class),
                        row.get("recommender_account_id", String.class),
                        row.get("content_url", String.class),
                        row.get("checks_json", String.class),
                        row.get("last_checked_at", java.time.Instant.class),
                        row.get("submitted_at", java.time.Instant.class)))
                .all();
    }
}
