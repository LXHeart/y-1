package com.grassland.intelligence.digitalhuman;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 数字人核心 DDL 与逐表生命周期登记 IT（任务书 #105B C105B-01 / TC105B-01-01～04）。
 *
 * <p>
 * 覆盖：空库全量迁移与 V87 增量升级收敛（表/约束/索引/触发器一致、旧数据与 checksum 不变）； owner 活动会话并发唯一（双独立
 * JDBC 事务屏障对撞 + 仓储 409 映射）；非法行 DB 拒绝且无残行（null owner/重复 revision/负 epoch/stage
 * 形状）；registry 漏登记 dh_session 时 lifecycle 门禁失败且指向真实 handler。
 */
class DigitalHumanSchemaIT extends IntelligenceItSupport {

	/** TC105B-01-01 专用隔离实例：一个全量、一个 V87→V88 增量（不与共享容器混用）。 */
	static final PostgreSQLContainer<?> FULL_DB = new PostgreSQLContainer<>("postgres:16-alpine");

	static final PostgreSQLContainer<?> UPGRADE_DB = new PostgreSQLContainer<>("postgres:16-alpine");

	static {
		FULL_DB.start();
		UPGRADE_DB.start();
	}

	@Autowired
	private DigitalHumanRepository repository;

	@Autowired
	private DigitalHumanLifecycleService lifecycle;

	// ---------- TC105B-01-01：空库与 V87 升级 ----------

