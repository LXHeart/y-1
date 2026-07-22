package com.grassland.identity.config;

import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 从 DATABASE_URL（JDBC 风格）派生 r2dbc 连接。
 * 仅在 identity.datasource.from-database-url=true 时激活（Docker/prod）。
 * 测试时不激活，由 spring.r2dbc.url + Spring Boot 自动配置处理。
 */
@Configuration
@ConditionalOnProperty(name = "identity.datasource.from-database-url", havingValue = "true")
public class R2dbcConnectionFactoryConfig {

    @Bean
    ConnectionFactory connectionFactory(Environment env) {
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
        return ConnectionFactories.get(r2dbcUrl);
    }

    static String toR2dbcUrl(String jdbcUrl) {
        String url = jdbcUrl.trim();
        if (url.startsWith("jdbc:")) url = url.substring("jdbc:".length());
        if (!url.startsWith("r2dbc:")) {
            if (url.startsWith("postgresql://") || url.startsWith("postgres://")) {
                url = "r2dbc:" + url;
            } else {
                url = "r2dbc:postgresql://" + url;
            }
        }
        int qi = url.indexOf('?');
        if (qi < 0) return url;
        String base = url.substring(0, qi);
        StringBuilder rb = new StringBuilder(base);
        String sep = "?";
        for (String p : url.substring(qi + 1).split("&")) {
            if (p.startsWith("channel_binding")) continue;
            if (p.startsWith("sslmode=")) p = "sslMode=" + p.substring("sslmode=".length());
            rb.append(sep).append(p);
            sep = "&";
        }
        return rb.toString();
    }
}
