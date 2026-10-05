package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.MarketplaceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Execute actual PostgreSQL triggers, not a string comparison of generated SQL.
 */
class TaskContractRulesIT extends MarketplaceItSupport {
	private static final ObjectMapper JSON = new ObjectMapper();
	@Autowired
	private TaskRepository tasks;

	@Test
	void comparisonConsentVersionPreviewAndAcceptanceShareTheFieldSet() throws Exception {
		String taskId = UUID.randomUUID().toString();
		String appId = UUID.randomUUID().toString();
		db.sql("""
				INSERT INTO task(id, owner_account_id, organization_id, title, status, content_form, platform,
				    version, bounty_cents, review_required, delivery_deadline_days, cancel_policy, requirements)
				VALUES (CAST(:id AS uuid), gen_random_uuid(), gen_random_uuid(), '统一合同', 'published',
				    'article', 'zhihu', 1, 0, true, 5, '{"script":1250}',
				    '{"productServiceInfo":null,"mustInclude":["门店名称"],"forbiddenContent":[],
				      "publishStartAt":null,"publishEndAt":null,"metricRequirements":[],
				      "evidenceRequirements":[],"commissionLadder":null,"interaction":null}')
				""").bind("id", taskId).then().block();
		db.sql("""
				INSERT INTO task_version(task_id, version, title, content_form, platform, requirements)
				SELECT id, version, title, content_form, platform, requirements FROM task WHERE id=CAST(:id AS uuid)
				""").bind("id", taskId).then().block();
		db.sql("""
				INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents)
				VALUES (CAST(:app AS uuid), CAST(:task AS uuid), gen_random_uuid(), 'pending', 0)
				""").bind("app", appId).bind("task", taskId).then().block();
		Task task = tasks.findById(taskId).block();
		JsonNode expected = JSON.readTree(TaskContractTerms.snapshot(task).toString());
		assertThat(read("SELECT task_contract_terms(t)::text AS value FROM task t WHERE id=CAST(:id AS uuid)", taskId))
				.isEqualTo(expected);
		assertThat(
				read("SELECT contract_terms::text AS value FROM task_version WHERE task_id=CAST(:id AS uuid)", taskId))
				.isEqualTo(expected);
		assertThat(read("SELECT terms_snapshot_json::text AS value FROM task_application WHERE id=CAST(:id AS uuid)",
				appId)).isEqualTo(expected);

		db.sql("UPDATE task SET delivery_deadline_days=3, version=2 WHERE id=CAST(:id AS uuid)").bind("id", taskId)
				.then().block();
		assertThat(read("SELECT terms_snapshot_json::text AS value FROM task_application WHERE id=CAST(:id AS uuid)",
				appId)).isEqualTo(expected); // old consent is never silently rewritten
		db.sql("UPDATE task_application SET status='reconsent' WHERE id=CAST(:id AS uuid)").bind("id", appId).then()
				.block();
		db.sql("UPDATE task_application SET status='pending' WHERE id=CAST(:id AS uuid)").bind("id", appId).then()
				.block();
		JsonNode consent = read(
				"SELECT terms_snapshot_json::text AS value FROM task_application WHERE id=CAST(:id AS uuid)", appId);
		assertThat(consent.get("deliveryDeadlineDays").asInt()).isEqualTo(3);

		db.sql("""
				UPDATE task_application SET status='accepted', decided_at=now(), bounty_cents=1500,
				  reputation_level_at_accept=1, reputation_policy_version_at_accept=1,
				  settlement_delay_days_at_accept=2, commission_bonus_bps_at_accept=0, premium_support_at_accept=false
				WHERE id=CAST(:id AS uuid)
				""").bind("id", appId).then().block();
		JsonNode accepted = read(
				"SELECT task_context_snapshot::text AS value FROM task_application WHERE id=CAST(:id AS uuid)", appId);
		for (String key : TaskContractTerms.keys()) {
			assertThat(accepted.has(key)).as(key).isTrue();
			if (!key.equals("bountyCents"))
				assertThat(accepted.get(key)).as(key).isEqualTo(consent.get(key));
		}
		assertThat(accepted.get("bountyCents").asInt()).isEqualTo(1500); // accepted funding override
		db.sql("UPDATE task SET review_required=false, delivery_deadline_days=9 WHERE id=CAST(:id AS uuid)")
				.bind("id", taskId).then().block();
		assertThat(read("SELECT task_context_snapshot::text AS value FROM task_application WHERE id=CAST(:id AS uuid)",
				appId)).isEqualTo(accepted);
	}

	private JsonNode read(String sql, String id) throws Exception {
		return JSON
				.readTree(db.sql(sql).bind("id", id).map((row, meta) -> row.get("value", String.class)).one().block());
	}
}
