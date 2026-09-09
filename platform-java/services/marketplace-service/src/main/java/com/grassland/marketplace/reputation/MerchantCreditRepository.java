package com.grassland.marketplace.reputation;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-04：商家信用四指标的读时派生事实（单 SQL 聚合，无落表快照——读时派生口径）。
 *
 * <p>
 * 事实来源全部为 marketplace 自有表：task（发布/取消）、task_application（确认/验收超时）、
 * experience_benefit（体验失约）、consumer_order_after_sales_dispute（争议裁定）。
 */
@Component
public class MerchantCreditRepository {

	private final DatabaseClient db;

	public MerchantCreditRepository(DatabaseClient db) {
		this.db = db;
	}

	public record OrgCreditFacts(long publishedTasks, long cancelledTasks, long confirmations, long autoConfirmations,
			long benefits, long benefitDefaults, long resolvedDisputes, long disputeLosses, long cooperations) {
	}

	public Mono<OrgCreditFacts> facts(String organizationId) {
		return db
				.sql("""
						SELECT
						  (SELECT count(*) FROM task WHERE organization_id = CAST(:org AS uuid)
						     AND published_at IS NOT NULL) AS published_tasks,
						  (SELECT count(*) FROM task WHERE organization_id = CAST(:org AS uuid)
						     AND published_at IS NOT NULL AND status = 'cancelled') AS cancelled_tasks,
						  (SELECT count(*) FROM task_application a JOIN task t ON t.id = a.task_id
						     WHERE t.organization_id = CAST(:org AS uuid) AND a.confirmed_at IS NOT NULL) AS confirmations,
						  (SELECT count(*) FROM task_application a JOIN task t ON t.id = a.task_id
						     WHERE t.organization_id = CAST(:org AS uuid) AND a.auto_confirmed_at IS NOT NULL) AS auto_confirmations,
						  (SELECT count(*) FROM experience_benefit b
						     JOIN task_application a ON a.id = b.application_id JOIN task t ON t.id = a.task_id
						     WHERE t.organization_id = CAST(:org AS uuid)) AS benefits,
						  (SELECT count(*) FROM experience_benefit b
						     JOIN task_application a ON a.id = b.application_id JOIN task t ON t.id = a.task_id
						     WHERE t.organization_id = CAST(:org AS uuid) AND b.status = 'merchant_defaulted') AS benefit_defaults,
						  (SELECT count(*) FROM consumer_order_after_sales_dispute d
						     JOIN consumer_order o ON o.id = d.order_id
						     WHERE o.organization_id = CAST(:org AS uuid) AND d.resolution IS NOT NULL) AS resolved_disputes,
						  (SELECT count(*) FROM consumer_order_after_sales_dispute d
						     JOIN consumer_order o ON o.id = d.order_id
						     WHERE o.organization_id = CAST(:org AS uuid) AND d.resolution = 'refund') AS dispute_losses,
						  (SELECT count(*) FROM task_application a JOIN task t ON t.id = a.task_id
						     WHERE t.organization_id = CAST(:org AS uuid)
						       AND (a.status IN ('accepted', 'reserving') OR a.confirmed_at IS NOT NULL)) AS cooperations
						""")
				.bind("org", organizationId)
				.map((row, metadata) -> new OrgCreditFacts(row.get("published_tasks", Long.class),
						row.get("cancelled_tasks", Long.class), row.get("confirmations", Long.class),
						row.get("auto_confirmations", Long.class), row.get("benefits", Long.class),
						row.get("benefit_defaults", Long.class), row.get("resolved_disputes", Long.class),
						row.get("dispute_losses", Long.class), row.get("cooperations", Long.class)))
				.one();
	}
}
