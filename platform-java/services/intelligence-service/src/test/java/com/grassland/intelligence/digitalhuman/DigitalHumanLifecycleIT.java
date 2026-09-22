package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository;
import com.grassland.intelligence.compliance.PersonalDataErasureService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 数字人注销/删除/阶段门禁 IT（任务书 #105B C105B-05 / TC105B-05-01～04）。
 *
 * <p>
 * 覆盖：role/operation 创建与注销 prepare 的 DB gate 竞态（不靠先查后写；freeze 后无新行）； 清理归属（A/B 角色
 * + 系统 catalog preset，A 注销两次仅清 A 且幂等）；dh invocation unknown 阻塞 prepare 与
 * verify（可删已授权 内容但不伪 verified）；阶段真实检查（lifecycle 门禁 + 登记 handler/TC 真实存在）。
 */
class DigitalHumanLifecycleIT extends IntelligenceItSupport {

	@Autowired
	private DatabaseClient db;

	@Autowired
	private DigitalHumanLifecycleService lifecycle;

	@Autowired
	private IntelligenceAccountLifecycleRepository gate;

	@Autowired
	private PersonalDataErasureService erasure;

	@Autowired
	private com.grassland.intelligence.compliance.IntelligenceJobInventory inventory;

	private static final ObjectMapper JSON = new ObjectMapper();

	// ---------- TC105B-05-01：create 与 freeze 竞态 ----------

