package com.grassland.intelligence.videoproduction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 治理台视频任务监控指标（任务书 #65 卡7）：窗口（7d/30d）内 SQL 聚合任务成功率/取消率/
 * 管线时长/供应商分布/成本收入/降级占比/重试与重抽率。**只读**——干预走既有模型/价目面板。
 *
 * <p>比率一律 0..1（四位小数），时长秒（一位小数）；金额分。指标口径：
 * <ul>
 *   <li>avgPipelineSeconds = succeeded 任务 completed_at − created_at 的均值；</li>
 *   <li>costCents = 任务关联 ai_run 的实际成本（平台支出），revenueCents = actual_cost_cents 汇总（一口价收入）；</li>
 *   <li>noVoiceRatio = 无任何 succeeded 配音的任务占比；retryRatio = 存在候选重试（attempts&gt;1）的任务占比；
 *       rerollRatio = recompose_seq&gt;0 的任务占比。</li>
 * </ul>
 */
@Service
public class VideoTaskMetricsService {

    private final DatabaseClient db;

    public VideoTaskMetricsService(DatabaseClient db) {
        this.db = db;
    }

    /** §3 契约的 metrics 载荷（有序，键名即契约字段）。 */
    public Mono<Map<String, Object>> metrics(String window) {
        int days = "30d".equals(window) ? 30 : 7;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(days);
        return Mono.zip(
                summary(cutoff),
                providers(cutoff),
                costCents(cutoff),
                noVoiceCount(cutoff),
                retriedCount(cutoff))
                .map(tuple -> {
                    Map<String, Object> summary = tuple.getT1();
                    long taskCount = (Long) summary.get("taskCountRaw");
                    long noVoice = tuple.getT4();
                    long retried = tuple.getT5();
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("window", days == 30 ? "30d" : "7d");
                    body.put("taskCount", taskCount);
                    body.put("successRate", ratio(summary.get("succeededRaw"), taskCount));
                    body.put("cancelRate", ratio(summary.get("cancelledRaw"), taskCount));
                    body.put("avgPipelineSeconds",
                            summary.get("avgPipelineSeconds") == null ? 0.0 : summary.get("avgPipelineSeconds"));
                    body.put("providers", tuple.getT2());
                    body.put("costVsRevenue", Map.of(
                            "costCents", tuple.getT3(),
                            "revenueCents", summary.get("revenueCents")));
                    body.put("degraded", Map.of(
                            "slideshowRatio", ratio(summary.get("slideshowRaw"), taskCount),
                            "noVoiceRatio", ratio(noVoice, taskCount)));
                    body.put("retryRatio", ratio(retried, taskCount));
                    body.put("rerollRatio", ratio(summary.get("rerolledRaw"), taskCount));
                    return body;
                });
    }

    private Mono<Map<String, Object>> summary(OffsetDateTime cutoff) {
        return db.sql("""
                        SELECT COUNT(*) AS task_count_raw,
                               COUNT(*) FILTER (WHERE phase='succeeded') AS succeeded_raw,
                               COUNT(*) FILTER (WHERE phase='cancelled') AS cancelled_raw,
                               ROUND(AVG(EXTRACT(EPOCH FROM (completed_at - created_at)))
                                     FILTER (WHERE phase='succeeded' AND completed_at IS NOT NULL), 1)
                                   AS avg_pipeline_seconds,
                               COUNT(*) FILTER (WHERE mode='slideshow') AS slideshow_raw,
                               COUNT(*) FILTER (WHERE recompose_seq > 0) AS rerolled_raw,
                               COALESCE(SUM(actual_cost_cents), 0) AS revenue_cents
                        FROM video_production_task WHERE created_at >= :cutoff
                        """)
                .bind("cutoff", cutoff)
                .map((row, metadata) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("taskCountRaw", row.get("task_count_raw", Long.class));
                    result.put("succeededRaw", row.get("succeeded_raw", Long.class));
                    result.put("cancelledRaw", row.get("cancelled_raw", Long.class));
                    result.put("avgPipelineSeconds",
                            row.get("avg_pipeline_seconds", Double.class) == null ? null
                                    : row.get("avg_pipeline_seconds", Double.class));
                    result.put("slideshowRaw", row.get("slideshow_raw", Long.class));
                    result.put("rerolledRaw", row.get("rerolled_raw", Long.class));
                    result.put("revenueCents", row.get("revenue_cents", Long.class));
                    return result;
                })
                .one();
    }

    private Mono<List<Map<String, Object>>> providers(OffsetDateTime cutoff) {
        return db.sql("""
                        SELECT COALESCE(provider, '(未解析)') AS provider,
                               COUNT(*) AS task_count,
                               ROUND(AVG(actual_duration_seconds), 1) AS avg_seconds,
                               COUNT(*) FILTER (WHERE phase='failed') AS failed_count
                        FROM video_production_task
                        WHERE created_at >= :cutoff AND provider IS NOT NULL
                        GROUP BY provider ORDER BY task_count DESC
                        """)
                .bind("cutoff", cutoff)
                .map((row, metadata) -> {
                    long count = row.get("task_count", Long.class);
                    long failed = row.get("failed_count", Long.class);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("provider", row.get("provider", String.class));
                    entry.put("taskCount", count);
                    entry.put("avgSeconds",
                            row.get("avg_seconds", Double.class) == null ? 0.0
                                    : row.get("avg_seconds", Double.class));
                    entry.put("failureRate", ratio(failed, count));
                    return entry;
                })
                .all()
                .collectList()
                .defaultIfEmpty(new ArrayList<>());
    }

    /** 平台成本：任务关联 ai_run 的实际结算成本分。 */
    private Mono<Long> costCents(OffsetDateTime cutoff) {
        return db.sql("""
                        SELECT COALESCE(SUM(r.actual_cents), 0) AS cost_cents
                        FROM ai_run r JOIN video_production_task t ON t.run_id = r.id
                        WHERE t.created_at >= :cutoff
                        """)
                .bind("cutoff", cutoff)
                .map(row -> row.get("cost_cents", Long.class))
                .one();
    }

    /** 无配音任务数：分镜下不存在 succeeded 配音行。 */
    private Mono<Long> noVoiceCount(OffsetDateTime cutoff) {
        return db.sql("""
                        SELECT COUNT(*) AS no_voice
                        FROM video_production_task t
                        WHERE t.created_at >= :cutoff
                          AND NOT EXISTS (
                              SELECT 1 FROM video_shot_audio a
                              JOIN video_shot s ON s.id = a.shot_id
                              WHERE s.storyboard_id = t.storyboard_id AND a.status = 'succeeded')
                        """)
                .bind("cutoff", cutoff)
                .map(row -> row.get("no_voice", Long.class))
                .one();
    }

    /** 存在候选重试的任务数（attempts > 1）。 */
    private Mono<Long> retriedCount(OffsetDateTime cutoff) {
        return db.sql("""
                        SELECT COUNT(*) AS retried
                        FROM video_production_task t
                        WHERE t.created_at >= :cutoff
                          AND EXISTS (
                              SELECT 1 FROM video_shot_take k
                              JOIN video_shot s ON s.id = k.shot_id
                              WHERE s.storyboard_id = t.storyboard_id AND k.attempts > 1)
                        """)
                .bind("cutoff", cutoff)
                .map(row -> row.get("retried", Long.class))
                .one();
    }

    private static double ratio(Object numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        long value = numerator instanceof Number number ? number.longValue() : 0;
        return Math.round(value * 10000.0 / denominator) / 10000.0;
    }
}
