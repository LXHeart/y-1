package com.grassland.marketplace.config;

import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * marketplace DB 地基（Epic 4 Slice 4A）：从 {@code DATABASE_URL} 派生 JDBC {@link DataSource} 供 Flyway 用。
 * 复刻 identity 的 {@code DataSourceConfig}，关键差异：
 * <ul>
 *   <li>条件属性 {@code marketplace.datasource.from-database-url}（marketplace 自有开关）。</li>
 *   <li>Flyway 历史表 {@code marketplace_flyway_schema}（与 identity 的 {@code flyway_schema_history} 隔离，
 *       两服务共用 neon 集群 + public schema 但各自独立迁移历史）。</li>
 * </ul>
 * user/pass 用 setter（不拼进 URL，避免密码里的 {@code @} 歧义）；{@link PGSimpleDataSource} 无池（Flyway 仅启动期跑一次）。
 */
@Configuration
@ConditionalOnProperty(name = "marketplace.datasource.from-database-url", havingValue = "true")
public class MarketplaceDataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("marketplace-service needs DATABASE_URL for Flyway JDBC DataSource");
        }
        JdbcParts parts = parse(databaseUrl);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(parts.jdbcUrl());
        ds.setUser(parts.user());
        ds.setPassword(parts.password());
        return ds;
    }

    /**
     * 手动建 Flyway 并 migrate（{@code initMethod="migrate"}）。
     * {@code baseline-on-migrate} + {@code baseline-version=0} 兼容非空 public schema（identity/legacy 表已存在）。
     * {@code table=marketplace_flyway_schema} 与 identity 历史隔离。
     */
    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .table("marketplace_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    /**
     * 解析 DATABASE_URL（与 identity DataSourceConfig.parse 同逻辑）。java.net.URI 以最后一个 {@code @} 分隔
     * userInfo/host，故含 {@code @} 的密码（如 {@code Aa@111111}）正确归属 userInfo。
     * 兼容无显式端口（neon pooler）——省略端口段（PG JDBC 默认 5432）；保留 {@code sslmode}，丢弃 {@code channel_binding}。
     */
    static JdbcParts parse(String databaseUrl) {
        int schemeEnd = databaseUrl.indexOf("://");
        String rest = schemeEnd >= 0 ? databaseUrl.substring(schemeEnd + 3) : databaseUrl;
        URI uri = URI.create("http://" + rest);
        String userInfo = uri.getRawUserInfo();
        String user = "";
        String password = "";
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            password = colon < 0 ? "" : userInfo.substring(colon + 1);
        }
        String portPart = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        String queryPart = toJdbcQuery(uri.getRawQuery());
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + portPart + uri.getPath() + queryPart;
        return new JdbcParts(jdbcUrl, user, password);
    }

    private static String toJdbcQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        StringBuilder qb = new StringBuilder();
        String sep = "?";
        for (String param : rawQuery.split("&")) {
            if (param.startsWith("channel_binding")) {
                continue;
            }
            qb.append(sep).append(param);
            sep = "&";
        }
        return qb.toString();
    }

    record JdbcParts(String jdbcUrl, String user, String password) {}
}
