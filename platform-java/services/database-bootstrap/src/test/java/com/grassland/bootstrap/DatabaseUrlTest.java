package com.grassland.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void parsesCredentialsPortAndSupportedQueryParameters() {
        DatabaseUrl result = DatabaseUrl.parse(
                "postgresql://user:pass@db.example:5433/app?sslmode=require&channel_binding=require");

        assertThat(result.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example:5433/app?sslmode=require");
        assertThat(result.user()).isEqualTo("user");
        assertThat(result.password()).isEqualTo("pass");
    }
}
