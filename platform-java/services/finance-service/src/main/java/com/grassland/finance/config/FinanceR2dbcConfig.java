package com.grassland.finance.config;

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
 * finance R2DBC 连接（Epic 4 Slice 4D）：从 {@code DATABASE_URL} 派生 r2dbc {@link ConnectionFactory}。
 * 复刻 trust 的 {@code TrustR2dbcConfig}（含连接池）；条件属性 {@code finance.datasource.from-database-url}。
 * 业务读写走 R2DBC（{@code DatabaseClient} 由 Boot {@code R2dbcDataAutoConfiguration} 在 ConnectionFactory bean 存在时自动建）。
 * 测试时不激活（由 {@code spring.r2dbc.url} + Boot 自动配置处理）。
 *
 * <p><b>连接池</b>（GL-P3-PLATFORM-001，对齐 trust）：包一层 {@link ConnectionPool}——复用连接、借出前 {@code SELECT 1}
 * 剔除死连接、淘汰长空闲连接。默认 initial 2 / max 10，经 {@code FINANCE_DATASOURCE_POOL_*} env 可调。
 * finance 持资金账本，连接稳定性优先级最高。
 */
@Configuration
@ConditionalOnProperty(name = "finance.datasource.from-database-url", havingValue = "true")
public class FinanceR2dbcConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionFactory connectionFactory(
            Environment env,
            @Value("${finance.datasource.pool.initial-size:2}") int initialSize,
            @Value("${finance.datasource.pool.max-size:10}") int maxSize,
            @Value("${finance.datasource.pool.max-idle-seconds:120}") long maxIdleSeconds,
            @Value("${finance.datasource.pool.max-acquire-seconds:20}") long maxAcquireSeconds) {
        String explicit = env.getProperty("spring.r2dbc.url");
        String r2dbcUrl;
        if (explicit != null && !explicit.isBlank()) {
            r2dbcUrl = explicit;
        } else {
            String databaseUrl = env.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalStateException("finance-service needs DATABASE_URL or spring.r2dbc.url");
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
                .name("finance-r2dbc-pool")
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
