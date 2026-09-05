package com.grassland.marketplace.taskcatalog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read-side aggregation over task matching facts. No counters are duplicated
 * here.
 */
@Component
public class TaskMetricsRepository {

	private final DatabaseClient db;

	public TaskMetricsRepository(DatabaseClient db) {
		this.db = db;
	}

	public Flux<TaskProgress> findProgressByTaskIds(Collection<String> taskIds) {
		List<String> ids = taskIds == null
				? List.of()
				: taskIds.stream().filter(id -> id != null && !id.isBlank()).toList();
		if (ids.isEmpty()) {
			return Flux.empty();
		}
		return db
				.sql("""
						WITH app AS (
						    SELECT task_id,
						           COUNT(*)::int AS total,
						           COUNT(*) FILTER (WHERE status = 'pending')::int AS pending,
						           COUNT(*) FILTER (WHERE status = 'reserving')::int AS reserving,
						           COUNT(*) FILTER (WHERE status = 'accepted')::int AS accepted,
						           COUNT(*) FILTER (WHERE status = 'rejected')::int AS rejected,
						           COUNT(*) FILTER (WHERE status = 'withdrawn')::int AS withdrawn,
						           COUNT(*) FILTER (WHERE status = 'refunded')::int AS refunded,
						           COALESCE(SUM(bounty_cents) FILTER (WHERE status IN ('reserving','accepted')), 0)::bigint AS reserved_bounty,
						           COALESCE(SUM(bounty_cents) FILTER (WHERE confirmed_at IS NOT NULL), 0)::bigint AS confirmed_bounty,
						           COUNT(*) FILTER (WHERE confirmed_at IS NOT NULL)::int AS confirmed
						    FROM task_application
						    WHERE task_id::text IN (:taskIds)
						    GROUP BY task_id
						), submitted AS (
						    SELECT a.task_id,
						           COUNT(*) FILTER (WHERE s.status = 'submitted')::int AS submitted
						    FROM engagement_submission s
						    JOIN task_application a ON a.id = s.application_id
						    WHERE a.task_id::text IN (:taskIds)
						    GROUP BY a.task_id
						), settled AS (
						    SELECT a.task_id, COUNT(DISTINCT o.aggregate_id)::int AS settled
						    FROM marketplace_outbox o
						    JOIN task_application a ON a.id::text = o.aggregate_id
						    WHERE o.event_type = 'EngagementSettled'
						      AND a.task_id::text IN (:taskIds)
						    GROUP BY a.task_id
						)
						SELECT t.id::text AS task_id,
						       COALESCE(app.total, 0)::int AS total,
						       COALESCE(app.pending, 0)::int AS pending,
						       COALESCE(app.reserving, 0)::int AS reserving,
						       COALESCE(app.accepted, 0)::int AS accepted,
						       COALESCE(app.rejected, 0)::int AS rejected,
						       COALESCE(app.withdrawn, 0)::int AS withdrawn,
						       COALESCE(app.refunded, 0)::int AS refunded,
						       COALESCE(app.confirmed, 0)::int AS confirmed,
						       COALESCE(app.reserved_bounty, 0)::bigint AS reserved_bounty,
						       COALESCE(app.confirmed_bounty, 0)::bigint AS confirmed_bounty,
						       COALESCE(submitted.submitted, 0)::int AS submitted,
						       COALESCE(settled.settled, 0)::int AS settled,
						       COALESCE(counter.occupied_slots, 0)::int AS occupied
						FROM task t
						LEFT JOIN app ON app.task_id = t.id
						LEFT JOIN task_acceptance_counter counter ON counter.task_id = t.id
						LEFT JOIN submitted ON submitted.task_id = t.id
						LEFT JOIN settled ON settled.task_id = t.id
						WHERE t.id::text IN (:taskIds)
						GROUP BY t.id, app.total, app.pending, app.reserving, app.accepted, app.rejected,
						         app.withdrawn, app.refunded, app.confirmed, app.reserved_bounty,
						         app.confirmed_bounty, submitted.submitted, settled.settled, counter.occupied_slots
						""")
				.bind("taskIds", ids)
				.map((row, metadata) -> new TaskProgress(row.get("task_id", String.class),
						value(row.get("total", Integer.class)), value(row.get("pending", Integer.class)),
						value(row.get("reserving", Integer.class)), value(row.get("accepted", Integer.class)),
						value(row.get("rejected", Integer.class)), value(row.get("withdrawn", Integer.class)),
						value(row.get("refunded", Integer.class)), value(row.get("submitted", Integer.class)),
						value(row.get("confirmed", Integer.class)), value(row.get("settled", Integer.class)),
						value(row.get("occupied", Integer.class)), valueLong(row.get("reserved_bounty", Long.class)),
						valueLong(row.get("confirmed_bounty", Long.class))))
				.all();
	}

