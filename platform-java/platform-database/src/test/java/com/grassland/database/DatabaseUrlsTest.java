package com.grassland.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 原五份拷贝中曾以生产事故驱动的解析要点（neon 无端口 / 密码含 @ / 查询串过滤）逐项锁定。 */
class DatabaseUrlsTest {

    @Test
    void parsesUserPasswordHostPortAndDatabase() {
        var parts = DatabaseUrls.parse("postgres://lxh:secret@db.example.com:55432/grassland");
        assertThat(parts.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:55432/grassland");
        assertThat(parts.user()).isEqualTo("lxh");
        assertThat(parts.password()).isEqualTo("secret");
    }

    @Test
    void omitsPortSegmentForPortlessNeonPoolerUrlInsteadOfWritingMinusOne() {
        var parts = DatabaseUrls.parse("postgres://lxh:secret@pooler.neon.tech/grassland");
        assertThat(parts.jdbcUrl()).isEqualTo("jdbc:postgresql://pooler.neon.tech/grassland");
    }

    @Test
    void keepsPasswordContainingAtSign() {
        var parts = DatabaseUrls.parse("postgres://lxh:Aa@111111@db.example.com/grassland");
        assertThat(parts.user()).isEqualTo("lxh");
        assertThat(parts.password()).isEqualTo("Aa@111111");
        assertThat(parts.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com/grassland");
    }

    @Test
    void keepsSslmodeButDropsChannelBindingQueryParams() {
        var parts = DatabaseUrls.parse(
                "postgres://lxh:secret@db.example.com/grassland?sslmode=require&channel_binding=require");
        assertThat(parts.jdbcUrl())
                .isEqualTo("jdbc:postgresql://db.example.com/grassland?sslmode=require");
    }

    @Test
    void toleratesMissingUserInfoAndEmptyQuery() {
        var parts = DatabaseUrls.parse("postgres://db.example.com/grassland");
        assertThat(parts.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com/grassland");
        assertThat(parts.user()).isEmpty();
        assertThat(parts.password()).isEmpty();
    }
}
