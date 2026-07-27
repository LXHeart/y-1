package com.grassland.marketplace.reputation;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 声誉指标聚合（只读派生，无自有表）。
 *
 * <p>四项指标一次查询算完（四个标量子查询共用同一个 accountId 绑定），避免为一个人发四趟往返。
 * 平均响应时长取 decided_at（商家接单）→ <b>首次</b>交付物提交，负样本（首交早于接单，
 * 理论上不该出现）直接排除而不是算进平均里拉低数字。
 */
@Component
public class ReputationRepository {

    private static final String AGGREGATE_SQL = """
            SELECT
              (SELECT COUNT(*) FROM task_application
                 WHERE recommender_account_id = CAST(:acc AS uuid) AND status = 'accepted')::int
                AS accepted_count,
              (SELECT COUNT(*) FROM task_application
                 WHERE recommender_account_id = CAST(:acc AS uuid) AND confirmed_at IS NOT NULL)::int
                AS completed_count,
              (SELECT COUNT(*) FROM engagement_rating
                 WHERE recommender_account_id = CAST(:acc AS uuid))::int
                AS rating_count,
              (SELECT AVG(score) FROM engagement_rating
                 WHERE recommender_account_id = CAST(:acc AS uuid))::float8
                AS average_score,
              (SELECT AVG(EXTRACT(EPOCH FROM (s.first_at - a.decided_at)))
                 FROM task_application a
                 JOIN (SELECT application_id, MIN(created_at) AS first_at
                         FROM engagement_submission GROUP BY application_id) s
                   ON s.application_id = a.id
                WHERE a.recommender_account_id = CAST(:acc AS uuid)
                  AND a.decided_at IS NOT NULL
                  AND s.first_at >= a.decided_at)::float8
                AS average_response_seconds
            """;

    private final DatabaseClient db;

    public ReputationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 某推荐官的声誉指标。从未接单/从未被评分 → 各项为 0/null（不是 404：「这人还没干过活」本身就是事实）。 */
    public Mono<ReputationStats> statsOf(String accountId) {
        return db.sql(AGGREGATE_SQL).bind("acc", accountId)
                .map(ReputationRepository::map).one()
                .defaultIfEmpty(ReputationStats.empty());
    }

    private static ReputationStats map(Readable row) {
        return new ReputationStats(
                intOf(row, "accepted_count"),
                intOf(row, "completed_count"),
                intOf(row, "rating_count"),
                row.get("average_score", Double.class),
                row.get("average_response_seconds", Double.class));
    }

    private static int intOf(Readable row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value;
    }
}
