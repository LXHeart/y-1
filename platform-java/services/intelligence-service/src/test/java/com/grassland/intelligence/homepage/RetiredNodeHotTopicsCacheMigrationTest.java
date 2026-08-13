package com.grassland.intelligence.homepage;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class RetiredNodeHotTopicsCacheMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void dropsOnlyTheRetiredCacheTable() throws Exception {
        DataSource dataSource = dataSource();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cached_hot_topics(id bigserial PRIMARY KEY, items jsonb NOT NULL)");
        }

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .table("intelligence_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "cached_hot_topics")).isFalse();
            assertThat(tableExists(statement, "intelligence_cached_hot_topics")).isTrue();
        }
    }

    private static boolean tableExists(Statement statement, String table) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
