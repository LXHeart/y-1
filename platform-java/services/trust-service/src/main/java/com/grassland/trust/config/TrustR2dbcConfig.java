package com.grassland.trust.config;

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
 * trust R2DBC 连接（草场 Epic 6 Slice 6A）：从 {@code DATABASE_URL} 派生 r2dbc {@link ConnectionFactory}。
 * 条件属性 {@code trust.datasource.from-database-url}。
 *
 * <p><b>连接池</b>：原实现直接返回 {@code ConnectionFactories.get(url)} —— <b>裸工厂，无池</b>，
 * 每次查询都新建 TCP + SSL 握手，高频查询（如 outbox 轮询、workflow activity）开销与失败面都大。
 * 现包一层 {@link ConnectionPool} 复用连接；{@code validationQuery} 在借出前剔除已被服务端回收的
 * 死连接；{@code maxIdleTime} 主动淘汰长空闲连接。
 *
 * <p><b>⚠️ 定性说明</b>：本池化是<b>合理加固</b>，但<b>并非</b> 2026-07 那次 neon 连接故障的原因。
 * 那次故障经对照实验定位为 <b>neon 侧/网络链路问题</b>——绕开本服务、从宿主机直连 neon
 * 同样 3 次 2 失败、成功一次耗时 15.9s。加池与调驱动参数都无法改善。切勿因本注释
 * 误以为连接问题已由池化解决。
 */
@Configuration
@ConditionalOnProperty(name = "trust.datasource.from-database-url", havingValue = "true")
public class TrustR2dbcConfig {

    @Bean(destroyMethod = "dispose")
    ConnectionFactory connectionFactory(
            Environment env,
            @Value("${trust.datasource.pool.initial-size:2}") int initialSize,
            @Value("${trust.datasource.pool.max-size:10}") int maxSize,
            @Value("${trust.datasource.pool.max-idle-seconds:120}") long maxIdleSeconds,
            @Value("${trust.datasource.pool.max-acquire-seconds:20}") long maxAcquireSeconds) {
        String explicit = env.getProperty("spring.r2dbc.url");
        String r2dbcUrl;
        if (explicit != null && !explicit.isBlank()) {
            r2dbcUrl = explicit;
        } else {
            String databaseUrl = env.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalStateException("trust-service needs DATABASE_URL or spring.r2dbc.url");
            }
            r2dbcUrl = toR2dbcUrl(databaseUrl);
        }
        ConnectionFactory delegate = ConnectionFactories.get(r2dbcUrl);
        return new ConnectionPool(ConnectionPoolConfiguration.builder(delegate)
                .initialSize(initialSize)
                .maxSize(maxSize)
                // 借出前跑一次轻量查询：neon 会主动断空闲连接，池内可能存着已死的连接，
                // 不校验就会把死连接交给调用方 → 表现为随机 08006/连接失败。
                .validationQuery("SELECT 1")
                .validationDepth(ValidationDepth.REMOTE)
                .maxIdleTime(Duration.ofSeconds(maxIdleSeconds))
                .maxAcquireTime(Duration.ofSeconds(maxAcquireSeconds))
                .name("trust-r2dbc-pool")
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
                    continue;  // 后者由下方按端点类型统一决定
                }
                if (param.startsWith("sslmode=")) {
                    param = "sslMode=" + param.substring("sslmode=".length());
                }
                rebuilt.append(sep).append(param);
                sep = "&";
            }
        }
        if (isPgBouncerEndpoint(base)) {
            // neon 的 -pooler 端点是 PgBouncer（transaction 模式）：事务间复用后端连接，
            // 服务端 prepared statement 随之失效。关闭缓存后走 unnamed 语句，与 transaction pooling 兼容。
            // 注：这是与 PgBouncer 配套的正确设置，但同样不是 2026-07 neon 故障的原因（见类注释）。
            rebuilt.append(sep).append("preparedStatementCacheQueries=0");
        }
        return rebuilt.toString();
    }

    /** neon 的连接池端点主机名含 {@code -pooler}（PgBouncer）。直连端点无此后缀，可用 prepared statement。 */
    static boolean isPgBouncerEndpoint(String urlBase) {
        return urlBase.contains("-pooler.");
    }
}
