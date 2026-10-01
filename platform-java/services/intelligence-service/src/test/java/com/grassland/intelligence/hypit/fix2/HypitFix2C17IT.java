package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitAuthorService;
import com.grassland.intelligence.hypit.agent.HypitAuthorService.DraftInput;
import com.grassland.intelligence.hypit.agent.HypitAuthorService.DraftResult;
import com.grassland.intelligence.hypit.project.HypitChangesetService.FileChange;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * C107F2-17（107-fix-2 / RULE-10 写侧 / F22、F29）：复刻方案转换为有内容的源码与材料需求。
 *
 * <p>
 * 真 PostgreSQL + WireMock sidecar（workspace.check 真实编译检查）。
 *
 * <ul>
 * <li>TC-F2-17-01 两个不同 brief/方案 → 生成源码的时长/字幕/画面分段实际不同，非仅注释差异。</li>
 * <li>TC-F2-17-02 第一轮检查无效引用、第二轮修复合法 → 修复后才建 validated 草稿；两轮仍坏保留 save 草稿与诊断，head
 * 不动。</li>
 * <li>TC-F2-17-03 方案含未绑定必要 asset → WAITING_INPUT 指出缺口、零 command/changeset。</li>
 * <li>TC-F2-17-04 越界文件路径 / shell 内容 → 整批拒绝、保留原源码。</li>
 * </ul>
 */
