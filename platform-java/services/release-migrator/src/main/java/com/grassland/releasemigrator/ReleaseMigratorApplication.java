package com.grassland.releasemigrator;

import com.grassland.database.FlywayBootstrap;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 独立 release migration job（进度指南「生产切流前阻塞项 #1」编码）：五服务 Flyway 迁移按 固定顺序在一个 one-shot
 * 容器里执行——发布链先跑本 job 再滚动应用镜像，替代「各服务启动期 各自迁移」的隐式顺序。每个服务沿用其运行时同一份
 * {@link FlywayBootstrap} 口径 （历史表名 / baseline 0 / postgres transactional lock
 * 差异），字节级同一迁移文件 （打包期从各服务源目录拷贝），服务启动时的 initMethod=migrate 变为幂等 no-op。
 *
 * <p>
 * 顺序编码依据：identity 先行（共享身份表，是其余服务的断言信任根）；随后
 * marketplace/finance/trust/intelligence（各自独立历史表，互不依赖）。任一步失败
 * 立即退出非零——不做部分成功后的继续。
 */
@SpringBootApplication
public class ReleaseMigratorApplication {

	private static final Logger log = LoggerFactory.getLogger(ReleaseMigratorApplication.class);

	/**
	 * 第 0 步：database-bootstrap
	 * 共享表（app_users/session/user_settings/email_verification_codes）。 identity
	 * 等服务的迁移引用这些表——「legacy SQL + 各服务 Flyway 顺序」里的前置地基层。
	 */
	static final ServiceMigrations BOOTSTRAP = new ServiceMigrations("database-bootstrap",
			"database_bootstrap_flyway_schema", false);

	/** 迁移编排表：服务名 / Flyway 历史表 / 是否禁用 postgres transactional lock（与服务侧配置逐字一致）。 */
	static final List<ServiceMigrations> ORDER = List.of(
			new ServiceMigrations("identity-service", "flyway_schema_history", false),
			new ServiceMigrations("marketplace-service", "marketplace_flyway_schema", true),
			new ServiceMigrations("finance-service", "finance_flyway_schema", false),
			new ServiceMigrations("trust-service", "trust_flyway_schema", true),
			new ServiceMigrations("intelligence-service", "intelligence_flyway_schema", false));

	record ServiceMigrations(String service, String historyTable, boolean disablePostgresTransactionalLock) {
	}

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(ReleaseMigratorApplication.class, args);
		System.exit(SpringApplication.exit(context));
	}

	@Bean
	DataSource dataSource(Environment environment) {
		return FlywayBootstrap.dataSource(environment.getProperty("DATABASE_URL"), "release-migrator");
	}

	@Bean
	ApplicationRunner migrateInReleaseOrder(DataSource dataSource, Environment environment) {
		return (ApplicationArguments ignored) -> {
			waitForDatabase(dataSource, environment);
			runServiceMigrations(dataSource, BOOTSTRAP);
			for (ServiceMigrations service : ORDER) {
				runServiceMigrations(dataSource, service);
			}
			log.info("[release-migrator] 全部服务迁移完成（顺序：bootstrap → {}）",
					ORDER.stream().map(ServiceMigrations::service).toList());
		};
	}

	private static void runServiceMigrations(DataSource dataSource, ServiceMigrations service) {
		Flyway flyway = FlywayBootstrap.flyway(dataSource, service.historyTable(),
				"classpath:db/migratedb/" + service.service(), service.disablePostgresTransactionalLock());
		int executed = flyway.migrate().migrationsExecuted;
		log.info("[release-migrator] {} 迁移完成：本次执行 {} 个（历史表 {}）", service.service(), executed, service.historyTable());
	}

	/** 与 database-bootstrap 同款等待循环：DB 未就绪时有限重试而非立刻失败。 */
	private static void waitForDatabase(DataSource dataSource, Environment environment) throws SQLException {
		int maxAttempts = environment.getProperty("migrator.max-attempts", Integer.class, 30);
		long retryDelayMs = environment.getProperty("migrator.retry-delay-ms", Long.class, 2_000L);
		SQLException lastFailure = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try (Connection ignoredConnection = dataSource.getConnection()) {
				return;
			} catch (SQLException failure) {
				lastFailure = failure;
				if (attempt == maxAttempts) {
					throw failure;
				}
				try {
					Thread.sleep(retryDelayMs);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new SQLException("release-migrator interrupted while waiting for database", interrupted);
				}
			}
		}
		throw lastFailure;
	}
}
