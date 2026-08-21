package com.grassland.releasemigrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.database.FlywayBootstrap;
import com.grassland.releasemigrator.ReleaseMigratorApplication.ServiceMigrations;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 独立 release migration job 载荷验证：五服务迁移按 {@link ReleaseMigratorApplication#ORDER}
 * 的口径（历史表/锁差异）在空库上可全量执行，且幂等（服务启动期的 initMethod=migrate 变 no-op）。 迁移文件经打包期拷贝位于
 * classpath:db/migratedb/&lt;svc&gt;（与服务运行时同字节）。
 */
@Testcontainers
class ReleaseMigratorMigrationTest {

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

	@Test
	@DisplayName("五服务迁移按固定顺序在空库全量执行且幂等；历史表与锁口径与服务侧一致")
	void orderedMigrationsRunOnEmptyDatabaseAndAreIdempotent() {
		assertThat(ReleaseMigratorApplication.ORDER).extracting(ServiceMigrations::service).containsExactly(
				"identity-service", "marketplace-service", "finance-service", "trust-service", "intelligence-service");

		// 第 0 步共享地基（identity 迁移引用 app_users 等共享表）
		Flyway bootstrap = FlywayBootstrap.flyway(dataSource, ReleaseMigratorApplication.BOOTSTRAP.historyTable(),
				"classpath:db/migratedb/database-bootstrap",
				ReleaseMigratorApplication.BOOTSTRAP.disablePostgresTransactionalLock());
		assertThat(bootstrap.migrate().migrationsExecuted).isPositive();
		assertThat(bootstrap.migrate().migrationsExecuted).isZero();

		for (ServiceMigrations service : ReleaseMigratorApplication.ORDER) {
			Flyway flyway = FlywayBootstrap.flyway(dataSource, service.historyTable(),
					"classpath:db/migratedb/" + service.service(), service.disablePostgresTransactionalLock());
			int executed = flyway.migrate().migrationsExecuted;
			assertThat(executed).as("%s 空库应执行至少一个迁移", service.service()).isPositive();
			// 幂等：再跑一次 no-op（服务启动期的 initMethod=migrate 语义）
			assertThat(flyway.migrate().migrationsExecuted).as("%s 重复迁移应为 no-op", service.service()).isZero();
		}
	}

	@Test
	@DisplayName("五个独立 Flyway 历史表都存在（互不覆盖）")
	void eachServiceHasItsOwnHistoryTable() throws Exception {
		FlywayBootstrap.flyway(dataSource, ReleaseMigratorApplication.BOOTSTRAP.historyTable(),
				"classpath:db/migratedb/database-bootstrap",
				ReleaseMigratorApplication.BOOTSTRAP.disablePostgresTransactionalLock()).migrate();
		for (ServiceMigrations service : ReleaseMigratorApplication.ORDER) {
			FlywayBootstrap.flyway(dataSource, service.historyTable(), "classpath:db/migratedb/" + service.service(),
					service.disablePostgresTransactionalLock()).migrate();
		}
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet tables = statement.executeQuery("SELECT table_name FROM information_schema.tables"
						+ " WHERE table_schema = current_schema() AND table_name LIKE '%flyway%'"
						+ " ORDER BY table_name")) {
			var names = new java.util.ArrayList<String>();
			while (tables.next()) {
				names.add(tables.getString(1));
			}
			assertThat(names).contains("flyway_schema_history", "marketplace_flyway_schema", "finance_flyway_schema",
					"trust_flyway_schema", "intelligence_flyway_schema");
		}
	}
}