	@Test
	void tc105b_01_01_emptySchemaAndV87UpgradeConverge() throws Exception {
		Flyway.configure().dataSource(FULL_DB.getJdbcUrl(), FULL_DB.getUsername(), FULL_DB.getPassword())
				.table("intelligence_flyway_schema").locations("classpath:db/migration").baselineOnMigrate(true)
				.baselineVersion("0").load().migrate();

		Flyway.configure().dataSource(UPGRADE_DB.getJdbcUrl(), UPGRADE_DB.getUsername(), UPGRADE_DB.getPassword())
				.table("intelligence_flyway_schema").locations("classpath:db/migration").baselineOnMigrate(true)
				.baselineVersion("0").target("87").load().migrate();

		// 增量实例在 V87 基线上种一笔旧业务行（结构/内容迁移后必须原样保留）。
		String legacyAccount = "legacy-" + UUID.randomUUID();
		String legacyOperation = UUID.randomUUID().toString();
		try (Connection c = connection(UPGRADE_DB); Statement s = c.createStatement()) {
			s.execute("INSERT INTO ai_run(operation_id, account_id, capability, provider, budget_cents, status,"
					+ " started_at) VALUES ('" + legacyOperation + "', '" + legacyAccount + "', 'text', 'sandbox',"
					+ " 0, 'running', '2026-09-01T00:00:00Z')");
		}
		Map<String, String> checksumsBefore = historyChecksums(UPGRADE_DB);

		// 增量升级到最新（含 V88）。
		Flyway.configure().dataSource(UPGRADE_DB.getJdbcUrl(), UPGRADE_DB.getUsername(), UPGRADE_DB.getPassword())
				.table("intelligence_flyway_schema").locations("classpath:db/migration").baselineOnMigrate(true)
				.baselineVersion("0").load().migrate();

		// 旧迁移 checksum（V1～V87）升级前后不变，且与全量实例一致。
		Map<String, String> checksumsAfter = historyChecksums(UPGRADE_DB);
		assertThat(checksumsAfter).isEqualTo(checksumsBefore);
		assertThat(historyChecksums(FULL_DB)).containsAllEntriesOf(checksumsAfter);

		// 旧业务行内容不变。
		try (Connection c = connection(UPGRADE_DB);
				PreparedStatement ps = c.prepareStatement(
						"SELECT account_id, status, started_at FROM ai_run WHERE operation_id = ?::uuid")) {
			ps.setString(1, legacyOperation);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getString(1)).isEqualTo(legacyAccount);
				assertThat(rs.getString(2)).isEqualTo("running");
				assertThat(rs.getTimestamp(3).toInstant().toString()).isEqualTo("2026-09-01T00:00:00Z");
				assertThat(rs.next()).isFalse();
			}
		}

		// dh 表结构逐列/逐约束/逐索引/逐触发器一致。
		assertThat(dhColumns(UPGRADE_DB)).isEqualTo(dhColumns(FULL_DB));
		assertThat(dhConstraints(UPGRADE_DB)).isEqualTo(dhConstraints(FULL_DB));
		assertThat(dhIndexes(UPGRADE_DB)).isEqualTo(dhIndexes(FULL_DB));
		assertThat(dhTriggers(UPGRADE_DB)).isEqualTo(dhTriggers(FULL_DB));
		// 九张 dh 个人表全部挂独立守卫触发器。
		assertThat(dhTriggers(FULL_DB)).hasSize(9).allMatch(t -> t.contains("trg_dh_guard_"));
	}

	// ---------- TC105B-01-02：并发唯一 ----------

	@Test
	void tc105b_01_02_concurrentActiveSessionAllowedOnce() throws Exception {
		String owner = "dh-it-" + UUID.randomUUID();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch gate = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		List<String> failures = new ArrayList<>();
		List<Thread> workers = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			Thread worker = new Thread(() -> {
				ready.countDown();
				try {
					gate.await();
					try (Connection c = connection(POSTGRES)) {
						try (PreparedStatement ps = c.prepareStatement(
								"INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
										+ " profile_name_at_creation, backend_id, preflight_id, controller_id,"
										+ " config_snapshot, state, state_entered_at) VALUES (?::uuid, ?, ?::uuid, 1,"
										+ " 'it', 'mock', ?::uuid, ?::uuid, '{}'::jsonb, 'ready', now())")) {
							ps.setObject(1, UUID.randomUUID());
							ps.setString(2, owner);
							ps.setObject(3, UUID.randomUUID());
							ps.setObject(4, UUID.randomUUID());
							ps.setObject(5, UUID.randomUUID());
							ps.executeUpdate();
							successes.incrementAndGet();
						}
					}
				} catch (SQLException failure) {
					synchronized (failures) {
						failures.add(String.valueOf(failure.getMessage()));
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}, "dh-session-writer-" + i);
			worker.start();
			workers.add(worker);
		}
		assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		gate.countDown();
		for (Thread worker : workers) {
			worker.join(20_000);
		}
		assertThat(successes.get()).as("同 owner 并发两活动 session 仅一条提交成功").isEqualTo(1);
		assertThat(failures).hasSize(1);
		assertThat(failures.get(0)).contains("uq_dh_session_owner_active");
		Long active = db
				.sql("SELECT count(*) AS n FROM dh_session WHERE owner_account_id = :o"
						+ " AND state NOT IN ('ended','failed')")
				.bind("o", owner).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(active).as("无双 slot").isEqualTo(1L);

		// 仓储路径：同 owner 第三次插入映射 409 dh_session_active。
		String repoOwner = "dh-it-repo-" + UUID.randomUUID();
		var insert = new DigitalHumanRepository.SessionInsert(UUID.randomUUID(), repoOwner, UUID.randomUUID(), 1, "it",
				"mock", UUID.randomUUID(), UUID.randomUUID(), "{}");
		assertThat(repository.insertSession(insert).block(Duration.ofSeconds(10))).isNotNull();
		assertThatThrownBySessionActive(
				() -> repository
						.insertSession(new DigitalHumanRepository.SessionInsert(UUID.randomUUID(), repoOwner,
								UUID.randomUUID(), 1, "it", "mock", UUID.randomUUID(), UUID.randomUUID(), "{}"))
						.block(Duration.ofSeconds(10)));
		cleanupSessions(owner);
		cleanupSessions(repoOwner);
	}

	private void assertThatThrownBySessionActive(Runnable call) {
		try {
			call.run();
			throw new AssertionError("期望 409 dh_session_active，实际成功");
		} catch (IntelligenceException expected) {
			assertThat(expected.status()).isEqualTo(409);
			assertThat(expected.code()).isEqualTo("dh_session_active");
		}
	}

	// ---------- TC105B-01-03：非法行（参数化） ----------

	@ParameterizedTest
	@ValueSource(strings = {"null_owner_profile", "duplicate_revision", "negative_epoch", "bad_stage_shape"})
	void tc105b_01_03_invalidRowsRejectedByDatabase(String caseName) {
		String owner = "dh-it-invalid-" + UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		switch (caseName) {
			case "null_owner_profile" ->
				// 不给 owner 列 → NOT NULL 拒绝；无残行。
				assertRejectedAndClean(
						() -> db.sql("INSERT INTO dh_profile(id, name) VALUES (CAST(:id AS uuid), 'x')")
								.bind("id", profileId.toString()).then().block(Duration.ofSeconds(10)),
						"dh_profile", owner, profileId, 0L);
			case "duplicate_revision" -> {
				db.sql(insertProfileSql()).bind("id", profileId.toString()).bind("owner", owner).bind("name", "x")
						.then().block(Duration.ofSeconds(10));
				db.sql(insertRevisionSql()).bind("profile", profileId.toString()).bind("owner", owner)
						.bind("rid", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10));
				// 同 (profile_id, revision) 二次插入 → UNIQUE 拒绝；残行=预种的合法 revision1。
				assertRejectedAndClean(
						() -> db.sql(insertRevisionSql()).bind("profile", profileId.toString()).bind("owner", owner)
								.bind("rid", UUID.randomUUID().toString()).then().block(Duration.ofSeconds(10)),
						"dh_profile_revision", owner, profileId, 1L);
				Long revisions = db
						.sql("SELECT count(*) AS n FROM dh_profile_revision WHERE profile_id = CAST(:p AS uuid)")
						.bind("p", profileId.toString()).map(row -> row.get("n", Long.class)).one()
						.block(Duration.ofSeconds(10));
				assertThat(revisions).isEqualTo(1L);
			}
			case "negative_epoch" ->
				// lease_epoch = -1 → CHECK 拒绝；无残行。
				assertRejectedAndClean(() -> db
						.sql("INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,"
								+ " profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot,"
								+ " state, state_entered_at, lease_epoch) VALUES (CAST(:id AS uuid), :owner,"
								+ " gen_random_uuid(), 1, 'it', 'mock', gen_random_uuid(), gen_random_uuid(),"
								+ " '{}'::jsonb, 'ready', now(), -1)")
						.bind("id", UUID.randomUUID().toString()).bind("owner", owner).then()
						.block(Duration.ofSeconds(10)), "dh_session", owner, null, 0L);
			case "bad_stage_shape" ->
				// preview 挂 session → stage 形状 CHECK 拒绝；无残行。
				assertRejectedAndClean(() -> db
						.sql("INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id, stage,"
								+ " resource_id, segment_index, operation_id, state, settlement_state,"
								+ " provider_snapshot, budget_snapshot, request_hash, deadline_at)"
								+ " VALUES (CAST(:id AS uuid), :owner, gen_random_uuid(), NULL, 'preview',"
								+ " gen_random_uuid(), 0, gen_random_uuid(), 'reserved', 'not_required',"
								+ " '{}'::jsonb, '{}'::jsonb, repeat('0', 64), now())")
						.bind("id", UUID.randomUUID().toString()).bind("owner", owner).then()
						.block(Duration.ofSeconds(10)), "dh_invocation", owner, null, 0L);
			default -> throw new IllegalArgumentException(caseName);
		}
		cleanupOwner(owner, profileId);
	}

	private String insertProfileSql() {
		return "INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)"
				+ " VALUES (CAST(:id AS uuid), :owner, :name, 1, 'active')";
	}

	private String insertRevisionSql() {
		return "INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,"
				+ " tone, avatar_id, avatar_revision, voice_id, catalog_version) VALUES (CAST(:rid AS uuid),"
				+ " :owner, CAST(:profile AS uuid), 1, 'p', 'g', 'natural', gen_random_uuid(), 1, 'v', 1)";
	}

	/** 断言插入被 DB 拒绝（唯一/NOT NULL/CHECK），且该归属残行数=预期（含预种合法行）。 */
	private void assertRejectedAndClean(Runnable insert, String table, String owner, UUID profileId,
			long expectedResidual) {
		Throwable rejected = null;
		try {
			insert.run();
		} catch (RuntimeException | Error failure) {
			rejected = failure;
		}
		assertThat(rejected).as("插入应被 DB 拒绝").isNotNull();
		boolean integrity = false;
		for (Throwable cur = rejected; cur != null; cur = cur.getCause()) {
			if (cur instanceof io.r2dbc.spi.R2dbcException r && r.getSqlState() != null
					&& (r.getSqlState().startsWith("23") || r.getSqlState().startsWith("27"))) {
				integrity = true;
				break;
			}
		}
		assertThat(integrity).as("应为 DB 完整性拒绝，实际 %s", rejected).isTrue();
		Long residual = countByOwner(table, owner, profileId);
		assertThat(residual).as("%s 残行数", table).isEqualTo(expectedResidual);
	}

	private Long countByOwner(String table, String owner, UUID profileId) {
		String column = table.equals("dh_profile") ? "id" : "owner_account_id";
		Object value = table.equals("dh_profile") ? profileId.toString() : owner;
		return db
				.sql("SELECT count(*) AS n FROM " + table + " WHERE " + column + " = "
						+ (column.equals("id") ? "CAST(:v AS uuid)" : ":v"))
				.bind("v", value).map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
	}

	// ---------- TC105B-01-04：新表漏登记 → lifecycle 门禁失败 ----------

	@Test
	void tc105b_01_04_registryOmissionFailsLifecycleGate() throws Exception {
		Path repoRoot = Paths.get("..", "..", "..").toAbsolutePath().normalize();
		assertThat(repoRoot.resolve("package.json")).exists();
		Path registry = repoRoot.resolve("tests/contracts/resource-lifecycle.registry.json");
		Path baseline = repoRoot.resolve("tests/contracts/lifecycle-inventory.baseline.json");
		Path events = repoRoot.resolve("tests/contracts/event-consumers.registry.json");
		assertThat(registry).exists();

		ObjectMapper mapper = new ObjectMapper();
		JsonNode root = mapper.readTree(registry.toFile());
		ArrayNode resources = (ArrayNode) root.get("resources");
		int before = resources.size();
		for (int i = resources.size() - 1; i >= 0; i--) {
			if ("dh_session".equals(resources.get(i).get("table").asText())) {
				resources.remove(i);
			}
		}
		assertThat(resources.size()).as("临时 registry 应删去 dh_session 一条").isEqualTo(before - 1);

		Path fixture = Files.createTempDirectory("dh-lifecycle-fixture");
		try {
			mapper.writerWithDefaultPrettyPrinter()
					.writeValue(fixture.resolve("resource-lifecycle.registry.json").toFile(), root);
			Files.copy(baseline, fixture.resolve("lifecycle-inventory.baseline.json"));
			Files.copy(events, fixture.resolve("event-consumers.registry.json"));

			ProcessBuilder builder = new ProcessBuilder("bash", "-lc",
					"npx tsx scripts/quality/check-lifecycle-contracts.ts");
			builder.directory(repoRoot.toFile());
			builder.environment().put("LIFECYCLE_REGISTRY_ROOT", fixture.toString());
			Process gate = builder.start();
			String output = new String(gate.getInputStream().readAllBytes())
					+ new String(gate.getErrorStream().readAllBytes());
			boolean finished = gate.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(finished).as("lifecycle 门禁应在 300s 内结束").isTrue();
			assertThat(gate.exitValue()).as("漏登记 dh_session 时门禁必须失败。输出：%s", output).isNotZero();
			assertThat(output).contains("dh_session");

			// 失败指向真实 producer/handler：正式登记的 dh_session 条目引用真实 handler 类与本 IT 文件。
			JsonNode real = mapper.readTree(registry.toFile());
			JsonNode dhSession = null;
			for (JsonNode entry : real.get("resources")) {
				if ("dh_session".equals(entry.get("table").asText())) {
					dhSession = entry;
					break;
				}
			}
			assertThat(dhSession).as("正式 registry 必须登记 dh_session").isNotNull();
			assertThat(dhSession.get("eraseHandler").asText()).contains("DigitalHumanLifecycleService");
			assertThat(repoRoot.resolve(dhSession.get("tc").asText())).exists();
			assertThat(dhSession.get("ownerResolver").asText()).isEqualTo("owner_account_id");
			Map<String, Long> probe = lifecycle.countRows("dh-it-none-" + UUID.randomUUID())
					.block(Duration.ofSeconds(10));
			assertThat(probe).as("登记 handler 是真实实现（可调用而非 stub）").isNotNull()
					.allSatisfy((table, count) -> assertThat(count).as(table).isZero());
		} finally {
			deleteRecursively(fixture);
		}
	}

	private static void deleteRecursively(Path dir) throws java.io.IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.delete(path);
				} catch (java.io.IOException ignored) {
					// 临时目录尽力清理
				}
			});
		}
	}

	// ---------- 清理（共享容器只清本用例合成数据） ----------

	private void cleanupSessions(String owner) {
		db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", owner).then()
				.block(Duration.ofSeconds(10));
	}

	private void cleanupOwner(String owner, UUID profileId) {
		db.sql("DELETE FROM dh_session WHERE owner_account_id = :o").bind("o", owner).then()
				.then(db.sql("DELETE FROM dh_invocation WHERE owner_account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM dh_profile_revision WHERE owner_account_id = :o"
						+ " OR profile_id = CAST(:p AS uuid)").bind("o", owner).bind("p", profileId.toString()).then())
				.then(db.sql("DELETE FROM dh_profile WHERE owner_account_id = :o OR id = CAST(:p AS uuid)")
						.bind("o", owner).bind("p", profileId.toString()).then())
				.block(Duration.ofSeconds(10));
	}

	// ---------- 迁移/结构工具 ----------

	private static Connection connection(PostgreSQLContainer<?> container) throws SQLException {
		return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
	}

	/** V1～V87 的 version→checksum（升级前后与跨实例一致性证据）。 */
	private static Map<String, String> historyChecksums(PostgreSQLContainer<?> container) throws SQLException {
		Map<String, String> checksums = new TreeMap<>();
		try (Connection c = connection(container);
				PreparedStatement ps = c.prepareStatement(
						"SELECT version, checksum FROM intelligence_flyway_schema WHERE version::int <= 87"
								+ " ORDER BY version::int");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				checksums.put(rs.getString(1), String.valueOf(rs.getObject(2)));
			}
		}
		return checksums;
	}

	private static List<String> dhColumns(PostgreSQLContainer<?> container) throws SQLException {
		return query(container,
				"SELECT table_name || ':' || ordinal_position || ':' || column_name || ':'"
						+ " || data_type || ':' || is_nullable FROM information_schema.columns"
						+ " WHERE table_name LIKE 'dh\\_%' ORDER BY table_name, ordinal_position");
	}

	private static List<String> dhConstraints(PostgreSQLContainer<?> container) throws SQLException {
		return query(container,
				"SELECT table_name || ':' || constraint_name || ':' || constraint_type"
						+ " FROM information_schema.table_constraints WHERE table_name LIKE 'dh\\_%'"
						+ " ORDER BY table_name, constraint_name");
	}

	private static List<String> dhIndexes(PostgreSQLContainer<?> container) throws SQLException {
		return query(container, "SELECT tablename || ':' || indexdef FROM pg_indexes"
				+ " WHERE tablename LIKE 'dh\\_%' ORDER BY tablename, indexname");
	}

	private static List<String> dhTriggers(PostgreSQLContainer<?> container) throws SQLException {
		return query(container, "SELECT tgrelid::regclass::text || ':' || tgname FROM pg_trigger"
				+ " WHERE tgname LIKE 'trg_dh_guard_%' AND NOT tgisinternal ORDER BY 1");
	}

	private static List<String> query(PostgreSQLContainer<?> container, String sql) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Connection c = connection(container);
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				rows.add(rs.getString(1));
			}
		}
		return rows;
	}
}
