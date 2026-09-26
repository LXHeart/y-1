package com.grassland.intelligence.hypit.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PricingRow;
import com.grassland.intelligence.hypit.project.HypitProjectRepository.ProjectRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 计划/估价持久与预检（任务书 #107-1 C107-08 / 卡步骤 6/7/8）。
 *
 * <p>真 PostgreSQL：plan 按 (project, plan_hash) 内容寻址幂等（同内容读原行、计划
 * 行不可变无更新路径）、估价快照 unknown 价格如实 null 不写 0 且 (plan, pricing_hash)
 * 幂等、build 预检对 revision/profileHash 变化 409 要求重新 plan、grant scope 必须
 * 落在计划 targets 内。引擎命令面（check/plan/pricing 经 sidecar）的语义真值由
 * B/tests/engine/planning.test.ts 对真实 distribution 覆盖，本 IT 不重复桩引擎。
 */
@TestPropertySource(properties = { "hypit.enabled=true" })
class HypitPlanIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";

	@Autowired
	HypitPlanService planService;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void seed() {
		db.sql("DELETE FROM hypit_pricing_snapshot WHERE plan_id IN (SELECT p.id FROM hypit_plan p"
				+ " JOIN hypit_project pr ON pr.id = p.project_id"
				+ " WHERE pr.account_id IN (:owner, :other))")
				.bind("owner", OWNER).bind("other", "eeeeeeee-0000-4000-8000-00000000000e")
				.then().then(db.sql("DELETE FROM hypit_plan WHERE project_id IN"
						+ " (SELECT id FROM hypit_project WHERE account_id IN (:owner, :other))")
						.bind("owner", OWNER).bind("other", "eeeeeeee-0000-4000-8000-00000000000e").then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:owner, :other)")
						.bind("owner", OWNER).bind("other", "eeeeeeee-0000-4000-8000-00000000000e").then())
				.block(Duration.ofSeconds(10));
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'plan-it', 'clone', 'ready', 3)")
				.bind("id", projectId.toString()).bind("owner", OWNER)
				.bind("ws", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10));
	}

	private ProjectRow currentProject() {
		return db.sql("SELECT id::text, account_id, workspace_id::text, title, mode, status, revision,"
				+ " version, head_manifest_hash, selected_run, deleted_at, created_at, updated_at"
				+ " FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId.toString())
				.map((row, meta) -> new ProjectRow(UUID.fromString(row.get("id", String.class)),
						row.get("account_id", String.class), UUID.fromString(row.get("workspace_id", String.class)),
						row.get("title", String.class), row.get("mode", String.class),
						row.get("status", String.class), row.get("revision", Long.class),
						row.get("version", Long.class), row.get("head_manifest_hash", String.class),
						row.get("selected_run", String.class), row.get("deleted_at", java.time.Instant.class),
						row.get("created_at", java.time.Instant.class), row.get("updated_at", java.time.Instant.class)))
				.one().block(Duration.ofSeconds(10));
	}

	/** 与服务层过渡 profileHash 同值（C23 前恒定）；z 开头的 hash 代表「已变化」。 */
	private static final String PROFILE_HASH =
			com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge
					.sha256Hex("{\"profile\":null}");

	private PlanRow insertPlan(long revision, String planHash, String planJson) {
		return plans
				.insertPlan(UUID.randomUUID(), projectId, revision, "main.svrun", planHash, PROFILE_HASH, planJson)
				.block(Duration.ofSeconds(10));
	}

	// ---------- 步骤 6：内容寻址、不可变 ----------

	@Test
	void samePlanContentIsIdempotentByHashAndRowsNeverChange() {
		String planJson = "{\"targets\":[\"final.video\"],\"steps\":4}";
		PlanRow first = insertPlan(3, "a".repeat(64), planJson);
		PlanRow replay = insertPlan(3, "a".repeat(64), planJson);
		assertThat(replay.id()).as("同内容幂等返回原行").isEqualTo(first.id());
		// jsonb 列读回为规范形态：按语义比较（计划内容不可变）
		Map<String, Object> stored = com.grassland.intelligence.hypit.project.HypitJson.read(first.planJson());
		assertThat(stored.get("targets")).isEqualTo(java.util.List.of("final.video"));
		assertThat(((Number) stored.get("steps")).intValue()).isEqualTo(4);

		// 不同内容不同 hash：新行（计划历史保留，不覆盖）
		PlanRow divergent = insertPlan(3, "b".repeat(64), "{\"targets\":[\"final.video\"],\"steps\":5}");
		assertThat(divergent.id()).isNotEqualTo(first.id());
		assertThat(plans.listPlans(projectId, 10).collectList().block(Duration.ofSeconds(10))).hasSize(2);
	}

	// ---------- 步骤 7：估价快照 ----------

	@Test
	void pricingSnapshotKeepsUnknownsNullAndIsHashIdempotent() {
		PlanRow plan = insertPlan(3, "a".repeat(64), "{\"targets\":[\"poster.image\"]}");
		String costs = "{\"rows\":[{\"request\":\"need-1\",\"capability\":\"@hypit/gpt-image@1#image\","
				+ "\"status\":\"resolved\",\"estimatedCost\":null,\"currency\":null}]}";
		String unknowns = "{\"requests\":[\"need-1\"]}";
		PricingRow first = plans.insertPricing(UUID.randomUUID(), plan.id(), "c".repeat(64), costs, unknowns)
				.block(Duration.ofSeconds(10));
		PricingRow replay = plans.insertPricing(UUID.randomUUID(), plan.id(), "c".repeat(64), costs, unknowns)
				.block(Duration.ofSeconds(10));
		assertThat(replay.id()).as("同 hash 估价幂等").isEqualTo(first.id());
		assertThat(first.unknownsJson()).as("unknown 请求如实成行").contains("need-1");
		Map<String, Object> costsDoc = com.grassland.intelligence.hypit.project.HypitJson.read(first.costsJson());
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> costRows = (List<Map<String, Object>>) costsDoc.get("rows");
		assertThat(costRows.get(0).get("estimatedCost")).as("价格未知保持 null，不写 0").isNull();

		PricingRow latest = plans.latestPricing(plan.id()).block(Duration.ofSeconds(10));
		assertThat(latest.pricingHash()).isEqualTo("c".repeat(64));
	}

	// ---------- 步骤 8：build 预检 ----------

	@Test
	void planFreshnessRejectsRevisionProfileAndForeignChanges() {
		PlanRow plan = insertPlan(3, "a".repeat(64), "{\"targets\":[\"final.video\"]}");
		ProjectRow current = currentProject();
		assertThat(planService.requirePlanFresh(current, plan.id()).block(Duration.ofSeconds(10)).id())
				.isEqualTo(plan.id());

		// revision 前进 → 409 重新 plan
		db.sql("UPDATE hypit_project SET revision = 4 WHERE id = CAST(:id AS uuid)").bind("id", projectId.toString())
				.then().block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> planService.requirePlanFresh(currentProject(), plan.id())
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class, error -> {
					assertThat(error.status()).isEqualTo(409);
					assertThat(error.code()).isEqualTo("hypit_plan_stale");
				});

		// profileHash 变化（C23 前的过渡口径：非过渡值一律视为 Profile 已变）→ 409
		db.sql("UPDATE hypit_project SET revision = 3 WHERE id = CAST(:id AS uuid)").bind("id", projectId.toString())
				.then().block(Duration.ofSeconds(10));
		// 已变化的 Profile（非过渡 hash）→ 409
		db.sql("UPDATE hypit_project SET revision = 3 WHERE id = CAST(:id AS uuid)").bind("id", projectId.toString())
				.then().block(Duration.ofSeconds(10));
		PlanRow drifted = plans.insertPlan(UUID.randomUUID(), projectId, 3, "main.svrun", "d".repeat(64),
				"z".repeat(64), "{\"targets\":[]}")
				.block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> planService.requirePlanFresh(currentProject(), drifted.id())
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class, error -> {
					assertThat(error.status()).isEqualTo(409);
					assertThat(error.code()).isEqualTo("hypit_plan_stale");
				});
		// 外部 plan（他工程的行）→ 404 不泄漏存在性
		UUID foreignProject = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status)"
				+ " VALUES (CAST(:id AS uuid), 'eeeeeeee-0000-4000-8000-00000000000e', CAST(:ws AS uuid),"
				+ " 'other', 'clone', 'ready')").bind("id", foreignProject.toString())
				.bind("ws", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10));
		PlanRow foreign = plans
				.insertPlan(UUID.randomUUID(), foreignProject, 1, "main.svrun", "f".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10));
		assertThatThrownBy(() -> planService.requirePlanFresh(currentProject(), foreign.id())
				.block(Duration.ofSeconds(10))).isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(404));
	}

	@Test
	void grantScopeMustBeCoveredByPlanTargets() {
		PlanRow plan = insertPlan(3, "a".repeat(64), "{\"targets\":[\"final.video\",\"poster.image\"]}");
		HypitPlanService.requireGrantCovers(plan, "{\"targets\":[\"final.video\"]}");
		HypitPlanService.requireGrantCovers(plan, "{}");
		assertThatThrownBy(() -> HypitPlanService.requireGrantCovers(plan, "{\"targets\":[\"other.video\"]}"))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> {
					assertThat(error.status()).isEqualTo(409);
					assertThat(error.code()).isEqualTo("hypit_plan_stale");
				});
	}
}