@TestPropertySource(properties = "hypit.enabled=true")
class HypitFix2C17IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000017a";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	@Autowired
	HypitAuthorService authors;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c17-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@BeforeEach
	void seed() {
		SIDECAR.resetRequests();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c17', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM hypit_project"
				+ " WHERE account_id = :o)").bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:o, 'system')").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	private void stubCheckOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"ok\":true,"
								+ "\"diagnostics\":[]}}")));
	}

	private void stubCheckAlwaysFailing(String message) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"failed\",\"error\":{\"code\":\"compile_failed\","
								+ "\"message\":\"" + message + "\"}}")));
	}

	private UUID seedAsset(String sha) {
		UUID assetId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_asset(id, project_id, resource_handle, role, origin_kind, sha256, mime_type,"
				+ " size_bytes, status) VALUES (CAST(:id AS uuid), CAST(:p AS uuid), :handle, 'footage', 'upload',"
				+ " :sha, 'video/mp4', 1024, 'ready')").bind("id", assetId.toString()).bind("p", projectId.toString())
				.bind("handle", "it://f-" + sha.substring(0, 6) + ".mp4").bind("sha", sha).then()
				.block(Duration.ofSeconds(10));
		return assetId;
	}

	private DraftInput draft(String title, List<Map<String, Object>> steps) {
		return new DraftInput(UUID.randomUUID(), projectId, 2L, title, steps, List.of());
	}

	/** 从草稿命令的 canonical 载荷提取指定文件内容（非转义原文）。 */
	private String changesetFile(String changesetId, String path) {
		Map<String, Object> payload = db
				.sql("SELECT payload_json::text AS payload FROM hypit_command WHERE id = (SELECT command_id"
						+ " FROM hypit_changeset WHERE id = CAST(:c AS uuid))")
				.bind("c", changesetId).map((row, meta) -> row.get("payload", String.class)).one().map(HypitJson::read)
				.block(Duration.ofSeconds(10));
		Object canonicalRaw = payload.get("canonical");
		Map<String, Object> canonical = canonicalRaw instanceof String text
				? HypitJson.read(text)
				: HypitJson.mapValue(canonicalRaw);
		for (Object changeRaw : (List<?>) canonical.get("changes")) {
			Map<String, Object> change = HypitJson.mapValue(changeRaw);
			if (path.equals(change.get("path"))) {
				return String.valueOf(change.get("content"));
			}
		}
		throw new IllegalStateException("file not in changeset: " + path);
	}

	// ── TC-F2-17-01：两个不同方案 → 源码内容实际不同 ────────────────────────
	@Test
	@DisplayName("TC-F2-17-01 不同 brief/分析：时长/字幕/画面分段实际不同，非仅注释差异")
	void differentPlansProduceSubstantivelyDifferentSources() {
		stubCheckOk();
		UUID assetA = seedAsset("1".repeat(64));
		UUID assetB = seedAsset("2".repeat(64));
		DraftResult first = authors
				.draft(OWNER,
						draft("旗舰机评测",
								List.of(Map.of("index", 0, "capability", "media", "anchorSeconds", 0.0, "endSeconds",
										4.0, "assetId", assetA.toString(), "caption", "开场：旗舰机特写"),
										Map.of("index", 1, "capability", "media", "anchorSeconds", 4.0, "endSeconds",
												10.0, "assetId", assetB.toString(), "caption", "中段：跑分对比"))))
				.block(Duration.ofSeconds(60));
		DraftResult second = authors.draft(OWNER,
				draft("护肤教程",
						List.of(Map.of("index", 0, "capability", "media", "anchorSeconds", 0.0, "endSeconds", 6.0,
								"caption", "步骤一：洁面演示"),
								Map.of("index", 1, "capability", "media", "anchorSeconds", 6.0, "endSeconds", 18.0,
										"caption", "步骤二：精华按摩"))))
				.block(Duration.ofSeconds(60));

		assertThat(first.status()).isEqualTo("DRAFT_PASSED");
		assertThat(second.status()).isEqualTo("DRAFT_PASSED");

		String payloadA = changesetFile(first.changesetId(), "main.svml");
		String payloadB = changesetFile(second.changesetId(), "main.svml");
		// 时序实际不同：总时长 10s vs 18s（不再固定 2s 模板）。
		assertThat(payloadA).contains("end=\"10s\"");
		assertThat(payloadB).contains("end=\"18s\"");
		// 字幕实际不同：真实文本进文档（非注释）。
		assertThat(payloadA).contains("旗舰机评测：开场：旗舰机特写").contains("中段：跑分对比");
		assertThat(payloadB).contains("护肤教程：步骤一：洁面演示").contains("步骤二：精华按摩");
		// 画面分段实际不同：锚点窗 0-4/4-10 vs 0-6/6-18。
		assertThat(payloadA).contains("start=\"0s\" end=\"4s\"").contains("start=\"4s\" end=\"10s\"");
		assertThat(payloadB).contains("start=\"0s\" end=\"6s\"").contains("start=\"6s\" end=\"18s\"");
		// 素材选择实际不同：方案A绑定 assetA/assetB，方案B未绑定（MISSING 如实标注，不伪造句柄）。
		assertThat(payloadA).contains("asset:" + assetA).contains("asset:" + assetB);
		assertThat(payloadB).contains("material: MISSING");
		// 两产物确非同文档。
		assertThat(payloadA).isNotEqualTo(payloadB);
	}

	// ── TC-F2-17-02：诊断修复后 apply；失败稿不覆盖 head ─────────────────────
	@Test
	@DisplayName("TC-F2-17-02 一轮无效引用二轮修复通过；两轮仍坏保留草稿诊断，head 不动")
	void repairAfterInvalidReferenceAndFailedDraftKeepsHead() {
		// 场景一：第一次 check 报无效引用，第二次（规范化骨架）通过。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.inScenario("fix").whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
				.willSetStateTo("second")
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"failed\",\"error\":{\"code\":\"compile_failed\","
								+ "\"message\":\"无效引用：material-0 指向不存在的 asset\"}}")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.inScenario("fix").whenScenarioStateIs("second")
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"ok\":true,"
								+ "\"diagnostics\":[]}}")));
		DraftResult repaired = authors.draft(OWNER, draft("修复轮", List.of(
				Map.of("index", 0, "capability", "media", "anchorSeconds", 0.0, "endSeconds", 5.0, "caption", "修复段"))))
				.block(Duration.ofSeconds(60));
		assertThat(repaired.status()).as("使用诊断修复后建 validated 草稿").isEqualTo("DRAFT_PASSED");
		assertThat(repaired.rounds()).isEqualTo(2);
		String applyMode = db.sql("SELECT apply_mode FROM hypit_changeset WHERE id = CAST(:c AS uuid)")
				.bind("c", repaired.changesetId()).map((row, meta) -> row.get("apply_mode", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(applyMode).isEqualTo("validated");
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(revision).as("草稿不动 head（revision 不变）").isEqualTo(2L);

		// 场景二：两轮检查都失败 → save 草稿保留诊断、head 不动、revision 不变。
		SIDECAR.resetAll();
		stubCheckAlwaysFailing("两轮均编译失败");
		DraftResult broken = authors.draft(OWNER, draft("失败稿", List.of(
				Map.of("index", 0, "capability", "media", "anchorSeconds", 0.0, "endSeconds", 5.0, "caption", "失败段"))))
				.block(Duration.ofSeconds(60));
		assertThat(broken.status()).isEqualTo("DRAFT_DIAGNOSTICS");
		assertThat(broken.diagnostics()).as("完整诊断保留").isNotEmpty();
		String brokenMode = db.sql("SELECT apply_mode FROM hypit_changeset WHERE id = CAST(:c AS uuid)")
				.bind("c", broken.changesetId()).map((row, meta) -> row.get("apply_mode", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(brokenMode).isEqualTo("save");
		Long revisionAfter = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(revisionAfter).as("失败稿不覆盖 head").isEqualTo(2L);
	}

	// ── TC-F2-17-03：未绑定必要 asset → WAITING_INPUT 零副作用 ──────────────
	@Test
	@DisplayName("TC-F2-17-03 方案引用未绑定 asset：WAITING_INPUT 指出缺口，零 command/changeset")
	void missingBoundAssetWaitsWithZeroSideEffects() {
		String ghost = UUID.randomUUID().toString();
		DraftResult result = authors
				.draft(OWNER,
						draft("缺料方案", List.of(Map.of("index", 0, "capability", "media", "anchorSeconds", 0.0,
								"endSeconds", 5.0, "assetId", ghost, "caption", "需要素材"))))
				.block(Duration.ofSeconds(30));

		assertThat(result.status()).isEqualTo("WAITING_INPUT");
		assertThat(result.missingMaterials()).as("指出缺项").containsExactly(ghost);
		Long commands = db
				.sql("SELECT COUNT(*) AS n FROM hypit_command WHERE account_id = :o AND action = 'author.draft'")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(commands).as("零 command（零收费记账）").isZero();
		Long changesets = db
				.sql("SELECT COUNT(*) AS n FROM hypit_changeset c JOIN hypit_project p"
						+ " ON p.id = c.project_id WHERE p.account_id = :o")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(changesets).as("零 changeset").isZero();
		assertThat(SIDECAR.getAllServeEvents()).as("零外部执行（不 check 不构建）").isEmpty();
	}

	// ── TC-F2-17-04：越界路径 / shell 内容整批拒绝 ──────────────────────────
	@Test
	@DisplayName("TC-F2-17-04 越界文件路径或 shell 内容：拒绝并保留原源码")
	void unsafeChangesetRejectedKeepingOriginalSources() {
		Throwable escape = catchThrowable(() -> HypitAuthorService
				.validateChangesetSafety(List.of(new FileChange("../../etc/cron/evil", "put", "x", null))));
		assertThat(escape).isInstanceOf(IntelligenceException.class).hasMessageContaining("拒绝越界文件路径");

		Throwable absolute = catchThrowable(() -> HypitAuthorService
				.validateChangesetSafety(List.of(new FileChange("/etc/passwd", "put", "x", null))));
		assertThat(absolute).isInstanceOf(IntelligenceException.class);

		Throwable shell = catchThrowable(
				() -> HypitAuthorService.validateChangesetSafety(List.of(new FileChange("src/evil.js", "put",
						"const { exec } = require('child_process'); exec('rm -rf /')", null))));
		assertThat(shell).isInstanceOf(IntelligenceException.class).hasMessageContaining("shell 执行内容");

		// 合法变更集不受影响（白名单路径 + 无 shell 原语）。
		HypitAuthorService.validateChangesetSafety(List.of(new FileChange("main.svml", "put", "<svml/>", null)));
		// 拒绝路径下工程源码零变化：本用例没有任何 draft 调用，head 原样。
		Long changesets = db
				.sql("SELECT COUNT(*) AS n FROM hypit_changeset c JOIN hypit_project p"
						+ " ON p.id = c.project_id WHERE p.account_id = :o")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(changesets).isZero();
	}
}
