package com.grassland.trust.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DATABASE_URL → r2dbc URL 转换。
 *
 * <p>重点锁 PgBouncer 判定：neon 的 {@code -pooler} 端点是 PgBouncer（transaction 模式），
 * 事务间复用后端连接使服务端 prepared statement 失效 → 连接被强制关闭
 * （现象：前几次请求成功、连接复用后反复 {@code 08006 Connection unexpectedly closed}）。
 * 故对 pooler 端点必须关闭 prepared statement 缓存。
 */
class TrustR2dbcConfigTest {

    @Test
    void poolerEndpointDisablesPreparedStatementCache() {
        String url = TrustR2dbcConfig.toR2dbcUrl(
                "postgresql://u:p@ep-x-pooler.c-7.us-east-1.aws.neon.tech/neondb?sslmode=require");

        assertThat(url).contains("preparedStatementCacheQueries=0");
        assertThat(url).contains("sslMode=require");   // sslmode → sslMode（r2dbc 拼写）
    }

    @Test
    void directEndpointKeepsPreparedStatements() {
        // 直连端点无 PgBouncer，prepared statement 可用（性能更好），不应被关掉
        String url = TrustR2dbcConfig.toR2dbcUrl(
                "postgresql://u:p@ep-x.c-7.us-east-1.aws.neon.tech/neondb?sslmode=require");

        assertThat(url).doesNotContain("preparedStatementCacheQueries");
    }

    @Test
    void poolerEndpointWithoutQueryStringStillGetsFlag() {
        String url = TrustR2dbcConfig.toR2dbcUrl("postgresql://u:p@ep-x-pooler.aws.neon.tech/neondb");

        assertThat(url).contains("?preparedStatementCacheQueries=0");
    }

    @Test
    void existingPreparedStatementParamIsNormalized() {
        // 入参已带该项时不重复追加（避免 ?a=1&preparedStatementCacheQueries=5&preparedStatementCacheQueries=0）
        String url = TrustR2dbcConfig.toR2dbcUrl(
                "postgresql://u:p@ep-x-pooler.aws.neon.tech/neondb?preparedStatementCacheQueries=250");

        assertThat(url).containsOnlyOnce("preparedStatementCacheQueries");
        assertThat(url).contains("preparedStatementCacheQueries=0");
    }

    @Test
    void dropsChannelBindingWhichR2dbcRejects() {
        String url = TrustR2dbcConfig.toR2dbcUrl(
                "postgresql://u:p@ep-x-pooler.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertThat(url).doesNotContain("channel_binding");
    }

    @Test
    void addsR2dbcSchemeWhenMissing() {
        assertThat(TrustR2dbcConfig.toR2dbcUrl("postgresql://u:p@host/db")).startsWith("r2dbc:postgresql://");
        assertThat(TrustR2dbcConfig.toR2dbcUrl("jdbc:postgresql://u:p@host/db")).startsWith("r2dbc:postgresql://");
    }
}
