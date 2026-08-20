package com.grassland.database;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * 从 DATABASE_URL 派生无池 JDBC {@link DataSource} 与手动 {@link Flyway} bean 的共享工厂。
 *
 * <p>
 * 纯 R2DBC 应用（无 spring-jdbc / Hikari）下 Spring Boot 的
 * {@code FlywayAutoConfiguration} 不触发，各服务以 {@code @Bean(initMethod="migrate")}
 * 手动跑迁移——本工厂承接原五份逐字相同的 DataSourceConfig 骨架（2026-08-20
 * 下沉），服务侧只保留注解常量（条件属性名）与方言参数：
 * <ul>
 * <li>{@code historyTable}：五服务共库同 public schema，历史表互不相同 （identity 用 Flyway 默认名
 * {@code flyway_schema_history}，历史遗留）。</li>
 * <li>{@code locations}：跨服务 e2e 的 JVM classpath 上可能同时存在多个服务的迁移 jar （同路径
 * {@code db/migration} 会版本冲突），测试经服务侧属性覆盖为 filesystem 隔离目录。</li>
 * <li>{@code disablePostgresTransactionalLock}：marketplace/trust 的既有口径（共库多服务
 * 并发迁移时避开 advisory lock 语义差异），identity/finance/intelligence 保持默认。</li>
 * </ul>
 *
 * <p>
 * user/pass 不拼进 URL（避免密码里的 {@code @} 等特殊字符编码歧义），经 setter 单独设；
 * {@link PGSimpleDataSource} 无池即可——Flyway 只在启动期跑一次。
 */
public final class FlywayBootstrap {

	private FlywayBootstrap() {
	}

	/** 解析 DATABASE_URL 并构建无池 DataSource；缺失时以 serviceName 报 fail-fast 启动错误。 */
	public static DataSource dataSource(String databaseUrl, String serviceName) {
		if (databaseUrl == null || databaseUrl.isBlank()) {
			throw new IllegalStateException(serviceName + " needs DATABASE_URL for Flyway JDBC DataSource");
		}
		DatabaseUrls.JdbcParts parts = DatabaseUrls.parse(databaseUrl);
		PGSimpleDataSource ds = new PGSimpleDataSource();
		ds.setURL(parts.jdbcUrl());
		ds.setUser(parts.user());
		ds.setPassword(parts.password());
		return ds;
	}

	/**
	 * 手动建 Flyway。{@code baseline-on-migrate} + {@code baseline-version=0} 兼容 已有
	 * legacy 表的非空库（app_users/session 等）。
	 */
	public static Flyway flyway(DataSource dataSource, String historyTable, String locations,
			boolean disablePostgresTransactionalLock) {
		org.flywaydb.core.api.configuration.FluentConfiguration configure = Flyway.configure().dataSource(dataSource)
				.locations(locations).table(historyTable).baselineOnMigrate(true).baselineVersion("0");
		if (disablePostgresTransactionalLock) {
			configure.configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
		}
		return configure.load();
	}
}
