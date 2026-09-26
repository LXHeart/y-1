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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 任务书 #103 C103-23（TC103-23-02/05/06）：release-migrator 升级与打包资源真实性。
 *
 * - 载荷字节一致：classpath db/migratedb/&lt;svc&gt; 与各服务源目录 db/migration 逐文件同字节； -
 * 基线升级：identity@V50 / marketplace@V64 / intelligence@V84 的「旧完整库」注入 旧多店长、旧
 * closed 残留与历史退款，跑全部新迁移后数据保留、新表不编造事实、 历史 checksum 不变（重放不 repair）； - 并发
 * job：两个迁移实例竞争同一测试库，Flyway 原生锁串行化，版本零重复； - 凭据边界：只读账号迁移明确失败且异常链不回显口令。
 */
@Testcontainers
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ReleaseMigratorUpgradeIT {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
		// 锁口径必须与 FlywayBootstrap.flyway 逐字一致（marketplace/trust 禁用 PG 事务锁）——
		// 用默认事务锁跑锁禁用服务的迁移会与其 DDL 语句自锁（2026-09-16 实测 30min 挂死）。
		FluentConfiguration configuration = Flyway.configure().dataSource(dataSource).table(service.historyTable())
				.locations("classpath:db/migratedb/" + service.service()).baselineOnMigrate(true).baselineVersion("0");
		if (service.disablePostgresTransactionalLock()) {
			configuration.configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
		}
		return configuration;
	}

	/** 按 {@link ReleaseMigratorApplication#flywayStep} 同一口径执行一个服务的全部迁移。 */
	private static int migrateAll(ServiceMigrations service) {
		return FlywayBootstrap.flyway(dataSource, service.historyTable(), "classpath:db/migratedb/" + service.service(),
				service.disablePostgresTransactionalLock()).migrate().migrationsExecuted;
	}

	/** 基线迁移：target 限定到任务书 #103 之前的版本，构造「旧完整库」。 */
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

	private static Map<String, Long> historyChecksums(String historyTable) throws SQLException {
		Map<String, Long> checksums = new TreeMap<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement
						.executeQuery("SELECT version, checksum FROM " + historyTable + " WHERE version IS NOT NULL")) {
			while (rs.next()) {
				checksums.put(rs.getString(1), rs.getLong(2));
			}
		}
		return checksums;
	}

	@Test
	@DisplayName("ApplicationRunner 生产装配路径：等待 DB → bootstrap → 五域顺序 → 幂等重跑")
	void applicationRunnerExecutesFullOrderIdempotently() throws Exception {
		MockEnvironment environment = new MockEnvironment().withProperty("DATABASE_URL",
				"postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":"
						+ POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName())
				.withProperty("migrator.max-attempts", "5").withProperty("migrator.retry-delay-ms", "0");
		ReleaseMigratorApplication application = new ReleaseMigratorApplication();
		DataSource runnerDataSource = application.dataSource(environment);
		ApplicationRunner runner = application.migrateInReleaseOrder(runnerDataSource, environment);

		// 全量执行：与生产 one-shot job 同一路径（含 waitForDatabase 真实就绪）
		runner.run(null);
		assertThat(historyChecksums("flyway_schema_history")).isNotEmpty();
		assertThat(historyChecksums("marketplace_flyway_schema")).isNotEmpty();
		assertThat(historyChecksums("intelligence_flyway_schema")).isNotEmpty();

		// The operational SQL must execute against the real migrated schemas, not just
		// exist as a file.
		String diagnostics = Files.readString(Path.of("../../../scripts/acceptance/task-103-diagnostics.sql"));
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setReadOnly(true);
			int resultSets = 0;
			boolean result = statement.execute(diagnostics);
			while (result || statement.getUpdateCount() != -1) {
				assertThat(result).as("diagnostics may only return read results").isTrue();
				try (ResultSet rows = statement.getResultSet()) {
					int count = 0;
					while (rows.next())
						count++;
					assertThat(count).isLessThanOrEqualTo(100);
				}
				resultSets++;
				result = statement.getMoreResults();
			}
			assertThat(resultSets).isEqualTo(6);
		}

		// 幂等重跑：服务启动期 initMethod=migrate 语义，全部 no-op 且历史不变
		Map<String, Long> before = historyChecksums("flyway_schema_history");
		runner.run(null);
		assertThat(historyChecksums("flyway_schema_history")).isEqualTo(before);
	}

	@Test
	@DisplayName("打包载荷与各服务源 SQL 逐文件同字节（含 bootstrap）")
	void payloadMatchesSourceSqlByteForByte() throws Exception {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("database-bootstrap", "../database-bootstrap/src/main/resources/db/bootstrap");
		for (ServiceMigrations service : ReleaseMigratorApplication.ORDER) {
			sources.put(service.service(), "../" + service.service() + "/src/main/resources/db/migration");
		}
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		for (Map.Entry<String, String> source : sources.entrySet()) {
			Path payloadDir = Path.of(classLoader.getResource("db/migratedb/" + source.getKey()).toURI());
			Path sourceDir = Path.of(source.getValue());
			assertThat(Files.isDirectory(sourceDir)).as("%s 源目录应存在（测试须自模块目录运行）", sourceDir).isTrue();

			Map<String, byte[]> payload = new TreeMap<>();
			try (var stream = Files.list(payloadDir)) {
				for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
					payload.put(file.getFileName().toString(), Files.readAllBytes(file));
				}
			}
			Map<String, byte[]> origin = new TreeMap<>();
			try (var stream = Files.list(sourceDir)) {
				for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
					origin.put(file.getFileName().toString(), Files.readAllBytes(file));
				}
			}
			assertThat(payload.keySet()).as("%s 载荷文件名集合", source.getKey()).isEqualTo(origin.keySet());
			for (Map.Entry<String, byte[]> file : origin.entrySet()) {
				assertThat(payload.get(file.getKey())).as("%s/%s 与源同字节", source.getKey(), file.getKey())
						.isEqualTo(file.getValue());
			}
		}
	}

	@Test
	@DisplayName("基线 V50/V64/V84 旧库升级：数据保留、历史 checksum 不变、新表不编造事实")
	void upgradesPreTaskBookBaselinePreservingLegacyData() throws Exception {
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		migrateToBaseline(new ServiceMigrations("identity-service", "flyway_schema_history", false), "50");
		migrateToBaseline(new ServiceMigrations("marketplace-service", "marketplace_flyway_schema", true), "64");
		migrateAll(new ServiceMigrations("finance-service", "finance_flyway_schema", false));
		migrateAll(new ServiceMigrations("trust-service", "trust_flyway_schema", true));
		migrateToBaseline(new ServiceMigrations("intelligence-service", "intelligence_flyway_schema", false), "84");

		// 旧库遗留数据：双账号、旧多店长（同店两个 manager）、旧 closed 残留、历史部分退款。
		// account_prefix 为 V43 后的 NOT NULL 唯一列（存量库已回填），旧库 fixture 显式提供。
		execute("""
				INSERT INTO app_users (id, email, password_hash, display_name, role, status) VALUES
				('11111111-1111-4111-8111-111111111101', 'legacy-a@example.invalid', 'x', 'Legacy A', 'user', 'active'),
				('11111111-1111-4111-8111-111111111102', 'legacy-b@example.invalid', 'x', 'Legacy B', 'user', 'active')
				""");
		execute("INSERT INTO organization (id, owner_account_id, name, account_prefix) VALUES"
				+ " ('22222222-2222-4222-8222-222222222201', '11111111-1111-4111-8111-111111111101', 'Legacy Org',"
				+ " 'legacyorg')");
		execute("INSERT INTO store (id, organization_id, name) VALUES"
				+ " ('33333333-3333-4333-8333-333333333301', '22222222-2222-4222-8222-222222222201', 'Legacy Store')");
		execute("""
				INSERT INTO store_membership (id, store_id, account_id, role) VALUES
				('44444444-4444-4444-8444-444444444401', '33333333-3333-4333-8333-333333333301', '11111111-1111-4111-8111-111111111101', 'manager'),
				('44444444-4444-4444-8444-444444444402', '33333333-3333-4333-8333-333333333301', '11111111-1111-4111-8111-111111111102', 'manager')
				""");
		execute("INSERT INTO account_closure_request (id, account_id, status, retention_until, completed_at) VALUES"
				+ " ('55555555-5555-4555-8555-555555555501', '11111111-1111-4111-8111-111111111102',"
				+ " 'completed', '2026-01-01 00:00:00+00', '2026-01-08 00:00:00+00')");
		execute("INSERT INTO consumer_payment (id, order_ref, consumer_account_id, organization_id, amount_cents,"
				+ " channel, provider_ref, operation_id, status, refunded_at, refunded_amount_cents) VALUES"
				+ " ('66666666-6666-4666-8666-666666666601', 'legacy-order-1', '11111111-1111-4111-8111-111111111102',"
				+ " '22222222-2222-4222-8222-222222222201', 10000, 'sandbox', 'legacy-pay-1', 'legacy-op-pay-1',"
				+ " 'partially_refunded', '2026-02-01 00:00:00+00', 500)");
		execute("INSERT INTO consumer_payment_refund (id, order_ref, amount_cents, reason, operation_id, provider_ref, status)"
				+ " VALUES ('66666666-6666-4666-8666-666666666602', 'legacy-order-1', 500, 'legacy partial refund',"
				+ " 'legacy-op-refund-1', 'legacy-refund-1', 'succeeded')");

		Map<String, Long> identityHistoryBefore = historyChecksums("flyway_schema_history");
		Map<String, Long> marketplaceHistoryBefore = historyChecksums("marketplace_flyway_schema");
		assertThat(identityHistoryBefore).containsKey("50");
		assertThat(marketplaceHistoryBefore).containsKey("64");

		// 全量迁移：补上 V51/V65/V66/V85 等任务书 #103 新迁移（真实编排路径）。
		List<Integer> executed = new ArrayList<>();
		ReleaseMigratorApplication.migrateInReleaseOrder(service -> {
			int count = migrateAll(service);
			executed.add(count);
			return count;
		});
		assertThat(executed.get(1)).as("identity 应执行 V51").isEqualTo(1);
		assertThat(executed.get(2)).as("marketplace 应执行 V65/V66").isEqualTo(2);
		// tc105x-02-01（任务书 #105fix-1 C105X-02）：期望数从 intelligence 迁移目录动态推导
		// （baseline V84 之后的所有版本；2026-09-25 实测 6：V85~V90）——新增迁移不再打断本 IT。
		assertThat(executed.get(5)).as("intelligence 应执行 baseline V84 之后的全部新迁移（目录动态推导）")
				.isEqualTo(countIntelligenceMigrationsAfter(84));

		// 旧数据全部保留（数据保留，索引兼容由迁移成功本身证明）。
		assertThat(queryInt("SELECT count(*) FROM app_users WHERE email LIKE 'legacy-%'")).isEqualTo(2);
		assertThat(queryInt("SELECT count(*) FROM store_membership WHERE role = 'manager'"
				+ " AND store_id = '33333333-3333-4333-8333-333333333301'")).isEqualTo(2);
		assertThat(queryInt("SELECT count(*) FROM account_closure_request WHERE status = 'completed'")).isEqualTo(1);
		assertThat(queryInt("SELECT count(*) FROM consumer_payment_refund WHERE order_ref = 'legacy-order-1'"))
				.isEqualTo(1);

		// V51 状态域扩展：旧 closed 行的 'completed' 仍合法，新合法态 'preparing' 可插入。
		execute("INSERT INTO account_closure_request (id, account_id, status) VALUES"
				+ " ('55555555-5555-4555-8555-555555555502', '11111111-1111-4111-8111-111111111102', 'preparing')");

		// 新表存在且不编造业务事实（snapshot/操作表只能由服务运行期写入）。
		assertThat(queryInt("SELECT count(*) FROM commerce_settlement_fact")).isZero();
		assertThat(queryInt("SELECT count(*) FROM commerce_settlement_allocation_fact")).isZero();
		assertThat(queryInt("SELECT count(*) FROM engagement_exit_operation")).isZero();
		assertThat(queryInt("SELECT count(*) FROM intelligence_account_lifecycle")).isZero();
		assertThat(queryInt("SELECT count(*) FROM personal_data_erasure_manifest")).isZero();

		// 重放：全部 no-op，历史 checksum 一字不改（不 repair）。
		List<Integer> rerun = new ArrayList<>();
		ReleaseMigratorApplication.migrateInReleaseOrder(service -> {
			int count = migrateAll(service);
			rerun.add(count);
			return count;
		});
		assertThat(rerun).containsOnly(0);
		Map<String, Long> identityHistoryAfter = historyChecksums("flyway_schema_history");
		Map<String, Long> marketplaceHistoryAfter = historyChecksums("marketplace_flyway_schema");
		for (Map.Entry<String, Long> before : identityHistoryBefore.entrySet()) {
			assertThat(identityHistoryAfter).as("identity V%s checksum 不变").containsEntry(before.getKey(),
					before.getValue());
		}
		for (Map.Entry<String, Long> before : marketplaceHistoryBefore.entrySet()) {
			assertThat(marketplaceHistoryAfter).as("marketplace V%s checksum 不变").containsEntry(before.getKey(),
					before.getValue());
		}
	}

	@Test
	@DisplayName("任务书 #104 C104-03（TC104-03-06）：V86 库升级 V87——组织密钥数据不变、屏障例外生效、重放 no-op")
	void v86ToV87UpgradePreservesOrgKeysAndEnablesMaintenance() throws Exception {
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		ServiceMigrations intelligence = new ServiceMigrations("intelligence-service", "intelligence_flyway_schema",
				false);
		migrateToBaseline(intelligence, "86");

		// 真实库里组织密钥都是 creator 冻结前创建的——播种必须先插 key（V86 屏障下
		// 冻结账号不能再新建；key 的 INSERT 会自动注册 active gate 行），再推进
		// creator 的 lifecycle 状态到 frozen/erasing/erased（UPSERT 覆盖自动注册行）。
		execute("INSERT INTO ai_provider_key(organization_id, owner_account_id, capability, base_url, encrypted_key,"
				+ " masked_hint) VALUES"
				+ " ('legacy-org-frozen', 'legacy-creator-frozen', 'text', 'https://api.example', 'enc-f-1', 'sk-***f1'),"
				+ " ('legacy-org-erasing', 'legacy-creator-erasing', 'text', 'https://api.example', 'enc-e-1', 'sk-***e1'),"
				+ " ('legacy-org-erased', 'legacy-creator-erased', 'text', 'https://api.example', 'enc-r-1', 'sk-***r1'),"
				+ " (NULL, 'legacy-creator-erased', 'text', 'https://api.example', 'enc-p-1', 'sk-***p1')");
		execute("INSERT INTO intelligence_account_lifecycle(account_id, state) VALUES"
				+ " ('legacy-creator-frozen', 'frozen'), ('legacy-creator-erasing', 'erasing'),"
				+ " ('legacy-creator-erased', 'erased')"
				+ " ON CONFLICT (account_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()");

		Map<String, String> digestBefore = keyDigest();
		Map<String, Long> historyBefore = historyChecksums("intelligence_flyway_schema");
		assertThat(historyBefore).containsKey("86").doesNotContainKey("87");

		// 升级：intelligence 执行 baseline V86 之后的全部新迁移（tc105x-02-01 目录动态推导；
		// 2026-09-25 实测 4：V87~V90）。
		assertThat(migrateAll(intelligence)).as("baseline V86 之后的全部新迁移（目录动态推导）")
				.isEqualTo(countIntelligenceMigrationsAfter(86));

		// 数据摘要不变（归属/能力/启停/时间一字不动）。
		assertThat(keyDigest()).isEqualTo(digestBefore);

		// 屏障语义：三态 creator 的组织密钥白名单维护均放行；个人密钥仍被拒。
		execute("UPDATE ai_provider_key SET base_url = 'https://api.example/v2', updated_at = now()"
				+ " WHERE organization_id = 'legacy-org-frozen'");
		execute("UPDATE ai_provider_key SET enabled = false, updated_at = now()"
				+ " WHERE organization_id = 'legacy-org-erasing'");
		execute("UPDATE ai_provider_key SET encrypted_key = 'enc-r-2', key_version = 'v2', masked_hint = 'sk-***r2',"
				+ " updated_at = now() WHERE organization_id = 'legacy-org-erased'");
		assertThatThrownBy(() -> execute("UPDATE ai_provider_key SET model = 'blocked' WHERE organization_id IS NULL"))
				.hasMessageContaining("account_closure_barrier");
		// 越权形态不落回 NEW.owner 门：改归属在 active 账号上也拒绝。
		execute("INSERT INTO intelligence_account_lifecycle(account_id, state) VALUES ('legacy-active-admin', 'active')");
		assertThatThrownBy(() -> execute("UPDATE ai_provider_key SET owner_account_id = 'legacy-active-admin'"
				+ " WHERE organization_id = 'legacy-org-frozen'")).hasMessageContaining("account_closure_barrier");

		// 再次迁移 no-op；V1~V86 checksum 一字不改（不 repair）。
		assertThat(migrateAll(intelligence)).isZero();
		Map<String, Long> historyAfter = historyChecksums("intelligence_flyway_schema");
		for (Map.Entry<String, Long> before : historyBefore.entrySet()) {
			assertThat(historyAfter).as("intelligence V%s checksum 不变").containsEntry(before.getKey(),
					before.getValue());
		}
		assertThat(historyAfter).containsKey("87");
	}

	/** ai_provider_key 全行摘要（升级前后数据不变断言用）。 */
	private static Map<String, String> keyDigest() throws SQLException {
		Map<String, String> digest = new TreeMap<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT id::text, organization_id, owner_account_id,"
						+ " capability, provider, base_url, model, encrypted_key, key_version, masked_hint,"
						+ " enabled::text, created_at::text, updated_at::text FROM ai_provider_key ORDER BY id::text")) {
			while (rs.next()) {
				StringBuilder row = new StringBuilder();
				for (int i = 2; i <= 13; i++) {
					row.append(rs.getString(i)).append('|');
				}
				digest.put(rs.getString(1), row.toString());
			}
		}
		return digest;
	}

	/**
	 * tc105x-02-01（任务书 #105fix-1 C105X-02）：intelligence 新增迁移数动态推导——非 .sql 文件不计入，
	 * version 按数值比较非字典序。取打包载荷目录（payloadMatchesSourceSqlByteForByte 已证明与源逐字节
	 * 一致），不依赖测试工作目录相对路径。新增 intelligence 迁移时本期望自动跟随，无需同步计数。
	 */
	private static int countIntelligenceMigrationsAfter(long baselineVersion) throws Exception {
		Path payloadDir = Path.of(Thread.currentThread().getContextClassLoader()
				.getResource("db/migratedb/intelligence-service").toURI());
		try (var stream = Files.list(payloadDir)) {
			return (int) stream.map(p -> p.getFileName().toString())
					.filter(name -> name.endsWith(".sql") && name.matches("V\\d+__.*"))
					.mapToLong(name -> Long.parseLong(name.substring(1, name.indexOf("__"))))
					.filter(version -> version > baselineVersion).count();
		}
	}

	@Test
	@DisplayName("两个迁移 job 并发竞争同一库：Flyway 原生锁串行化，版本零重复")
	void concurrentMigratorsSerializeViaFlywayLock() throws Exception {
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		ServiceMigrations identity = new ServiceMigrations("identity-service", "flyway_schema_history", false);
		Callable<Integer> job = () -> migrateAll(identity);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = pool.submit(job);
			Future<Integer> second = pool.submit(job);
			int firstCount = first.get(120, TimeUnit.SECONDS);
			int secondCount = second.get(120, TimeUnit.SECONDS);
			// 锁串行化：两个 job 都成功，且总迁移次数恰好等于单次全量（无重复执行）；
			// 提交顺序不保证锁获取顺序，只断言「一者全量、另一者 no-op」。
			int executed = Math.max(firstCount, secondCount);
			int noop = Math.min(firstCount, secondCount);
			assertThat(executed).as("先拿到锁者执行全部迁移").isPositive();
			assertThat(noop).as("后到者在锁后成为 no-op").isZero();
		} finally {
			pool.shutdownNow();
		}

		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT version, count(*) FROM flyway_schema_history"
						+ " WHERE version IS NOT NULL GROUP BY version HAVING count(*) > 1")) {
			assertThat(rs.next()).as("并发下无重复版本").isFalse();
		}
	}

	@Test
	@DisplayName("只读账号：迁移明确失败且异常链不回显口令（TC103-23-04）")
	void readOnlyAccountFailsClearlyWithoutLeakingPassword() throws Exception {
		migrateAll(ReleaseMigratorApplication.BOOTSTRAP);
		execute("DROP ROLE IF EXISTS migrator_ro");
		execute("CREATE ROLE migrator_ro LOGIN PASSWORD 'ro-secret-value-99'");
		execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO migrator_ro");

		PGSimpleDataSource readOnly = new PGSimpleDataSource();
		readOnly.setURL(POSTGRES.getJdbcUrl());
		readOnly.setUser("migrator_ro");
		readOnly.setPassword("ro-secret-value-99");

		Throwable failure = catchFlywayFailure(() -> FlywayBootstrap
				.flyway(readOnly, "flyway_schema_history", "classpath:db/migratedb/identity-service", false).migrate());
		String chain = flattenMessages(failure);
		assertThat(chain).contains("permission denied");
		assertThat(chain).doesNotContain("ro-secret-value-99");
	}

	private static Throwable catchFlywayFailure(Runnable migration) {
		try {
			migration.run();
		} catch (RuntimeException failure) {
			return failure;
		}
		throw new AssertionError("只读账号迁移应失败，实际成功");
	}

	private static String flattenMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			messages.append(current.getMessage()).append('\n');
		}
		return messages.toString();
	}
}
