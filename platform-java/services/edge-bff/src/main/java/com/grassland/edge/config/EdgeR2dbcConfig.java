package com.grassland.edge.config;

import com.grassland.edge.internalassertion.EdgeCookieSigner;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * edge-bff 直读 session 表的只读 R2DBC 连接（HLD 7.4「BFF 直读 session 表」）。
 *
 * <p>仅 SELECT session/app_users/identity_session（edge 不写身份库）。从 {@code DATABASE_URL} 派生 r2dbc url，
 * 逻辑同 {@code identity-service} 的 {@code R2dbcConnectionFactoryConfig.toR2dbcUrl}：前缀改 {@code r2dbc:}，
 * {@code sslmode→sslMode}，丢弃 JDBC 不识别的 {@code channel_binding}。
 *
 * <p>按 {@code edge.identity.from-database-url} opt-in：未配置 DATABASE_URL 的环境（如纯单测）不激活，
 * edge-bff 仍作透明代理运行（只是不签发断言）。启用时使用有界池，借出前执行远端 {@code SELECT 1}
 * 淘汰已被服务端回收的连接，并在应用关闭时释放池资源。
 */
@Configuration
@ConditionalOnProperty(name = "edge.identity.from-database-url", havingValue = "true")
public class EdgeR2dbcConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionFactory connectionFactory(
            Environment env,
            @Value("${edge.identity.datasource.pool.initial-size:2}") int initialSize,
            @Value("${edge.identity.datasource.pool.max-size:10}") int maxSize,
            @Value("${edge.identity.datasource.pool.max-idle-seconds:120}") long maxIdleSeconds,
            @Value("${edge.identity.datasource.pool.max-life-seconds:600}") long maxLifeSeconds,
            @Value("${edge.identity.datasource.pool.max-acquire-seconds:20}") long maxAcquireSeconds) {
        String explicit = env.getProperty("spring.r2dbc.url");
        String r2dbcUrl;
        if (explicit != null && !explicit.isBlank()) {
            r2dbcUrl = explicit;
        } else {
            String databaseUrl = env.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalStateException("edge-bff needs DATABASE_URL or spring.r2dbc.url for session-table read");
            }
            r2dbcUrl = toR2dbcUrl(databaseUrl);
        }
        ConnectionFactory delegate = ConnectionFactories.get(withPgBouncerOptions(r2dbcUrl));
        return new ConnectionPool(ConnectionPoolConfiguration.builder(delegate)
                .initialSize(requirePositive(initialSize, "initial-size"))
                .maxSize(requirePositive(maxSize, "max-size"))
                .validationQuery("SELECT 1")
                .validationDepth(ValidationDepth.REMOTE)
                .maxIdleTime(Duration.ofSeconds(requirePositive(maxIdleSeconds, "max-idle-seconds")))
                .maxLifeTime(Duration.ofSeconds(requirePositive(maxLifeSeconds, "max-life-seconds")))
                .maxAcquireTime(Duration.ofSeconds(requirePositive(maxAcquireSeconds, "max-acquire-seconds")))
                .name("edge-r2dbc-pool")
                .build());
    }

    /** 自建 DatabaseClient（不依赖 R2dbcDataAutoConfiguration 的 @ConditionalOnBean 顺序，保证 resolver 能注入）。 */
    @Bean
    DatabaseClient databaseClient(ConnectionFactory connectionFactory) {
        return DatabaseClient.create(connectionFactory);
    }

    @Bean
    EdgeCookieSigner edgeCookieSigner(@Value("${identity.session.secret:}") String secret) {
        return new EdgeCookieSigner(secret);
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
        if (qi < 0) {
            return url;
        }
        String base = url.substring(0, qi);
        StringBuilder rebuilt = new StringBuilder(base);
        String sep = "?";
        for (String param : url.substring(qi + 1).split("&")) {
            if (param.startsWith("channel_binding")) {
                continue;
            }
            if (param.startsWith("sslmode=")) {
                param = "sslMode=" + param.substring("sslmode=".length());
            }
            rebuilt.append(sep).append(param);
            sep = "&";
        }
        return rebuilt.toString();
    }

    static String withPgBouncerOptions(String r2dbcUrl) {
        int qi = r2dbcUrl.indexOf('?');
        String base = qi < 0 ? r2dbcUrl : r2dbcUrl.substring(0, qi);
        if (!base.contains("-pooler.")) {
            return r2dbcUrl;
        }
        String query = qi < 0 ? "" : r2dbcUrl.substring(qi + 1);
        String retained = Arrays.stream(query.split("&"))
                .filter(param -> !param.isBlank())
                .filter(param -> !param.startsWith("preparedStatementCacheQueries="))
                .collect(Collectors.joining("&"));
        return base + "?" + (retained.isBlank() ? "" : retained + "&")
                + "preparedStatementCacheQueries=0";
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("edge datasource " + name + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("edge datasource " + name + " must be positive");
        }
        return value;
    }
}
