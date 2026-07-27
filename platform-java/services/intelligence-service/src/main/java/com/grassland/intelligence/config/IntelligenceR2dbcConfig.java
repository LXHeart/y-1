package com.grassland.intelligence.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * intelligence R2DBC 连接（草场 intelligence Slice 1）：从 {@code DATABASE_URL} 派生 r2dbc {@link ConnectionFactory}。
 * 复刻 marketplace 的 {@code MarketplaceR2dbcConfig}；业务读写（outbox）走 R2DBC。
 * 测试时不激活（由 {@code spring.r2dbc.url} + Boot 自动配置处理）。
 */
@Configuration
@ConditionalOnProperty(name = "intelligence.datasource.from-database-url", havingValue = "true")
public class IntelligenceR2dbcConfig {

    @Bean
    ConnectionFactory connectionFactory(Environment env) {
        String explicit = env.getProperty("spring.r2dbc.url");
        String r2dbcUrl;
        if (explicit != null && !explicit.isBlank()) {
            r2dbcUrl = explicit;
        } else {
            String databaseUrl = env.getProperty("DATABASE_URL");
            if (databaseUrl == null || databaseUrl.isBlank()) {
                throw new IllegalStateException("intelligence-service needs DATABASE_URL or spring.r2dbc.url");
            }
            r2dbcUrl = toR2dbcUrl(databaseUrl);
        }
        return ConnectionFactories.get(r2dbcUrl);
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
}
