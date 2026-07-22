package com.grassland.identity.config;

import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 从 DATABASE_URL（postgresql://user:pass@host:port/db）派生 JDBC {@link DataSource}，供 Flyway 迁移用。
 *
 * <p>与 {@link R2dbcConnectionFactoryConfig} 并存：业务读写走 R2DBC（{@code ConnectionFactory}），
 * schema 迁移走 JDBC（Flyway 不支持 R2DBC）。两者同 {@code identity.datasource.from-database-url} 条件激活。
 *
 * <p>用 {@link PGSimpleDataSource}（无连接池）即可——Flyway 只在启动期跑一次迁移，无需池化。
 * user/pass 不拼进 URL（避免密码里的 {@code @} 等特殊字符的编码歧义），用 setter 单独设。
 */
@Configuration
@ConditionalOnProperty(name = "identity.datasource.from-database-url", havingValue = "true")
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("identity-service needs DATABASE_URL for Flyway JDBC DataSource");
        }
        JdbcParts parts = parse(databaseUrl);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(parts.jdbcUrl());
        ds.setUser(parts.user());
        ds.setPassword(parts.password());
        return ds;
    }

    /**
     * 手动建 Flyway 并在 bean 初始化时 migrate（{@code initMethod="migrate"}）。
     *
     * <p>Spring Boot {@code FlywayAutoConfiguration} 在纯 R2DBC 应用（无 spring-jdbc / Hikari）下不触发，
     * 故手动跑迁移。与 {@link #dataSource} 同 {@code from-database-url} 条件，确保 Docker / IT 都跑。
     * {@code baseline-on-migrate} + {@code baseline-version=0} 兼容已有 legacy 表（app_users/session）的非空库。
     */
    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    /**
     * 解析 DATABASE_URL。java.net.URI 以最后一个 {@code @} 分隔 userInfo 与 host，
     * 因此含 {@code @} 的密码（如 {@code Aa@111111}）能正确归属 userInfo：{@code lxh:Aa@111111} → user=lxh, pass=Aa@111111。
     *
     * <p>兼容无显式端口的 URL（如 neon pooler：{@code ...neon.tech/db}）——省略端口段，PG JDBC 默认 5432，
     * 不再写出 {@code :-1}。保留查询串中的 {@code sslmode}（neon 要求 SSL），丢弃 JDBC 不识别的 {@code channel_binding}
     * （JDBC 用 {@code channelBinding}；如需可另行配置）。
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

    /** 保留 sslmode 等 JDBC 识别的查询参数，丢弃 channel_binding（JDBC 用 channelBinding，此名不识别）。 */
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
