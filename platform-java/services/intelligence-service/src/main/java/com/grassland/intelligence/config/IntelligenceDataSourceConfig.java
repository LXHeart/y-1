package com.grassland.intelligence.config;

import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * intelligence DB 地基（草场 intelligence Slice 1）：从 {@code DATABASE_URL} 派生 JDBC {@link DataSource} 供 Flyway 用。
 * 复刻 marketplace 的 {@code MarketplaceDataSourceConfig}，关键差异：
 * <ul>
 *   <li>条件属性 {@code intelligence.datasource.from-database-url}。</li>
 *   <li>Flyway 历史表 {@code intelligence_flyway_schema}（与 identity/marketplace/finance/trust 各自独立）。</li>
 * </ul>
 * user/pass 用 setter（不拼进 URL，避免密码里的 {@code @} 歧义）；{@link PGSimpleDataSource} 无池（Flyway 仅启动期跑一次）。
 */
@Configuration
@ConditionalOnProperty(name = "intelligence.datasource.from-database-url", havingValue = "true")
public class IntelligenceDataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("intelligence-service needs DATABASE_URL for Flyway JDBC DataSource");
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
                .table("intelligence_flyway_schema")
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
