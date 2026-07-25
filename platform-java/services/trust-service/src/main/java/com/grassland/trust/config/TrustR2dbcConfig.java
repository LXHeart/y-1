package com.grassland.trust.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * trust R2DBC 连接（草场 Epic 6 Slice 6A）：从 {@code DATABASE_URL} 派生 r2dbc {@link ConnectionFactory}。
 * 复刻 finance 的 {@code FinanceR2dbcConfig}；条件属性 {@code trust.datasource.from-database-url}。
 */
@Configuration
@ConditionalOnProperty(name = "trust.datasource.from-database-url", havingValue = "true")
public class TrustR2dbcConfig {

    @Bean
    ConnectionFactory connectionFactory(Environment env) {
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
