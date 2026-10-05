package com.grassland.intelligence.humanize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * 任务书 #108 C-01（W07/V93）：条件种子升级——仅完整基线匹配行升级一次，自定义/改描述/disabled/部分种子
 * 场景原样或正确联动；补偿回滚不覆盖管理员后续编辑；迁移与种子同源逐项比对。
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

	// ---------- 任务书 #108 C-01（W07/V93）：条件种子升级与回滚（TC-C01-003）----------

	private static final ObjectMapper V93_MAPPER = new ObjectMapper();
	private static final String V93_RESOURCE = "/db/migration/V93__humanize_conditional_refresh.sql";

	/** V93 语句内嵌的基线旧文本与新版文本（code → 旧/新 prompt+description）。 */
	private record SkillTexts(String oldPrompt, String oldDesc, String newPrompt, String newDesc) {
	}

	private static String readClasspath(String resource) {
		try (InputStream in = HumanizeSkillIT.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing classpath resource " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + resource, e);
		}
	}

	/** strip 注释行 → 按分号拆分 → 过滤空白段（R2DBC 单语句执行；语句正文内无分号）。 */
	private static List<String> statementsOf(String sql) {
		List<String> statements = new ArrayList<>();
		for (String raw : sql.split(";")) {
			StringBuilder kept = new StringBuilder();
			for (String line : raw.split("\n")) {
				if (line.strip().startsWith("--")) {
					continue;
				}
				kept.append(line).append('\n');
			}
			if (!kept.toString().isBlank()) {
				statements.add(kept.toString());
			}
		}
		return statements;
	}

	private void execute(List<String> statements) {
		for (String statement : statements) {
			db.sql(statement).then().block();
		}
	}

	/**
	 * 从 V93 文件解析 code → (旧 prompt/旧 desc/新 prompt/新 desc)： SET 段的 prompt_content
	 * 在第一个 $doc$…$doc$、description 在第一个单引号串； WHERE 段的 prompt_content/description
	 * 在其后（文本本身不含 $ 与单引号）。
	 */
	private static Map<String, SkillTexts> parseV93(String sql) {
		Pattern code = Pattern.compile("WHERE code = '([a-z0-9-]+)'");
		Pattern dollar = Pattern.compile("\\$doc\\$(.*?)\\$doc\\$", Pattern.DOTALL);
		Pattern desc = Pattern.compile("description = '([^']*)'");
		Map<String, SkillTexts> result = new LinkedHashMap<>();
		for (String statement : statementsOf(sql)) {
			if (!statement.contains("UPDATE humanize_skill")) {
				continue;
			}
			Matcher codeMatcher = code.matcher(statement);
			assertThat(codeMatcher.find()).as("V93 语句缺 code").isTrue();
			List<String> dollars = new ArrayList<>();
			Matcher dollarMatcher = dollar.matcher(statement);
			while (dollarMatcher.find()) {
				dollars.add(dollarMatcher.group(1));
			}
			List<String> descs = new ArrayList<>();
			Matcher descMatcher = desc.matcher(statement);
			while (descMatcher.find()) {
				descs.add(descMatcher.group(1));
			}
			assertThat(dollars).as("V93 语句应含新/旧 prompt 各一段").hasSize(2);
			assertThat(descs).as("V93 语句应含新/旧 description 各一段").hasSize(2);
			result.put(codeMatcher.group(1),
					new SkillTexts(dollars.get(1), descs.get(1), dollars.get(0), descs.get(0)));
		}
		assertThat(result).as("V93 应包含三项规则升级").containsKeys("shuorenhua", "lieflat-11", "qu-ai-wei");
		return result;
	}

	private static JsonNode contractSkill(String code) {
		try {
			JsonNode root = V93_MAPPER
					.readTree(readClasspath("/contracts/humanize-skills.json").getBytes(StandardCharsets.UTF_8));
			for (JsonNode skill : root.path("skills")) {
				if (code.equals(skill.path("code").asText())) {
					return skill;
				}
			}
			throw new IllegalStateException("contracts/humanize-skills.json 缺 " + code);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot parse contracts/humanize-skills.json", e);
		}
	}

	private record SkillRow(String prompt, String description, int version, boolean enabled, UUID updatedBy) {
	}

	private SkillRow row(String code) {
		return db
				.sql("SELECT prompt_content, description, version, enabled, updated_by FROM humanize_skill "
						+ "WHERE code = :code")
				.bind("code", code)
				.map(r -> new SkillRow(r.get("prompt_content", String.class), r.get("description", String.class),
						r.get("version", Integer.class), Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
						r.get("updated_by", UUID.class)))
				.one().block();
	}

	private void insertSkill(String code, String description, String prompt, boolean enabled, int version,
			UUID updatedBy) {
		db.sql("INSERT INTO humanize_skill (code, display_name, description, prompt_content, source_repo, "
				+ "source_license, enabled, version, updated_by) VALUES (:code, :displayName, :description, :prompt, "
				+ ":sourceRepo, 'MIT', :enabled, :version, :updatedBy)").bind("code", code).bind("displayName", code)
				.bind("description", description).bind("prompt", prompt)
				.bind("sourceRepo", contractSkill(code).path("sourceRepo").asText()).bind("enabled", enabled)
				.bind("version", version)
				.bind("updatedBy", com.grassland.intelligence.config.R2dbcBindings.nullable(updatedBy, UUID.class))
				.then().block();
	}

	private void activate(String code) {
		db.sql("INSERT INTO humanize_config (id, active_skill_code) VALUES (1, :code) ON CONFLICT (id) DO UPDATE SET "
				+ "active_skill_code = EXCLUDED.active_skill_code, version = humanize_config.version + 1")
				.bind("code", code).then().block();
	}

	/** 恢复共享容器公共前提：清空两表后重跑 Seeder（新库种子路径 → 新版文本 3 行）。 */
	private void restoreSeededBaseline() {
		db.sql("DELETE FROM humanize_config").then().block();
		db.sql("DELETE FROM humanize_skill").then().block();
		seeder.seedOnStartup();
		assertThat(skills.count().block()).isEqualTo(3L);
	}

	@Test
	@DisplayName("TC-C01-003：V93 仅升级完整基线行（disabled 行也升级且 enabled 不变），自定义/改描述行原样，激活不变，幂等仅一次")
	void v93ConditionalUpgradeOnlyTouchesUntouchedBaselineRows() {
		Map<String, SkillTexts> texts = parseV93(readClasspath(V93_RESOURCE));
		try {
			db.sql("DELETE FROM humanize_config").then().block();
			db.sql("DELETE FROM humanize_skill").then().block();
			// 基线完整行（enabled=false：内容匹配仍升级，enabled 不变）+ 管理员自定义 prompt 行 + 改过描述行
			insertSkill("shuorenhua", texts.get("shuorenhua").oldDesc(), texts.get("shuorenhua").oldPrompt(), false, 0,
					null);
			insertSkill("lieflat-11", texts.get("lieflat-11").oldDesc(), "管理员自定义规则内容", true, 1, UUID.randomUUID());
			insertSkill("qu-ai-wei", "管理员改过的描述", texts.get("qu-ai-wei").oldPrompt(), true, 0, null);
			activate("shuorenhua");

			execute(statementsOf(readClasspath(V93_RESOURCE)));

			SkillRow upgraded = row("shuorenhua");
			// 迁移与种子同源：升级后的 DB 文本 == contracts/humanize-skills.json 当前文本
			assertThat(upgraded.prompt()).isEqualTo(texts.get("shuorenhua").newPrompt())
					.isEqualTo(contractSkill("shuorenhua").path("promptContent").asText());
			assertThat(upgraded.description()).isEqualTo(texts.get("shuorenhua").newDesc())
					.isEqualTo(contractSkill("shuorenhua").path("description").asText());
			assertThat(upgraded.version()).isEqualTo(1);
			assertThat(upgraded.enabled()).as("enabled 不因升级翻转").isFalse();
			assertThat(upgraded.updatedBy()).isNull();

			assertThat(row("lieflat-11").prompt()).isEqualTo("管理员自定义规则内容");
			assertThat(row("lieflat-11").version()).isEqualTo(1);
			assertThat(row("qu-ai-wei").description()).isEqualTo("管理员改过的描述");
			assertThat(row("qu-ai-wei").version()).isEqualTo(0);

			// 激活配置不变；enabled=false 的激活项注入联动失效语义保持（JOIN + enabled 双检）
			assertThat(config.findOrDefault().block().activeSkillCode()).isEqualTo("shuorenhua");
			assertThat(skills.findActiveSkill().block()).isNull();

			// 重放迁移：已升级/自定义/改描述行都不再匹配 → 全库无变化（仅更新一次）
			execute(statementsOf(readClasspath(V93_RESOURCE)));
			assertThat(row("shuorenhua").version()).isEqualTo(1);
			assertThat(row("lieflat-11").version()).isEqualTo(1);
			assertThat(row("qu-ai-wei").version()).isEqualTo(0);
		} finally {
			restoreSeededBaseline();
		}
	}

	@Test
	@DisplayName("TC-C01-003：部分种子库仅升级存在的基线行，缺失行不受影响，激活项升级后注入生效")
	void v93PartialSeedLibraryUpgradesOnlyPresentRows() {
		Map<String, SkillTexts> texts = parseV93(readClasspath(V93_RESOURCE));
		try {
			db.sql("DELETE FROM humanize_config").then().block();
			db.sql("DELETE FROM humanize_skill").then().block();
			insertSkill("shuorenhua", texts.get("shuorenhua").oldDesc(), texts.get("shuorenhua").oldPrompt(), true, 0,
					null);
			insertSkill("lieflat-11", texts.get("lieflat-11").oldDesc(), texts.get("lieflat-11").oldPrompt(), true, 0,
					null);
			activate("shuorenhua");

			execute(statementsOf(readClasspath(V93_RESOURCE)));

			assertThat(row("shuorenhua").version()).isEqualTo(1);
			assertThat(row("shuorenhua").prompt())
					.isEqualTo(contractSkill("shuorenhua").path("promptContent").asText());
			assertThat(row("lieflat-11").version()).isEqualTo(1);
			assertThat(skills.findByCode("qu-ai-wei").block()).isNull();
			// 激活的 enabled 基线行升级后注入直接读到新版
			assertThat(skills.findActiveSkill().block().promptContent())
					.isEqualTo(contractSkill("shuorenhua").path("promptContent").asText());
		} finally {
			restoreSeededBaseline();
		}
	}

	@Test
	@DisplayName("TC-C01-003：补偿回滚恢复旧文本且版本继续递增；不覆盖管理员后续编辑")
	void v93RollbackRestoresBaselineAndSkipsAdminEditedRows() {
		Map<String, SkillTexts> texts = parseV93(readClasspath(V93_RESOURCE));
		SkillTexts shuorenhua = texts.get("shuorenhua");
		// 与 test-artifacts/task-108/rollback-v93.sql（W49 证据）同源语义：精确匹配新版才恢复旧文本
		String rollback = """
				UPDATE humanize_skill
				SET prompt_content = $doc$%s$doc$, description = '%s', version = version + 1, updated_at = now()
				WHERE code = 'shuorenhua' AND updated_by IS NULL
				  AND prompt_content = $doc$%s$doc$ AND description = '%s'
				  AND source_repo = '%s' AND source_license = 'MIT'
				""".formatted(shuorenhua.oldPrompt(), shuorenhua.oldDesc(), shuorenhua.newPrompt(),
				shuorenhua.newDesc(), contractSkill("shuorenhua").path("sourceRepo").asText());
		try {
			db.sql("DELETE FROM humanize_config").then().block();
			db.sql("DELETE FROM humanize_skill").then().block();
			insertSkill("shuorenhua", shuorenhua.oldDesc(), shuorenhua.oldPrompt(), true, 0, null);
			execute(statementsOf(readClasspath(V93_RESOURCE)));
			assertThat(row("shuorenhua").version()).isEqualTo(1);

			// 管理员在升级后继续编辑（version=2、内容偏离新版）→ 回滚精确匹配新版，0 行命中不覆盖
			db.sql("UPDATE humanize_skill SET prompt_content = '管理员后来改的内容', version = version + 1, "
					+ "updated_by = :updatedBy WHERE code = 'shuorenhua'").bind("updatedBy", UUID.randomUUID()).then()
					.block();
			execute(List.of(rollback));
			assertThat(row("shuorenhua").prompt()).isEqualTo("管理员后来改的内容");
			assertThat(row("shuorenhua").version()).isEqualTo(2);

			// 未被再次编辑的 V93 升级行（version=1、新版文本）→ 回滚恢复旧文本且 version 继续递增（不降版本）
			db.sql("UPDATE humanize_skill SET prompt_content = :prompt, description = :description, version = 1, "
					+ "updated_by = NULL WHERE code = 'shuorenhua'").bind("prompt", shuorenhua.newPrompt())
					.bind("description", shuorenhua.newDesc()).then().block();
			execute(List.of(rollback));
			assertThat(row("shuorenhua").prompt()).isEqualTo(shuorenhua.oldPrompt());
			assertThat(row("shuorenhua").description()).isEqualTo(shuorenhua.oldDesc());
			assertThat(row("shuorenhua").version()).isEqualTo(2);
		} finally {
			restoreSeededBaseline();
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
		return db.sql(
				"SELECT display_name, description, prompt_content FROM humanize_skill WHERE id = CAST(:id AS uuid)")
				.bind("id", id).map(r -> new RowSnapshot(r.get("display_name", String.class),
						r.get("description", String.class), r.get("prompt_content", String.class)))
				.one().block();
	}

	private void restoreRow(String id, RowSnapshot snapshot) {
		db.sql("UPDATE humanize_skill SET display_name = :n, description = :d, prompt_content = :p, "
				+ "enabled = true, version = 0, updated_by = NULL, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("n", snapshot.displayName()).bind("d", snapshot.description()).bind("p", snapshot.promptContent())
				.bind("id", id).then().block();
	}
}