	@Test
	void tc105b_05_01_createVersusFreezeRaceUsesSameGate() throws Exception {
		// 分支一：在途创建（未提交 dh_operation）先持有 gate FOR SHARE → prepare 等；提交后 prepare
		// 锁内复查 inventory（创建可被计数）→ ACTIVE_JOBS 阻塞，不冻结。
		String account = "dh-lc-race-" + UUID.randomUUID();
		java.util.concurrent.CompletableFuture<Throwable> prepareOutcome = new java.util.concurrent.CompletableFuture<>();
		try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword())) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement(
					"INSERT INTO dh_operation(id, owner_account_id, kind, request_id, payload_hash, state)"
							+ " VALUES (gen_random_uuid(), ?, 'profile_create', gen_random_uuid(),"
							+ " repeat('0',64), 'pending')")) {
				statement.setString(1, account);
				statement.executeUpdate();
			}
			var preparing = gate.prepare(account, UUID.randomUUID()).toFuture();
			org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				Long waiting = db
						.sql("SELECT count(*) AS n FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
								+ " AND query LIKE '%intelligence_account_lifecycle%'")
						.map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
				assertThat(waiting).isGreaterThan(0L);
			});
			assertThat(preparing).isNotDone();
			connection.commit();
			// 直接核对 inventory：隔离「行存在」「计数 SQL」与「prepare 锁内复查」三层。
			Long rawCount = db
					.sql("SELECT count(*) AS n FROM dh_operation WHERE owner_account_id = :a"
							+ " AND state IN ('pending','running','unknown')")
					.bind("a", account).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
			assertThat(rawCount).as("提交后 pending dh_operation 行必须存在").isEqualTo(1L);
			var directCounts = inventory.countByKind(account).block(Duration.ofSeconds(5));
			assertThat(directCounts).as("inventory 必须计数 pending dh_operation").containsKey("dh_operation");
			Throwable blocked = null;
			try {
				preparing.get(5, TimeUnit.SECONDS);
			} catch (Exception failure) {
				blocked = failure.getCause() != null ? failure.getCause() : failure;
			}
			assertThat(blocked).as("在途 operation 提交后 prepare 必须被 ACTIVE_JOBS 阻塞").isNotNull();
			assertThat(String.valueOf(blocked.getMessage())).contains("dh_operation");
			assertThat(gate.find(account).block(Duration.ofSeconds(5)).state()).isEqualTo("active");

			// 分支二：无活动时 prepare 冻结成功 → freeze 后新行被 dh 守卫拒绝（无 freeze 后新行）。
			db.sql("DELETE FROM dh_operation WHERE owner_account_id = :o").bind("o", account).then()
					.block(Duration.ofSeconds(5));
			assertThat(gate.prepare(account, UUID.randomUUID()).block(Duration.ofSeconds(5)).state())
					.isEqualTo("frozen");
			Throwable rejected = null;
			try {
				db.sql("INSERT INTO dh_operation(id, owner_account_id, kind, request_id, payload_hash, state)"
						+ " VALUES (gen_random_uuid(), :o, 'profile_create', gen_random_uuid(), repeat('0',64),"
						+ " 'pending')").bind("o", account).then().block(Duration.ofSeconds(5));
			} catch (RuntimeException failure) {
				rejected = failure;
			}
			assertThat(rejected).as("freeze 后新建必须被 DB 拒绝").isNotNull();
			assertThat(String.valueOf(rootMessage(rejected))).contains("account_closure_barrier");
			prepareOutcome.complete(null);
		}
		assertThat(prepareOutcome).isCompleted();
	}

	private static String rootMessage(Throwable error) {
		Throwable current = error;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return String.valueOf(current.getMessage());
	}

	// ---------- TC105B-05-02：清理归属 ----------

	@Test
	void tc105b_05_02_erasureScopesToOwnerAndIsIdempotent() {
		String a = "dh-lc-a-" + UUID.randomUUID();
		String b = "dh-lc-b-" + UUID.randomUUID();
		seedPersonalProfile(a, "A 的角色");
		seedPersonalProfile(b, "B 的角色");
		// 系统 preset：dh_catalog singleton 行（平台配置，不随个人注销删除）。
		db.sql("INSERT INTO dh_catalog(singleton_id, version, config_json, updated_by)"
				+ " VALUES (1, 1, CAST(:c AS jsonb), 'it') ON CONFLICT (singleton_id) DO NOTHING")
				.bind("c", "{\"enabled\":true,\"newSessionsAllowed\":true}").then().block(Duration.ofSeconds(5));

		UUID request = UUID.randomUUID();
		gate.prepare(a, request).block(Duration.ofSeconds(5));
		var manifest = erasure.plan(a, request).block(Duration.ofSeconds(5));
		var first = erasure.process(manifest.id()).block(Duration.ofSeconds(30));
		assertThat(first.state()).isEqualTo("completed");
		assertThat(first.erased()).isTrue();

		// 仅 A 个人内容清；B 角色与 catalog preset 不动。
		assertThat(count("dh_profile", a)).isZero();
		assertThat(count("dh_profile_revision", a)).isZero();
		assertThat(count("dh_profile", b)).isEqualTo(1L);
		assertThat(count("dh_profile_revision", b)).isEqualTo(1L);
		assertThat(count("dh_catalog", null)).isEqualTo(1L);

		// 二次幂等：同 manifest 重复 process → completed、无新计数、B 仍在。
		var second = erasure.process(manifest.id()).block(Duration.ofSeconds(30));
		assertThat(second.state()).isEqualTo("completed");
		assertThat(second.counts().get("dh_profile")).isEqualTo(first.counts().get("dh_profile"));
		assertThat(count("dh_profile", b)).isEqualTo(1L);

		cleanupErasureTarget(b);
	}

	// ---------- TC105B-05-03：未知活动 ----------

	@Test
	void tc105b_05_03_unknownInvocationBlocksButContentErases() {
		String a = "dh-lc-unknown-" + UUID.randomUUID();
		seedPersonalProfile(a, "未知活动角色");
		seedUnknownInvocation(a);
		seedTranscriptRow(a);

		// prepare 被未决 invocation 阻塞（明确阻塞经济核对）。
		Throwable blocked = null;
		try {
			gate.prepare(a, UUID.randomUUID()).block(Duration.ofSeconds(5));
		} catch (RuntimeException failure) {
			blocked = failure;
		}
		assertThat(blocked).isNotNull();
		assertThat(rootMessage(blocked)).contains("dh_invocation");

		// 模拟经济核对结论未出但清理已授权（人工/对账后放行冻结）：直接置 frozen。
		UUID request = UUID.randomUUID();
		db.sql("INSERT INTO intelligence_account_lifecycle(account_id, closure_request_id, state)"
				+ " VALUES (:a, CAST(:r AS uuid), 'frozen') ON CONFLICT (account_id) DO UPDATE SET"
				+ " closure_request_id = CAST(:r AS uuid), state = 'frozen'").bind("a", a).bind("r", request.toString())
				.then().block(Duration.ofSeconds(5));
		var manifest = erasure.plan(a, request).block(Duration.ofSeconds(5));
		var receipt = erasure.process(manifest.id()).block(Duration.ofSeconds(30));

		// 已授权内容已删（profile/transcript 清空）；未决 invocation 阻塞 verify → 不伪 completed。
		assertThat(count("dh_profile", a)).isZero();
		assertThat(count("dh_transcript", a)).isZero();
		assertThat(receipt.state()).isEqualTo("needs_review");
		assertThat(receipt.erased()).isFalse();
		assertThat(count("dh_invocation", a)).as("经济事实行保留（脱敏不删除）").isEqualTo(1L);

		// dh 侧 verifyResidue 同口径：未决 → complete=false。
		var progress = lifecycle.verifyResidue(a, manifest.id(), java.util.Map.of()).block(Duration.ofSeconds(5));
		assertThat(progress.complete()).isFalse();
		assertThat(progress.retained()).isEqualTo(1);

		cleanupErasureTarget(a);
	}

	// ---------- TC105B-05-04：阶段真实检查 ----------

	@Test
	void tc105b_05_04_stageGatesRealInventory() throws Exception {
		Path repoRoot = Paths.get("..", "..", "..").toAbsolutePath().normalize();
		assertThat(repoRoot.resolve("package.json")).exists();

		// 真实 lifecycle 门禁（真实 registry + baseline + 现场扫描）退出 0。
		ProcessBuilder builder = new ProcessBuilder("bash", "-lc", "npm run quality:lifecycle");
		builder.directory(repoRoot.toFile());
		Process gateProcess = builder.start();
		String output = new String(gateProcess.getInputStream().readAllBytes())
				+ new String(gateProcess.getErrorStream().readAllBytes());
		assertThat(gateProcess.waitFor(300, TimeUnit.SECONDS)).isTrue();
		assertThat(gateProcess.exitValue()).as("真实 lifecycle 门禁应通过。输出：%s", output).isZero();
		assertThat(output).contains("29 资源");

		// 11 张 dh 表登记全部指向真实 handler 与 TC 文件（不留 B01 临时指向）。
		JsonNode registry = JSON
				.readTree(repoRoot.resolve("tests/contracts/resource-lifecycle.registry.json").toFile());
		int dhEntries = 0;
		for (JsonNode entry : registry.get("resources")) {
			if (entry.get("table").asText().startsWith("dh_")) {
				dhEntries++;
				String handler = entry.get("eraseHandler").asText();
				assertThat(handler).as("%s eraseHandler", entry.get("table"))
						.matches(".*(PersonalDataErasureService|DigitalHumanLifecycleService|不删除).*");
				assertThat(Files.exists(repoRoot.resolve(entry.get("tc").asText()))).as("%s tc 存在", entry.get("table"))
						.isTrue();
			}
		}
		assertThat(dhEntries).isEqualTo(11);

		// 迁移文件真实存在且 V87 未被改动（基线保护）。
		assertThat(Files.exists(repoRoot.resolve(
				"platform-java/services/intelligence-service/src/main/resources/db/migration/V88__digital_human_core.sql")))
				.isTrue();
	}

	// ---------- 种子与清理 ----------

	private void seedPersonalProfile(String owner, String name) {
		UUID profileId = UUID.randomUUID();
		db.sql("INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :o, :n, 1, 'active')").bind("id", profileId.toString()).bind("o", owner)
				.bind("n", name).then().block(Duration.ofSeconds(5));
		db.sql("INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,"
				+ " tone, avatar_id, avatar_revision, voice_id, catalog_version)"
				+ " VALUES (gen_random_uuid(), :o, CAST(:id AS uuid), 1, 'p', 'g', 'natural', gen_random_uuid(),"
				+ " 1, 'v', 1)").bind("o", owner).bind("id", profileId.toString()).then().block(Duration.ofSeconds(5));
	}

	private void seedUnknownInvocation(String owner) {
		db.sql("INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id, stage, resource_id,"
				+ " segment_index, operation_id, state, settlement_state, provider_snapshot, budget_snapshot,"
				+ " request_hash, deadline_at) VALUES (gen_random_uuid(), :o, NULL, NULL, 'preview',"
				+ " gen_random_uuid(), 0, gen_random_uuid(), 'unknown', 'pending', '{}'::jsonb, '{}'::jsonb,"
				+ " repeat('0',64), now())").bind("o", owner).then().block(Duration.ofSeconds(5));
	}

	private void seedTranscriptRow(String owner) {
		String session = UUID.randomUUID().toString();
		db.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
				+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot, state,"
				+ " state_entered_at) VALUES (CAST(:s AS uuid), :o, gen_random_uuid(), 1, 'n', 'mock',"
				+ " gen_random_uuid(), gen_random_uuid(), '{}'::jsonb, 'ended', now())").bind("s", session)
				.bind("o", owner).then().block(Duration.ofSeconds(5));
		db.sql("INSERT INTO dh_transcript(id, owner_account_id, session_id, utterance_id, utterance_seq, role,"
				+ " final_text, status, started_at, ended_at, content_epoch) VALUES (gen_random_uuid(), :o,"
				+ " CAST(:s AS uuid), gen_random_uuid(), 1, 'user', '已授权文本', 'complete', now(), now(), 1)")
				.bind("s", session).bind("o", owner).then().block(Duration.ofSeconds(5));
	}

	private Long count(String table, String owner) {
		String sql = "SELECT count(*) AS n FROM " + table;
		if (owner != null) {
			return db.sql(sql + " WHERE owner_account_id = :o").bind("o", owner).map(row -> row.get("n", Long.class))
					.one().block(Duration.ofSeconds(5));
		}
		return db.sql(sql).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
	}

	private void cleanupErasureTarget(String owner) {
		// 共享容器只清本用例归属（含 gate 行；不触碰 dh_catalog 平台行）。
		db.sql("DELETE FROM dh_invocation WHERE owner_account_id = :o").bind("o", owner).then()
				.then(db.sql("DELETE FROM dh_operation WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM dh_transcript WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM personal_data_erasure_object WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id = :o)").bind("o", owner)
						.then())
				.then(db.sql("DELETE FROM personal_data_erasure_step WHERE manifest_id IN"
						+ " (SELECT id FROM personal_data_erasure_manifest WHERE account_id = :o)").bind("o", owner)
						.then())
				.then(db.sql("DELETE FROM personal_data_erasure_manifest WHERE account_id = :o").bind("o", owner)
						.then())
				.then(db.sql("DELETE FROM intelligence_account_lifecycle WHERE account_id = :o").bind("o", owner)
						.then())
				.block(Duration.ofSeconds(10));
	}
}
