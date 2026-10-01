package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.template.HypitProjectPackageService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

/**
 * C107F2-29（F31 / §6.13、§7.4）：导入 PG 登记、owner 绑定与跨服务幂等收敛。 真 PostgreSQL +
 * WireMock sidecar 桩（manifest/hash 门禁与字节保真是 B 侧真值， 由 fix2-c28/workspace 组覆盖；本卡锁
 * Java 编排面）。
 *
 * <ul>
 * <li>TC-F2-29-01 owner A 导出、owner B 上传：新工程 owner=B、mode=import、 ready +
 * revision/selected_run 落定、revision 行存在；A 查无此工程（权限不继承）。</li>
 * <li>TC-F2-29-02 同 requestId 同包并发导入：恰一个 project/job/revision 与一次 sidecar
 * 解包（broker CommandStore 幂等回执）。</li>
 * <li>TC-F2-29-03 崩溃在 broker 发布后/PG 收敛前（命令在、resultJson 空）：重放 复用保留 projectId 重发同
 * commandId——登记同 project，不导入第二份、不漏 owner。</li>
 * <li>TC-F2-29-04 同 requestId 换包 hash：409 hypit_idempotency_conflict，原工程与
 * 回执不变；失败导入收敛 provisioning_failed 重新查询可见。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C29IT extends IntelligenceItSupport {

	private static final String OWNER_A = "ffffffff-0000-4000-8000-00000000290a";
	private static final String OWNER_B = "ffffffff-0000-4000-8000-00000000290b";
	private static final String MANIFEST_HASH = "a".repeat(64);
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	HypitProjectPackageService packages;

	@Autowired
	DatabaseClient db;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		for (String owner : List.of(OWNER_A, OWNER_B)) {
			cleanOwner(owner);
		}
	}

	private void cleanOwner(String owner) {
		db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
				+ " WHERE account_id = :o)").bind("o", owner).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", owner).then())
				.block(Duration.ofSeconds(20));
	}

	private void stubImportSuccess(long revision, long fileCount) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.import"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"project-package.import","state":"succeeded","result":{
						  "projectId":"broker-side","revision":%d,"manifestHash":"%s",
						  "fileCount":%d,"selectedRun":"main.svrun","artifactRoot":"project-exports/x/rev-1"}}
						""".formatted(revision, MANIFEST_HASH, fileCount))));
	}

	private long count(String sql, String owner) {
		Long n = db.sql(sql).bind("o", owner).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return n == null ? -1 : n;
	}

	private String projectStatus(String owner) {
		String status = db.sql("SELECT status FROM hypit_project WHERE account_id = :o").bind("o", owner)
				.map((row, meta) -> row.get("status", String.class)).one().block(Duration.ofSeconds(10));
		return status == null ? "" : status;
	}

	// ── TC-F2-29-01：owner B 导入 A 的包；权限不继承 ─────────────────────────────
	@Test
	@DisplayName("TC-F2-29-01 owner B 导入：B 拥有 ready 新工程，A 查无此工程")
	void ownerBOwnsImportedProjectAndANoAccess() {
		stubImportSuccess(2L, 7L);
		Map<String, Object> body = packages
				.import_(OWNER_B, UUID.randomUUID(), "project-exports/x/rev-1", "B 的导入", "b".repeat(64))
				.block(Duration.ofSeconds(20));
		assertThat(body).isNotNull();
		UUID projectId = UUID.fromString(String.valueOf(body.get("projectId")));
		assertThat(body.get("revision")).isEqualTo(2L);
		assertThat(body.get("fileCount")).isEqualTo(7L);
		assertThat(body.get("selectedRun")).isEqualTo("main.svrun");

		// PG 登记：owner=B、mode=import、ready + revision/head/selected_run 落定。
		var row = db
				.sql("SELECT account_id, mode, status, revision, selected_run, head_manifest_hash"
						+ " FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString())
				.map((r, meta) -> Map.of("account", r.get("account_id", String.class), "mode",
						r.get("mode", String.class), "status", r.get("status", String.class), "revision",
						r.get("revision", Long.class), "run", r.get("selected_run", String.class), "head",
						r.get("head_manifest_hash", String.class)))
				.one().block(Duration.ofSeconds(10));
		assertThat(row).isNotNull();
		assertThat(row.get("account")).isEqualTo(OWNER_B);
		assertThat(row.get("mode")).isEqualTo("import");
		assertThat(row.get("status")).isEqualTo("ready");
		assertThat(row.get("revision")).isEqualTo(2L);
		assertThat(row.get("run")).isEqualTo("main.svrun");
		assertThat(row.get("head")).isEqualTo(MANIFEST_HASH);

		// revision 行与 job/command 终态。
		Long revisions = db.sql("SELECT count(*) AS n FROM hypit_revision WHERE project_id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((r, meta) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(revisions).isEqualTo(1L);
		String jobState = db.sql("SELECT state FROM hypit_job WHERE project_id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((r, meta) -> r.get("state", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(jobState).isEqualTo("succeeded");
		String commandResult = db
				.sql("SELECT result_json::text AS r FROM hypit_command"
						+ " WHERE account_id = :o AND action = 'project.import'")
				.bind("o", OWNER_B).map((r, meta) -> r.get("r", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(commandResult).contains(projectId.toString()).contains("\"succeeded\"");

		// 权限不继承：A 视角查无此工程（findOwner 空语义 = 404）。
		Long ownedByA = db
				.sql("SELECT count(*) AS n FROM hypit_project WHERE id = CAST(:id AS uuid)" + " AND account_id = :o")
				.bind("id", projectId.toString()).bind("o", OWNER_A).map((r, meta) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(ownedByA).isZero();
	}

	// ── TC-F2-29-02：同 requestId 同包并发导入恰一份 ─────────────────────────────
	@Test
	@DisplayName("TC-F2-29-02 同 requestId 同包并发：一个 project/job/revision、一次解包")
	void concurrentSameRequestSamePackageImportsOnce() {
		stubImportSuccess(2L, 5L);
		UUID requestId = UUID.randomUUID();
		List<Map<String, Object>> results = Mono
				.zip(packages.import_(OWNER_B, requestId, "project-exports/same/rev-1", "并发", "c".repeat(64)),
						packages.import_(OWNER_B, requestId, "project-exports/same/rev-1", "并发", "c".repeat(64)))
				.map(tuple -> List.of(tuple.getT1(), tuple.getT2())).block(Duration.ofSeconds(30));
		assertThat(results).hasSize(2);
		String firstId = String.valueOf(results.get(0).get("projectId"));
		assertThat(firstId).isEqualTo(String.valueOf(results.get(1).get("projectId")));

		assertThat(count("SELECT count(*) AS n FROM hypit_project WHERE account_id = :o", OWNER_B))
				.as("exactly one project row").isEqualTo(1L);
		assertThat(count("SELECT count(*) AS n FROM hypit_job WHERE account_id = :o", OWNER_B))
				.as("exactly one job row").isEqualTo(1L);
		Long revisions = db
				.sql("SELECT count(*) AS n FROM hypit_revision r JOIN hypit_project p"
						+ " ON p.id = r.project_id WHERE p.account_id = :o")
				.bind("o", OWNER_B).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(revisions).as("exactly one revision row (idempotent insert)").isEqualTo(1L);
		// 并发重放路径可能对 broker 双发同 commandId 命令——broker CommandStore 幂等回执
		// 收敛到同一份解包；本卡断言落在 PG 单副本事实（project/job/revision 恰一）。
	}

	// ── TC-F2-29-03：broker 发布后 Java 崩溃 → 重放完成登记，不导入第二份 ────────
	@Test
	@DisplayName("TC-F2-29-03 崩溃重放：复用保留 projectId，登记同一工程不重复")
	void crashReplayCompletesRegistrationWithoutSecondImport() {
		// 模拟崩溃窗口：保留事务已提交（command 带保留 ids + project provisioning + job queued），
		// resultJson 尚空。直接造这三行而非驱动半截流程（broker 发布=sidecar 桩待命）。
		UUID requestId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		// canonical 与服务端同序同形（LinkedHashMap artifactRoot→packageSha256，紧凑 JSON），
		// payload_hash 必须等于同 requestId 重放请求的 canonical hash，否则 409 而非重放。
		String canonical = "{\"artifactRoot\":\"project-exports/x/rev-1\",\"packageSha256\":\"" + "d".repeat(64)
				+ "\"}";
		String canonicalHash;
		try {
			canonicalHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException error) {
			throw new IllegalStateException(error);
		}
		String payload = ("{\"artifactRoot\":\"project-exports/x/rev-1\",\"packageSha256\":\"" + "d".repeat(64)
				+ "\",\"projectId\":\"" + projectId + "\",\"jobId\":\"" + jobId + "\",\"title\":\"导入工程\"}");
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (gen_random_uuid(), :o, NULL, 'import',"
				+ " 'project.import', CAST(:r AS uuid), :h, CAST(:p AS jsonb), 'queued')").bind("o", OWNER_B)
				.bind("r", requestId.toString()).bind("h", canonicalHash).bind("p", payload).then()
				.block(Duration.ofSeconds(10));
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :o, gen_random_uuid(), '导入工程', 'import', 'provisioning', 0)")
				.bind("id", projectId.toString()).bind("o", OWNER_B).then().block(Duration.ofSeconds(10));
		db.sql("INSERT INTO hypit_job(id, command_id, project_id, account_id, kind, state, step_index, attempt,"
				+ " version) SELECT c.id, CAST(:j AS uuid), CAST(:p AS uuid), :o, 'project.import', 'queued', 0, 1, 1"
				+ " FROM hypit_command c WHERE c.account_id = :o AND c.action = 'project.import'"
				+ " AND c.request_id = CAST(:r AS uuid)").bind("j", jobId.toString()).bind("p", projectId.toString())
				.bind("o", OWNER_B).bind("r", requestId.toString()).then().block(Duration.ofSeconds(10));

		stubImportSuccess(2L, 9L);
		Map<String, Object> replayed = packages
				.import_(OWNER_B, requestId, "project-exports/x/rev-1", null, "d".repeat(64))
				.block(Duration.ofSeconds(20));
		assertThat(replayed).isNotNull();
		assertThat(String.valueOf(replayed.get("projectId"))).isEqualTo(projectId.toString());

		// 登记同一工程：ready、同一 revision 行、job 收敛，无第二工程/第二份解包。
		assertThat(projectStatus(OWNER_B)).isEqualTo("ready");
		assertThat(count("SELECT count(*) AS n FROM hypit_project WHERE account_id = :o", OWNER_B)).isEqualTo(1L);
		Long revisions = db.sql("SELECT count(*) AS n FROM hypit_revision WHERE project_id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(revisions).isEqualTo(1L);
		SIDECAR.verify(1,
				com.github.tomakehurst.wiremock.client.WireMock
						.postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
						.withRequestBody(containing("project-package.import")));
		// 重发命令携带保留 projectId（broker 不自行随机生成第二 id）。
		var requests = SIDECAR.findAll(com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.import")));
		assertThat(requests).hasSize(1);
		assertThat(requests.get(0).getBodyAsString()).contains(projectId.toString()).contains("newProjectId");
		// owner 不漏：project 行 owner=B（建行时已绑）。
		Long ownedByB = db
				.sql("SELECT count(*) AS n FROM hypit_project WHERE id = CAST(:id AS uuid)" + " AND account_id = :o")
				.bind("id", projectId.toString()).bind("o", OWNER_B).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(ownedByB).isEqualTo(1L);
	}

	// ── TC-F2-29-04：同 requestId 换包 409；失败导入 provisioning_failed 可见 ────
	@Test
	@DisplayName("TC-F2-29-04 重用 requestId 异包 409 原状不变；失败收敛 provisioning_failed")
	void hashConflictAndFailedImportVisibility() {
		stubImportSuccess(2L, 3L);
		UUID requestId = UUID.randomUUID();
		Map<String, Object> first = packages
				.import_(OWNER_B, requestId, "project-exports/one/rev-1", "一", "1".repeat(64))
				.block(Duration.ofSeconds(20));
		assertThat(first).isNotNull();
		String commandResultBefore = db
				.sql("SELECT result_json::text AS r FROM hypit_command"
						+ " WHERE account_id = :o AND action = 'project.import'")
				.bind("o", OWNER_B).map((row, meta) -> row.get("r", String.class)).one().block(Duration.ofSeconds(10));

		// 同 requestId、不同包 hash → 409，原工程与回执不变。
		try {
			packages.import_(OWNER_B, requestId, "project-exports/two/rev-1", "二", "2".repeat(64))
					.block(Duration.ofSeconds(20));
			throw new AssertionError("expected 409");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_idempotency_conflict");
		}
		assertThat(count("SELECT count(*) AS n FROM hypit_project WHERE account_id = :o", OWNER_B)).isEqualTo(1L);
		String commandResultAfter = db
				.sql("SELECT result_json::text AS r FROM hypit_command"
						+ " WHERE account_id = :o AND action = 'project.import'")
				.bind("o", OWNER_B).map((row, meta) -> row.get("r", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(commandResultAfter).isEqualTo(commandResultBefore);

		// 失败导入（broker 拒收）：provisioning_failed 重新查询可见，不是孤儿成功 id。
		SIDECAR.resetAll();
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.import"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"project-package.import","state":"failed",
						 "error":{"code":"invalid_input","message":"hash mismatch for main.svml"}}
						""")));
		try {
			packages.import_(OWNER_B, UUID.randomUUID(), "project-exports/bad/rev-1", null, null)
					.block(Duration.ofSeconds(20));
			throw new AssertionError("expected refusal");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("invalid_input");
		}
		// 两个工程行：成功 ready + 失败 provisioning_failed（重新查询可见）。
		Long failed = db
				.sql("SELECT count(*) AS n FROM hypit_project WHERE account_id = :o"
						+ " AND status = 'provisioning_failed'")
				.bind("o", OWNER_B).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(failed).isEqualTo(1L);
		String failedJob = db
				.sql("SELECT state FROM hypit_job WHERE account_id = :o AND kind = 'project.import'"
						+ " ORDER BY created_at DESC LIMIT 1")
				.bind("o", OWNER_B).map((row, meta) -> row.get("state", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(failedJob).isEqualTo("failed");
	}
}
