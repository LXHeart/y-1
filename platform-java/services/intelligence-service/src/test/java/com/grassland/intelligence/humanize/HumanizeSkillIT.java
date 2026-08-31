package com.grassland.intelligence.humanize;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 去AI味 skill 端到端（任务书 #61）：启动种子（3 条 MIT、幂等）、治理台鉴权与列表、整行编辑乐观锁、
 * 激活单选切换（含关闭注入与版本冲突）、停用即自动失效的注入联动。
 *
 * <p>
 * 共享容器里种子 3 行与空 {@code humanize_config} 是全套件公共前提，每个用例收尾恢复现场。
 */
class HumanizeSkillIT extends IntelligenceItSupport {

	@Autowired
	private HumanizeSkillRepository skills;

	@Autowired
	private HumanizeConfigRepository config;

	@Autowired
	private HumanizeSkillSeeder seeder;

	@AfterEach
	void clearActivation() {
		db.sql("DELETE FROM humanize_config").then().block();
	}

	// ---------- 启动种子 ----------

	@Test
	@DisplayName("启动种子：3 条 MIT 规则（shuorenhua / lieflat-11 / qu-ai-wei）")
	void startupSeedsThreeSkills() {
		assertThat(skills.count().block()).isEqualTo(3L);
		assertThat(skills.listAll().collectList().block()).extracting(HumanizeSkill::code)
				.containsExactlyInAnyOrder("shuorenhua", "lieflat-11", "qu-ai-wei");
		assertThat(skills.listAll().collectList().block())
				.allSatisfy(skill -> assertThat(skill.sourceLicense()).isEqualTo("MIT"));
	}

	@Test
	@DisplayName("表非空时再跑 Seeder 不重复种（幂等）")
	void reseedIsNoopWhenTableHasRows() {
		seeder.seedOnStartup();
		assertThat(skills.count().block()).isEqualTo(3L);
	}

	// ---------- 治理台鉴权与列表 ----------

	@Test
	@DisplayName("admin 列表：无断言 401；普通用户 403")
	void adminListRequiresAdmin() {
		client().get().uri("/api/admin/humanize-skills").exchange().expectStatus().isUnauthorized();

		client().get().uri("/api/admin/humanize-skills")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isForbidden();
	}

	@Test
	@DisplayName("admin 列表：3 项含 promptContent，未激活时 activeSkillCode 空串、configVersion 0")
	void adminListExposesPromptAndInactiveConfig() {
		client().get().uri("/api/admin/humanize-skills")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString())).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.skills.length()")
				.isEqualTo(3).jsonPath("$.data.skills[0].promptContent").isNotEmpty().jsonPath("$.data.activeSkillCode")
				.isEqualTo("").jsonPath("$.data.configVersion").isEqualTo(0);
	}

	// ---------- 整行编辑 ----------

	@Test
	@DisplayName("admin PUT：整行更新 version+1；旧版本/无此行 → 409；promptContent 超长 → 400")
	void adminUpdateWithOptimisticLock() {
		String id = idOf("shuorenhua");
		RowSnapshot snapshot = snapshotRow(id);
		try {
			client().put().uri("/api/admin/humanize-skills/" + id)
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(updateBody("说人话", "x", "新内容", true, 0))
					.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.skill.version").isEqualTo(1)
					.jsonPath("$.data.skill.promptContent").isEqualTo("新内容");

			client().put().uri("/api/admin/humanize-skills/" + id)
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(updateBody("说人话", "x", "再改", true, 0)).exchange()
					.expectStatus().isEqualTo(409);

			client().put().uri("/api/admin/humanize-skills/" + UUID.randomUUID())
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(updateBody("说人话", "x", "再改", true, 0)).exchange()
					.expectStatus().isEqualTo(409);

			client().put().uri("/api/admin/humanize-skills/" + id)
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(updateBody("说人话", "x", "超".repeat(3001), true, 1)).exchange().expectStatus()
					.isBadRequest();
		} finally {
			restoreRow(id, snapshot);
		}
	}

	// ---------- 激活单选 ----------

	@Test
	@DisplayName("admin 激活：未知 code → 400；激活成功 configVersion=1 且列表可见；旧版本 → 409；null 关闭注入")
	void adminActivateSwitchesSingleSelection() {
		client().put().uri("/api/admin/humanize-skills/active")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(activateBody("bogus", 0)).exchange().expectStatus()
				.isBadRequest();

		client().put().uri("/api/admin/humanize-skills/active")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(activateBody("shuorenhua", 0)).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.activeSkillCode").isEqualTo("shuorenhua")
				.jsonPath("$.data.configVersion").isEqualTo(1);

		client().get().uri("/api/admin/humanize-skills")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString())).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.activeSkillCode").isEqualTo("shuorenhua");

		client().put().uri("/api/admin/humanize-skills/active")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(activateBody("qu-ai-wei", 0)).exchange()
				.expectStatus().isEqualTo(409);

		client().put().uri("/api/admin/humanize-skills/active")
				.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(activateBody(null, 1)).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.activeSkillCode").isEqualTo("");

		assertThat(config.findOrDefault().block().activeSkillCode()).isNull();
	}

	@Test
	@DisplayName("激活的 skill 被停用后注入自动失效（JOIN + enabled 双检）")
	void disabledActiveSkillStopsInjection() {
		String id = idOf("shuorenhua");
		RowSnapshot snapshot = snapshotRow(id);
		try {
			client().put().uri("/api/admin/humanize-skills/active")
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(activateBody("shuorenhua", 0)).exchange()
					.expectStatus().isOk();

			assertThat(skills.findActiveSkill().block()).isNotNull();

			db.sql("UPDATE humanize_skill SET enabled = false WHERE code = 'shuorenhua'").then().block();

			assertThat(skills.findActiveSkill().block()).isNull();
		} finally {
			restoreRow(id, snapshot);
		}
	}

	// ---------- helpers ----------

	private static Map<String, Object> updateBody(String displayName, String description, String promptContent,
			boolean enabled, int expectedVersion) {
		return Map.of("displayName", displayName, "description", description, "promptContent", promptContent, "enabled",
				enabled, "expectedVersion", expectedVersion);
	}

	/** activeSkillCode 允许 null（关闭注入），Map.of 不接受 null 值——用 LinkedHashMap。 */
	private static Map<String, Object> activateBody(String activeSkillCode, long expectedConfigVersion) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("activeSkillCode", activeSkillCode);
		body.put("expectedConfigVersion", expectedConfigVersion);
		return body;
	}

	private String idOf(String code) {
		return skills.findByCode(code).block().id().toString();
	}

	/** 编辑用例会改整行——快照必须覆盖所有可编辑列，残留会污染共享容器的种子前提。 */
	private record RowSnapshot(String displayName, String description, String promptContent) {
	}

	private RowSnapshot snapshotRow(String id) {
		return db.sql("SELECT display_name, description, prompt_content FROM humanize_skill WHERE id = CAST(:id AS uuid)")
				.bind("id", id)
				.map(r -> new RowSnapshot(r.get("display_name", String.class), r.get("description", String.class),
						r.get("prompt_content", String.class)))
				.one().block();
	}

	private void restoreRow(String id, RowSnapshot snapshot) {
		db.sql("UPDATE humanize_skill SET display_name = :n, description = :d, prompt_content = :p, "
				+ "enabled = true, version = 0, updated_by = NULL, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("n", snapshot.displayName()).bind("d", snapshot.description())
				.bind("p", snapshot.promptContent()).bind("id", id).then().block();
	}
}
