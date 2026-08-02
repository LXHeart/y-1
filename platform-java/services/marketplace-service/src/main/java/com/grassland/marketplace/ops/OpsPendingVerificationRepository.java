package com.grassland.marketplace.ops;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

/**
 * 「待判定」核验只读查询（GL-P1-OPS-001 Stage 3）。
 *
 * <p>没有 insert/update：这是**派生视图**，不是新状态。数据真相在 {@code engagement_verification}，
 * 运营台只是换个切面看它 —— 加一张影子表就得处理「商家改判后影子表怎么同步」，那是白造的一致性问题。
 *
 * <p>三张表 JOIN 都是同库真 FK 路径（verification→submission→application→task），无跨服务调用。
 */
@Repository
public class OpsPendingVerificationRepository {

    private final DatabaseClient db;

    public OpsPendingVerificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 列出待判定核验，最近核验的在前。
     *
     * <p>{@code status='submitted'} 是关键过滤：商家 confirm/reject 之后人工判定已发生，
     * 该条不再属于「待判定」。
     */
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
                        WHERE v.status = 'inconclusive' AND s.status = 'submitted'
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
