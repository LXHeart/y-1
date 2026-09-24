package com.grassland.releasemigrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.database.FlywayBootstrap;
import com.grassland.releasemigrator.ReleaseMigratorApplication.ServiceMigrations;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 任务书 #105H C105H-02（TC105H-02-02）：数字人迁移升级兼容（空库/旧库/重新启动）。
 *
 * <ul>
 * <li>空库全量：五域 + bootstrap 全量迁移后 DH 表齐备（V88 核心 12 表 / V89 媒体 4 表）， 硬约束生效（owner
 * NOT NULL、资源唯一键、事件复合主键、catalog 单例、个人表无 org 列）；
 * <li>旧库升级： intelligence@V87（DH
 * 前最后一版）注入合成旧库样本（{@code tests/fixtures/digital-human/upgrade-cases.json}：
 * 旧个人资源/组织资源/未结费用），升级到最新后旧 checksum 与旧行不变、DH 约束对新表生效；
 * <li>重新启动： 二次迁移零新增、历史 checksum 与行数不变（无重复 seed/重复目录）。
 * </ul>
 *
 * <p>
 * 历史 SQL（V86/V87 及更早）与 checksum 一律只读；本测试不 repair、不改基线文件。
 */
@Testcontainers
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class DigitalHumanUpgradeIT {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	private static final ServiceMigrations INTELLIGENCE = new ServiceMigrations("intelligence-service",
			"intelligence_flyway_schema", false);

	private static DataSource dataSource;

	@BeforeAll
	static void start() {
		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setURL(POSTGRES.getJdbcUrl());
		ds.setUser(POSTGRES.getUsername());
		ds.setPassword(POSTGRES.getPassword());
		dataSource = ds;
	}

	@AfterAll
	static void stop() {
		POSTGRES.stop();
	}

	@BeforeEach
	void resetSchema() throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE");
			statement.execute("CREATE SCHEMA public");
		}
	}

	private static FluentConfiguration configure(ServiceMigrations service) {
		// 锁口径与 FlywayBootstrap.flyway 逐字一致（见 ReleaseMigratorUpgradeIT 同款注释）。
		FluentConfiguration configuration = Flyway.configure().dataSource(dataSource).table(service.historyTable())
				.locations("classpath:db/migratedb/" + service.service()).baselineOnMigrate(true).baselineVersion("0");
		if (service.disablePostgresTransactionalLock()) {
			configuration.configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
		}
		return configuration;
	}

	private static int migrateAll(ServiceMigrations service) {
		return FlywayBootstrap.flyway(dataSource, service.historyTable(), "classpath:db/migratedb/" + service.service(),
				service.disablePostgresTransactionalLock()).migrate().migrationsExecuted;
	}

	private static void migrateToBaseline(ServiceMigrations service, String target) {
		configure(service).target(target).load().migrate();
	}

	private static void execute(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static int queryInt(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}

	private static Map<String, Long> historyChecksums() throws SQLException {
		Map<String, Long> checksums = new TreeMap<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT version, checksum FROM intelligence_flyway_schema WHERE version IS NOT NULL")) {
			while (rs.next()) {
				checksums.put(rs.getString(1), rs.getLong(2));
			}
		}
		return checksums;
	}

	/** V88/V89 建的全部 DH 表（K05 阶段列）。 */
	private static final String[] DH_TABLES = {"dh_profile", "dh_profile_revision", "dh_catalog", "dh_operation",
			"dh_session", "dh_turn", "dh_event", "dh_transcript", "dh_invocation", "dh_preview", "dh_admin_audit",
			"dh_avatar", "dh_recording", "dh_asset_attachment", "dh_cleanup"};

	/** 升级后 DH 硬约束必须生效（空库/旧库两路径共用）。 */
	private static void assertDigitalHumanConstraintsEnforced() throws SQLException {
		// 承载会话（render invocation 的 stage 形状要求 session 非空）。
		String session = """
				INSERT INTO dh_session (id, owner_account_id, profile_id, profile_revision, profile_name_at_creation,
						backend_id, preflight_id, state_entered_at, config_snapshot, state, controller_id)
				VALUES (CAST('6f1d0000-0000-4000-8000-000000000040' AS uuid), 'dh-upgrade-constraint',
						gen_random_uuid(), 1, '约束探测会话', 'dh-test', gen_random_uuid(), now(), '{}'::jsonb,
						'ended', gen_random_uuid())
				""";

		// owner 归属为事实：dh_session 缺 owner_account_id 直接 NOT NULL 拒绝。
		assertThatThrownBy(() -> execute("""
				INSERT INTO dh_session (id, profile_id, profile_revision, profile_name_at_creation, backend_id,
						preflight_id, state_entered_at, config_snapshot, state, controller_id)
				VALUES (gen_random_uuid(), gen_random_uuid(), 1, '约束探测', 'dh-test', gen_random_uuid(), now(),
						'{}'::jsonb, 'ended', gen_random_uuid())
				""")).isInstanceOf(SQLException.class).hasMessageContaining("owner_account_id");

		execute(session);

		// 稳定经济键：同 (owner, resource_id, stage, segment_index) 的第二行必须唯一冲突。
		String invocation = """
				INSERT INTO dh_invocation (id, owner_account_id, session_id, stage, resource_id, segment_index,
						operation_id, state, settlement_state, provider_snapshot, budget_snapshot, request_hash,
						deadline_at)
				VALUES (gen_random_uuid(), 'dh-upgrade-constraint',
						CAST('6f1d0000-0000-4000-8000-000000000040' AS uuid), 'render',
						CAST('6f1d0000-0000-4000-8000-000000000041' AS uuid), 0, gen_random_uuid(), 'reserved',
						'not_required', '{}'::jsonb, '{}'::jsonb, repeat('0', 64), now())
				""";
		execute(invocation);
		assertThatThrownBy(() -> execute(invocation)).isInstanceOf(SQLException.class)
				.hasMessageContaining("uq_dh_invocation_resource");

		// 事件序号权威：同 (session_id, seq) 复合主键冲突。
		String event = """
				INSERT INTO dh_event (session_id, seq, owner_account_id, event_id, event_type, payload)
				VALUES (CAST('6f1d0000-0000-4000-8000-000000000040' AS uuid), 1, 'dh-upgrade-constraint',
						gen_random_uuid(), 'runtime.ready', '{}'::jsonb)
				""";
		execute(event);
		assertThatThrownBy(() -> execute(event)).isInstanceOf(SQLException.class).hasMessageContaining("dh_event_pkey");

		// catalog 单例：singleton_id 只能为 1。
		assertThatThrownBy(() -> execute("""
				INSERT INTO dh_catalog (singleton_id, version, config_json, updated_by)
				VALUES (2, 1, '{}'::jsonb, 'constraint-probe')
				""")).isInstanceOf(SQLException.class);

		// 首期个人域无组织列：dh 个人表不得出现 organization_id（K05：禁止预建 org 列）。
		for (String table : new String[]{"dh_profile", "dh_session", "dh_invocation", "dh_transcript"}) {
			assertThat(queryInt("""
					SELECT count(*) FROM information_schema.columns
					WHERE table_name = '%s' AND column_name = 'organization_id'
					""".formatted(table))).as("%s 不得有 organization_id 列", table).isZero();
		}
	}

	/** 迁移升级合成样本（fixture 契约由 tests/deployment/digital-human-ci.test.ts 锁定）。 */
	private record UpgradeCase(String id, List<String> seedSql, List<String> expectationSql,
			List<Integer> expectationEquals) {
	}

	/**
	 * 定形解析（本模块无 JSON 依赖）：fixture 由 TS 契约测试锁定结构——SQL/断言字符串内
	 * 不得含双引号与转义，因此可用键边界切片可靠提取（SQL 内可有 [] 等字符）； 解析结果为空即失败（不静默跳过样本）。
	 */
	private static List<UpgradeCase> parseUpgradeCases(String content) {
		List<UpgradeCase> cases = new ArrayList<>();
		String[] blocks = content.split("\\{\\s*\"id\"\\s*:");
		for (int index = 1; index < blocks.length; index++) {
			String block = blocks[index];
			String id = Pattern.compile("\"([^\"]+)\"").matcher(block).results().findFirst().orElseThrow().group(1);

			List<String> seeds = new ArrayList<>();
			Matcher seedKey = Pattern.compile("\"seedSql\"\\s*:\\s*").matcher(block);
			if (seedKey.find()) {
				int begin = seedKey.end();
				int end = block.indexOf("\"expectedAfterUpgrade\"", begin);
				if (end < 0) {
					end = block.length();
				}
				Matcher items = Pattern.compile("\"([^\"]+)\"").matcher(block.substring(begin, end));
				while (items.find()) {
					seeds.add(items.group(1));
				}
			}

			List<String> expectationSql = new ArrayList<>();
			List<Integer> expectationEquals = new ArrayList<>();
			Matcher expectations = Pattern.compile("\"sql\"\\s*:\\s*\"([^\"]+)\",\\s*\"equals\"\\s*:\\s*(\\d+)")
					.matcher(block);
			while (expectations.find()) {
				expectationSql.add(expectations.group(1));
				expectationEquals.add(Integer.valueOf(expectations.group(2)));
			}
			cases.add(new UpgradeCase(id, seeds, expectationSql, expectationEquals));
		}
		return cases;
	}

	@Test
	@DisplayName("tc105h_02_02 空库全量迁移：DH 表齐备（V88/V89）、硬约束生效、无种子重复")
	void emptyDatabaseMigratesDigitalHumanSchemaWithConstraints() throws Exception {
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		for (ServiceMigrations service : ReleaseMigratorApplication.ORDER) {
			migrateAll(service);
		}

		for (String table : DH_TABLES) {
			assertThat(queryInt(
					"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '"
							+ table + "'"))
					.as("表 %s 应存在", table).isEqualTo(1);
		}
		// V88/V89/V90 全部入历史（V90 为 G 阶段修正迁移，随全量一并生效）。
		for (String version : new String[]{"88", "89", "90"}) {
			assertThat(historyChecksums()).containsKey(version);
		}
		// 无种子数据：catalog 由治理面配置写入，迁移不种 singleton（重启不得重复 seed 的前提）。
		assertThat(queryInt("SELECT count(*) FROM dh_catalog")).isZero();

		assertDigitalHumanConstraintsEnforced();
	}

	@Test
	@DisplayName("tc105h_02_02 V87 旧库升级 + 二次启动：旧行/旧 checksum 不变、DH 约束生效、零重复")
	void upgradesPreDigitalHumanBaselineTwicePreservingLegacyRows() throws Exception {
		// 其余域全量；intelligence 停在 V87（开始实施 #105 前的最后一版）。
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		for (ServiceMigrations service : ReleaseMigratorApplication.ORDER) {
			if (service.service().equals("intelligence-service")) {
				migrateToBaseline(service, "87");
			} else {
				migrateAll(service);
			}
		}

		// 合成旧库样本（升级前注入；见 tests/fixtures/digital-human/upgrade-cases.json）。
		Path fixture = Path.of("../../../tests/fixtures/digital-human/upgrade-cases.json");
		assertThat(Files.exists(fixture)).as("fixture 应存在（测试自模块目录运行）").isTrue();
		List<UpgradeCase> cases = parseUpgradeCases(Files.readString(fixture));
		assertThat(cases).isNotEmpty();
		for (UpgradeCase caze : cases) {
			assertThat(caze.seedSql()).as("%s 应含 seedSql", caze.id()).isNotEmpty();
			assertThat(caze.expectationSql()).as("%s 应含升级后断言", caze.id()).isNotEmpty();
			for (String sql : caze.seedSql()) {
				execute(sql);
			}
		}

		Map<String, Long> checksumsBefore = historyChecksums();
		assertThat(checksumsBefore).containsKey("87").doesNotContainKey("88");

		// 第一次升级：intelligence → 最新（88/89/90）。
		assertThat(migrateAll(INTELLIGENCE)).isPositive();
		Map<String, Long> checksumsAfterFirst = historyChecksums();
		for (String version : new String[]{"88", "89", "90"}) {
			assertThat(checksumsAfterFirst).containsKey(version);
		}
		// 旧历史 checksum 逐项不变（升级不 repair/不改写历史）。
		checksumsBefore
				.forEach((version, checksum) -> assertThat(checksumsAfterFirst).containsEntry(version, checksum));

		// 旧库样本逐项保留。
		for (UpgradeCase caze : cases) {
			for (int index = 0; index < caze.expectationSql().size(); index++) {
				assertThat(queryInt(caze.expectationSql().get(index))).as("%s[%d]", caze.id(), index)
						.isEqualTo(caze.expectationEquals().get(index));
			}
		}

		// DH 约束在旧库升级路径同样生效。
		assertDigitalHumanConstraintsEnforced();

		// 二次启动（服务重启 initMethod=migrate 语义）：零新增、历史与行数不变。
		assertThat(migrateAll(INTELLIGENCE)).isZero();
		assertThat(historyChecksums()).isEqualTo(checksumsAfterFirst);
		assertThat(queryInt("SELECT count(*) FROM dh_catalog")).isZero();
		for (UpgradeCase caze : cases) {
			for (int index = 0; index < caze.expectationSql().size(); index++) {
				assertThat(queryInt(caze.expectationSql().get(index))).isEqualTo(caze.expectationEquals().get(index));
			}
		}
	}
}