	public Mono<MerchantDashboard> dashboard(String organizationId, String storeId, Instant from, Instant to) {
		// 任务书 #77 卡 B（D2）连带：卡 B 后任务全为门店级，商家看板不传 storeId = 组织全量——
		// 保留 store_id IS NULL 谓词会让看板漏掉全部新任务。
		String scope = "t.organization_id = CAST(:organizationId AS uuid)"
				+ (storeId == null ? "" : " AND t.store_id = CAST(:storeId AS uuid)")
				+ (from == null ? "" : " AND t.created_at >= :from") + (to == null ? "" : " AND t.created_at < :to");
		var spec = db
				.sql("""
						WITH scoped AS (SELECT t.id, t.status FROM task t WHERE %s),
						app AS (
						    SELECT a.task_id, a.id, a.status, a.bounty_cents, a.confirmed_at
						    FROM task_application a JOIN scoped t ON t.id = a.task_id
						), ratings AS (
						    SELECT AVG(r.score)::double precision AS average_rating
						    FROM engagement_rating r JOIN app ON app.id = r.application_id
						), settled AS (
						    SELECT DISTINCT o.aggregate_id FROM marketplace_outbox o
						    JOIN app ON app.id::text = o.aggregate_id
						    WHERE o.event_type = 'EngagementSettled'
						)
						SELECT (SELECT COUNT(*)::int FROM scoped) AS task_count,
						       (SELECT COUNT(*)::int FROM scoped WHERE status = 'published') AS published_count,
						       (SELECT COUNT(*)::int FROM app) AS applications,
						       (SELECT COUNT(*)::int FROM app WHERE status = 'accepted') AS accepted,
						       (SELECT COUNT(*)::int FROM app WHERE confirmed_at IS NOT NULL) AS confirmed,
						       (SELECT COUNT(*)::int FROM settled) AS settled,
						       (SELECT COALESCE(SUM(bounty_cents),0)::bigint FROM app WHERE status IN ('reserving','accepted')) AS reserved_bounty,
						       (SELECT COALESCE(SUM(bounty_cents),0)::bigint FROM app WHERE id::text IN (SELECT aggregate_id FROM settled)) AS settled_bounty,
						       (SELECT average_rating FROM ratings) AS average_rating
						"""
						.formatted(scope))
				.bind("organizationId", organizationId);
		if (storeId != null)
			spec = spec.bind("storeId", storeId);
		if (from != null)
			spec = spec.bind("from", from.atOffset(ZoneOffset.UTC));
		if (to != null)
			spec = spec.bind("to", to.atOffset(ZoneOffset.UTC));
		return spec.map(row -> {
			int applications = value(row.get("applications", Integer.class));
			int accepted = value(row.get("accepted", Integer.class));
			return new MerchantDashboard(organizationId, storeId, value(row.get("task_count", Integer.class)),
					value(row.get("published_count", Integer.class)), applications, accepted,
					value(row.get("confirmed", Integer.class)), value(row.get("settled", Integer.class)),
					valueLong(row.get("reserved_bounty", Long.class)), valueLong(row.get("settled_bounty", Long.class)),
					applications == 0 ? 0d : ((double) accepted / applications),
					row.get("average_rating", Double.class), false, false, false, "not_collected");
		}).one().defaultIfEmpty(new MerchantDashboard(organizationId, storeId, 0, 0, 0, 0, 0, 0, 0L, 0L, 0d, null,
				false, false, false, "not_collected"));
	}

	private static int value(Integer value) {
		return value == null ? 0 : value;
	}

	private static long valueLong(Long value) {
		return value == null ? 0L : value;
	}
}
