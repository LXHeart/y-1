package com.grassland.trust.config;

import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * trust DB 地基（草场 Epic 6 Slice 6A）：从 {@code DATABASE_URL} 派生 JDBC {@link DataSource} 供 Flyway 用。
 * 复刻 finance 的 {@code FinanceDataSourceConfig}，差异：条件属性 {@code trust.datasource.from-database-url}；
 * Flyway 历史表 {@code trust_flyway_schema}（与 identity/marketplace/finance 历史隔离，四服务共用 neon public schema）。
 */
@Configuration
@ConditionalOnProperty(name = "trust.datasource.from-database-url", havingValue = "true")
public class TrustDataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("trust-service needs DATABASE_URL for Flyway JDBC DataSource");
        }
        JdbcParts parts = parse(databaseUrl);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(parts.jdbcUrl());
        ds.setUser(parts.user());
        ds.setPassword(parts.password());
        return ds;
    }

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .table("trust_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

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
