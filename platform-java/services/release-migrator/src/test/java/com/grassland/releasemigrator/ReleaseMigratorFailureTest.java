package com.grassland.releasemigrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.releasemigrator.ReleaseMigratorApplication.ServiceMigrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 任务书 #103 C103-23（TC103-23-01/03/04）：启动等待与编排的故障面。
 *
 * 全部为纯单元故障注入——不 mock Flyway 成功（真实迁移由 MigrationTest/UpgradeIT 用真实
 * PG 覆盖）；DB 故障经 {@link FlakyDataSource} 在连接层注入，配置经 {@link MockEnvironment}。
 */
class ReleaseMigratorFailureTest {

	/** 连接层故障注入：前 failFirst 次 getConnection 抛 SQLException，之后放行（可计数）。 */
	static class FlakyDataSource implements DataSource {

		private final int failFirst;
		private final Connection healthy = org.mockito.Mockito.mock(Connection.class);
		int attempts = 0;

		FlakyDataSource(int failFirst) {
			this.failFirst = failFirst;
		}

		@Override
		public Connection getConnection() throws SQLException {
			attempts += 1;
			if (attempts <= failFirst) {
				throw new SQLException("connection refused (attempt " + attempts + ")");
			}
			return healthy;
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return getConnection();
		}

		@Override
		public <T> T unwrap(Class<T> iface) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) {
			return false;
		}

		@Override
		public java.io.PrintWriter getLogWriter() {
			return null;
		}

		@Override
		public void setLogWriter(java.io.PrintWriter out) {
		}

		@Override
		public void setLoginTimeout(int seconds) {
		}

		@Override
		public int getLoginTimeout() {
			return 0;
		}

		@Override
		public java.util.logging.Logger getParentLogger() {
			return null;
		}
	}

	/** getConnection 时中断当前线程（等待循环随即进入 sleep → InterruptedException 路径）。 */
	static final class InterruptingDataSource extends FlakyDataSource {

		InterruptingDataSource() {
			super(Integer.MAX_VALUE);
		}

		@Override
		public Connection getConnection() throws SQLException {
			attempts += 1;
			Thread.currentThread().interrupt();
			throw new SQLException("connection refused");
		}
	}

	@Test
	@DisplayName("DB 晚就绪：有限重试后放行，尝试次数与配置一致")
	void waitsForLateDatabase() throws SQLException {
		FlakyDataSource dataSource = new FlakyDataSource(2);
		MockEnvironment environment = new MockEnvironment()
				.withProperty("migrator.max-attempts", "5")
				.withProperty("migrator.retry-delay-ms", "0");

		ReleaseMigratorApplication.waitForDatabase(dataSource, environment);

		assertThat(dataSource.attempts).isEqualTo(3);
	}

	@Test
	@DisplayName("DB 持续不可用：按 maxAttempts 有限重试后非零失败，不无限等待")
	void failsAfterBoundedRetries() {
		FlakyDataSource dataSource = new FlakyDataSource(Integer.MAX_VALUE);
		MockEnvironment environment = new MockEnvironment()
				.withProperty("migrator.max-attempts", "3")
				.withProperty("migrator.retry-delay-ms", "0");

		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(dataSource, environment))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("connection refused");
		assertThat(dataSource.attempts).isEqualTo(3);
	}

	@Test
	@DisplayName("线程中断：抛 SQLException 且中断位保留（不清除、不吞）")
	void propagatesInterruptWithFlagPreserved() {
		InterruptingDataSource dataSource = new InterruptingDataSource();
		MockEnvironment environment = new MockEnvironment()
				.withProperty("migrator.max-attempts", "10")
				.withProperty("migrator.retry-delay-ms", "50");

		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(dataSource, environment))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("interrupted")
				.hasCauseInstanceOf(InterruptedException.class);
		// Thread.interrupted() 读取并清除：true 证明等待循环保留了中断位。
		assertThat(Thread.interrupted()).isTrue();
	}

	@Test
	@DisplayName("非法配置：maxAttempts<1 / retryDelay<0 给出带属性名的失败，不是 NullPointerException")
	void rejectsInvalidConfiguration() {
		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(new FlakyDataSource(0),
				new MockEnvironment().withProperty("migrator.max-attempts", "0")))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("migrator.max-attempts");
		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(new FlakyDataSource(0),
				new MockEnvironment().withProperty("migrator.max-attempts", "-5")))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("migrator.max-attempts");
		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(new FlakyDataSource(0),
				new MockEnvironment().withProperty("migrator.retry-delay-ms", "-1")))
								.isInstanceOf(IllegalArgumentException.class)
								.hasMessageContaining("migrator.retry-delay-ms");
	}

	@Test
	@DisplayName("错误 DATABASE_URL：连接快速失败并按有限重试终止，异常消息不回显口令")
	void wrongDatabaseUrlFailsFastWithoutLeakingPassword() {
		javax.sql.DataSource badUrl = com.grassland.database.FlywayBootstrap.dataSource(
				"jdbc:postgresql://127.0.0.1:1/none?user=migrator&password=secret-value-xyz", "release-migrator");
		MockEnvironment environment = new MockEnvironment()
				.withProperty("migrator.max-attempts", "2")
				.withProperty("migrator.retry-delay-ms", "0");

		assertThatThrownBy(() -> ReleaseMigratorApplication.waitForDatabase(badUrl, environment))
				.isInstanceOf(SQLException.class)
				.hasMessageNotContaining("secret-value-xyz");
	}

	@Test
	@DisplayName("中途迁移失败：失败服务之后的域不执行（每步失败即退出）")
	void stopsAtFirstFailingService() {
		List<String> executed = new ArrayList<>();
		RuntimeException failure = new IllegalStateException("boom: finance downstream broke");

		assertThatThrownBy(() -> ReleaseMigratorApplication.migrateInReleaseOrder(service -> {
			executed.add(service.service());
			if ("finance-service".equals(service.service())) {
				throw failure;
			}
			return 0;
		})).isSameAs(failure);

		// bootstrap/identity/marketplace/finance 已执行；trust/intelligence 未被触碰。
		assertThat(executed).containsExactly("database-bootstrap", "identity-service", "marketplace-service",
				"finance-service");
	}

	@Test
	@DisplayName("成功路径：bootstrap → 五域固定顺序（TC103-23-01）")
	void runsServicesInReleaseOrder() {
		List<String> executed = new ArrayList<>();
		ReleaseMigratorApplication.migrateInReleaseOrder(service -> {
			executed.add(service.service());
			return 0;
		});
		assertThat(executed).containsExactly("database-bootstrap", "identity-service", "marketplace-service",
				"finance-service", "trust-service", "intelligence-service");
	}

	@Test
	@DisplayName("历史表与 transactional lock 配置逐字保留（不因重构漂移）")
	void keepsHistoryTableAndLockConfiguration() {
		assertThat(ReleaseMigratorApplication.BOOTSTRAP.historyTable()).isEqualTo("database_bootstrap_flyway_schema");
		assertThat(ReleaseMigratorApplication.BOOTSTRAP.disablePostgresTransactionalLock()).isFalse();
		assertThat(ReleaseMigratorApplication.ORDER).extracting(ServiceMigrations::historyTable)
				.containsExactly("flyway_schema_history", "marketplace_flyway_schema", "finance_flyway_schema",
						"trust_flyway_schema", "intelligence_flyway_schema");
		assertThat(ReleaseMigratorApplication.ORDER).extracting(ServiceMigrations::disablePostgresTransactionalLock)
				.containsExactly(false, true, false, true, false);
	}
}
