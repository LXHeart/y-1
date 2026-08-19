package com.grassland.marketplace.compliance;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MarketplaceComplianceRepository {

    private final DatabaseClient db;

    public MarketplaceComplianceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Summary> closureSummary(String accountId) {
        return db.sql("""
                SELECT
                  (SELECT COUNT(*) FROM task_application a
                   WHERE a.recommender_account_id = CAST(:accountId AS uuid)
                     AND (a.status IN ('pending', 'reserving') OR
                          (a.status = 'accepted' AND NOT EXISTS (
                            SELECT 1 FROM marketplace_outbox o
                            WHERE o.event_type = 'EngagementSettled' AND o.aggregate_id = a.id::text))))
                  +
                  (SELECT COUNT(*) FROM task_application a JOIN task t ON t.id = a.task_id
                   WHERE t.owner_account_id = CAST(:accountId AS uuid)
                     AND (a.status IN ('pending', 'reserving') OR
                          (a.status = 'accepted' AND NOT EXISTS (
                            SELECT 1 FROM marketplace_outbox o
                            WHERE o.event_type = 'EngagementSettled' AND o.aggregate_id = a.id::text))))
                    AS active_engagements,
                  (SELECT COUNT(*) FROM consumer_order
                   WHERE consumer_account_id = CAST(:accountId AS uuid)
                     AND status IN ('pending_payment', 'paid', 'redeeming', 'refund_pending',
                                    'after_sales_disputed')) AS active_orders
                """)
                .bind("accountId", accountId)
                .map(row -> new Summary(value(row.get("active_engagements", Long.class)),
                        value(row.get("active_orders", Long.class))))
                .one();
    }

    public Flux<String> activeEngagementRefs(String accountId) {
        return db.sql("""
                SELECT DISTINCT a.id::text AS engagement_ref
                FROM task_application a JOIN task t ON t.id = a.task_id
                WHERE (a.recommender_account_id = CAST(:accountId AS uuid)
                       OR t.owner_account_id = CAST(:accountId AS uuid))
                  AND a.status IN ('reserving', 'accepted')
                ORDER BY engagement_ref
                """)
                .bind("accountId", accountId)
                .map(row -> row.get("engagement_ref", String.class))
                .all();
    }

    public Mono<Map<String, Long>> erasePii(String accountId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        return update("UPDATE task_application SET note = NULL, updated_at = now()"
                        + " WHERE recommender_account_id = CAST(:id AS uuid) AND note IS NOT NULL", accountId)
                .doOnNext(value -> counts.put("applications", value))
                .then(update("UPDATE engagement_submission SET note = NULL, updated_at = now()"
                        + " WHERE recommender_account_id = CAST(:id AS uuid) AND note IS NOT NULL", accountId))
                .doOnNext(value -> counts.put("submissions", value))
                .then(update("UPDATE engagement_rating SET comment = NULL"
                        + " WHERE (recommender_account_id = CAST(:id AS uuid)"
                        + " OR rated_by_account_id = CAST(:id AS uuid)) AND comment IS NOT NULL", accountId))
                .doOnNext(value -> counts.put("ratings", value))
                .then(update("UPDATE consumer_review SET comment = NULL"
                        + " WHERE consumer_account_id = CAST(:id AS uuid) AND comment IS NOT NULL", accountId))
                .doOnNext(value -> counts.put("reviews", value))
                .then(update("UPDATE consumer_order SET refund_reason = NULL, updated_at = now()"
                        + " WHERE consumer_account_id = CAST(:id AS uuid) AND refund_reason IS NOT NULL", accountId))
                .doOnNext(value -> counts.put("orderRefundReasons", value))
                .then(update("UPDATE consumer_order_after_sales_dispute dispute"
                        + " SET reason = '[redacted]', resolution_reason = NULL"
                        + " WHERE consumer_account_id = CAST(:id AS uuid)"
                        + " AND (reason <> '[redacted]' OR resolution_reason IS NOT NULL)", accountId))
                .doOnNext(value -> counts.put("afterSalesReasons", value))
                .then(update("UPDATE consumer_order_attribution attribution SET reason = NULL"
                        + " WHERE attribution.order_id IN (SELECT id FROM consumer_order"
                        + " WHERE consumer_account_id = CAST(:id AS uuid))"
                        + " AND attribution.reason IS NOT NULL", accountId))
                .doOnNext(value -> counts.put("attributionReasons", value))
                .then(update("UPDATE marketing_attribution_event SET metadata = '{}'::jsonb"
                        + " WHERE recommender_account_id = CAST(:id AS uuid) AND metadata <> '{}'::jsonb", accountId))
                .doOnNext(value -> counts.put("attributionMetadata", value))
                .thenReturn(counts);
    }

    private Mono<Long> update(String sql, String accountId) {
        return db.sql(sql).bind("id", accountId).fetch().rowsUpdated();
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    public record Summary(long activeEngagements, long activeOrders) {}
}
