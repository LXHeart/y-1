package com.grassland.marketplace.reputation;

import io.r2dbc.spi.Readable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 声誉指标聚合（只读派生，无自有表）。
 *
 * <p>全部指标一次查询算完（标量子查询共用参数化的账号集合），避免按指标或账号重复往返。
 * 平均响应时长取 decided_at（商家接单）→ <b>首次</b>交付物提交，负样本（首交早于接单，
 * 理论上不该出现）直接排除而不是算进平均里拉低数字。
 */
@Component
public class ReputationRepository {

    /**
     * 任务书 #74 卡 D：该账号各平台完成履约数（口径与 level 端点一致：confirmed_at 非空按任务 platform 聚合）。
     * 供 trust 垂类硬配额抽签（涉案平台完成 ≥3 的熟手席）判定。
     */
    public Mono<java.util.Map<String, Integer>> platformCompletions(java.util.UUID accountId) {
        return db.sql("""
                SELECT COALESCE(t.platform, 'unknown') AS platform, COUNT(*)::int AS completions
                FROM task_application a
                JOIN task t ON t.id = a.task_id
                WHERE a.recommender_account_id = CAST(:account AS uuid)
                  AND a.confirmed_at IS NOT NULL
                GROUP BY 1
                """)
                .bind("account", accountId)
                .map(row -> java.util.Map.entry(
                        row.get("platform", String.class),
                        row.get("completions", Integer.class) == null ? 0 : row.get("completions", Integer.class)))
                .all()
                .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue)
                .map(map -> (java.util.Map<String, Integer>) java.util.Collections.unmodifiableMap(map))
                .defaultIfEmpty(java.util.Map.of());
    }

    private static final String AGGREGATE_SQL = """
            WITH requested(account_id) AS (VALUES %s),
            submission_agg AS (
              SELECT s.application_id, s.recommender_account_id,
                     MIN(s.created_at) AS first_at, MAX(s.created_at) AS last_at
              FROM engagement_submission s
              JOIN requested r ON r.account_id = s.recommender_account_id
              GROUP BY s.application_id, s.recommender_account_id
            ),
            application_agg AS (
              SELECT r.account_id,
                     COUNT(a.id) FILTER (WHERE a.status IN ('accepted', 'refunded'))::int AS accepted_count,
                     COUNT(a.id) FILTER (WHERE a.confirmed_at IS NOT NULL)::int AS completed_count,
                     COUNT(a.id) FILTER (WHERE a.status = 'refunded')::int AS merchant_cancelled_count,
                     COUNT(a.id) FILTER (WHERE a.status = 'rejected')::int AS rejected_count,
                     COUNT(a.id) FILTER (WHERE a.status = 'withdrawn')::int AS withdrawn_count,
                     AVG(EXTRACT(EPOCH FROM (s.first_at - a.decided_at)))
                       FILTER (WHERE a.decided_at IS NOT NULL AND s.first_at >= a.decided_at)::float8
                       AS average_response_seconds,
                     MAX(GREATEST(
                       CASE WHEN a.status = 'withdrawn' THEN a.updated_at ELSE a.created_at END,
                       COALESCE(s.last_at, a.created_at)
                     ))::timestamptz AS last_active_at
              FROM requested r
              LEFT JOIN task_application a ON a.recommender_account_id = r.account_id
              LEFT JOIN submission_agg s ON s.application_id = a.id
              GROUP BY r.account_id
            ),
            rating_agg AS (
              SELECT rating.recommender_account_id AS account_id,
                     COUNT(*)::int AS rating_count,
                     AVG(rating.score)::float8 AS average_score
              FROM engagement_rating rating
              JOIN requested r ON r.account_id = rating.recommender_account_id
              GROUP BY rating.recommender_account_id
            )
            SELECT
              applications.accepted_count,
              applications.completed_count,
              applications.merchant_cancelled_count,
              applications.rejected_count,
              applications.withdrawn_count,
              COALESCE(ratings.rating_count, 0)::int AS rating_count,
              ratings.average_score,
              applications.average_response_seconds,
              applications.last_active_at,
              requested.account_id::text AS account_id
            FROM requested
            JOIN application_agg applications ON applications.account_id = requested.account_id
            LEFT JOIN rating_agg ratings ON ratings.account_id = requested.account_id
            """;

    private final DatabaseClient db;

    public ReputationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 某推荐官的声誉指标。从未接单/从未被评分 → 各项为 0/null（不是 404：「这人还没干过活」本身就是事实）。 */
    public Mono<ReputationStats> statsOf(String accountId) {
        return statsOfAccounts(List.of(accountId))
                .map(stats -> stats.getOrDefault(accountId, ReputationStats.empty()));
    }

    /** 多账号一次往返聚合；VALUES 只包含参数占位符，账号值始终通过 R2DBC 绑定。 */
    public Mono<Map<String, ReputationStats>> statsOfAccounts(Collection<String> accountIds) {
        List<String> requested = accountIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return Mono.just(Map.of());
        }
        String values = IntStream.range(0, requested.size())
                .mapToObj(index -> "(CAST(:acc" + index + " AS uuid))")
                .collect(Collectors.joining(", "));
        GenericExecuteSpec spec = db.sql(AGGREGATE_SQL.formatted(values));
        for (int index = 0; index < requested.size(); index++) {
            spec = spec.bind("acc" + index, requested.get(index));
        }
        return spec.map(row -> new AccountStats(row.get("account_id", String.class), map(row))).all()
                .collectMap(AccountStats::accountId, AccountStats::stats, LinkedHashMap::new)
                .map(Map::copyOf);
    }

    private record AccountStats(String accountId, ReputationStats stats) {}

    private static ReputationStats map(Readable row) {
        return new ReputationStats(
                intOf(row, "accepted_count"),
                intOf(row, "completed_count"),
                intOf(row, "merchant_cancelled_count"),
                intOf(row, "rejected_count"),
                intOf(row, "withdrawn_count"),
                intOf(row, "rating_count"),
                row.get("average_score", Double.class),
                row.get("average_response_seconds", Double.class),
                toInstant(row.get("last_active_at", java.time.OffsetDateTime.class)));
    }

    private static int intOf(Readable row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value;
    }

    private static java.time.Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
