package com.grassland.identity.config;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 从 DATABASE_URL（JDBC 风格）派生 r2dbc 连接。
 * 仅在 identity.datasource.from-database-url=true 时激活（Docker/prod）。
 * 测试时不激活，由 spring.r2dbc.url + Spring Boot 自动配置处理。
 *
 * <p><b>连接池</b>（GL-P3-PLATFORM-001，对齐 trust）：包一层 {@link ConnectionPool}——复用连接、借出前 {@code SELECT 1}
 * 剔除死连接、淘汰长空闲连接。默认 initial 2 / max 10，经 {@code IDENTITY_DATASOURCE_POOL_*} env 可调。
 */
@Configuration
@ConditionalOnProperty(name = "identity.datasource.from-database-url", havingValue = "true")
public class R2dbcConnectionFactoryConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionFactory connectionFactory(
            Environment env,
            @Value("${identity.datasource.pool.initial-size:2}") int initialSize,
            @Value("${identity.datasource.pool.max-size:10}") int maxSize,
            @Value("${identity.datasource.pool.max-idle-seconds:120}") long maxIdleSeconds,
            @Value("${identity.datasource.pool.max-acquire-seconds:20}") long maxAcquireSeconds) {
        String explicit = env.getProperty("spring.r2dbc.url");
        String r2dbcUrl;
        if (explicit != null && !explicit.isBlank()) {
            r2dbcUrl = explicit;
        } else {
            String databaseUrl = env.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalStateException("identity-service needs DATABASE_URL or spring.r2dbc.url");
            }
            r2dbcUrl = toR2dbcUrl(databaseUrl);
        }
        ConnectionFactory delegate = ConnectionFactories.get(r2dbcUrl);
        return new ConnectionPool(ConnectionPoolConfiguration.builder(delegate)
                .initialSize(initialSize)
                .maxSize(maxSize)
                .validationQuery("SELECT 1")
                .validationDepth(ValidationDepth.REMOTE)
                .maxIdleTime(Duration.ofSeconds(maxIdleSeconds))
                .maxAcquireTime(Duration.ofSeconds(maxAcquireSeconds))
                .name("identity-r2dbc-pool")
                .build());
    }

    static String toR2dbcUrl(String jdbcStyleUrl) {
        String url = jdbcStyleUrl.trim();
        if (url.startsWith("jdbc:")) {
            url = url.substring("jdbc:".length());
        }
        if (!url.startsWith("r2dbc:")) {
            url = (url.startsWith("postgresql://") || url.startsWith("postgres://"))
                    ? "r2dbc:" + url
                    : "r2dbc:postgresql://" + url;
        }
        int qi = url.indexOf('?');
        String base = qi < 0 ? url : url.substring(0, qi);
        StringBuilder rebuilt = new StringBuilder(base);
        String sep = "?";
        if (qi >= 0) {
            for (String param : url.substring(qi + 1).split("&")) {
                if (param.startsWith("channel_binding")
                        || param.startsWith("preparedStatementCacheQueries")) {
                    continue;
                }
                if (param.startsWith("sslmode=")) {
                    param = "sslMode=" + param.substring("sslmode=".length());
                }
                rebuilt.append(sep).append(param);
                sep = "&";
            }
        }
        if (isPgBouncerEndpoint(base)) {
            rebuilt.append(sep).append("preparedStatementCacheQueries=0");
        }
        return rebuilt.toString();
    }

    static boolean isPgBouncerEndpoint(String urlBase) {
        return urlBase.contains("-pooler.");
    }
}
