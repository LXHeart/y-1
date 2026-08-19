package com.grassland.edge.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EdgeR2dbcConfigTest {

    @Test
    void convertsDatabaseUrlsAndNormalizesPostgresOptions() {
        assertThat(EdgeR2dbcConfig.toR2dbcUrl(
                "jdbc:postgresql://user:pass@db.example/app?sslmode=require&channel_binding=require"))
                .isEqualTo("r2dbc:postgresql://user:pass@db.example/app?sslMode=require");
    }

    @Test
    void disablesPreparedStatementCacheForPgBouncerEndpoints() {
        String url = "r2dbc:postgresql://user:pass@ep-pooler.example/app?sslMode=require";
        assertThat(EdgeR2dbcConfig.withPgBouncerOptions(url))
                .isEqualTo(url + "&preparedStatementCacheQueries=0");
        assertThat(EdgeR2dbcConfig.withPgBouncerOptions(
                url + "&preparedStatementCacheQueries=0"))
                .isEqualTo(url + "&preparedStatementCacheQueries=0");
        assertThat(EdgeR2dbcConfig.withPgBouncerOptions(
                url + "&preparedStatementCacheQueries=100"))
                .isEqualTo(url + "&preparedStatementCacheQueries=0");
    }
}
