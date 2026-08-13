package com.grassland.finance.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class RetiredNodeCreditTablesMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetSchema() throws Exception {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("CREATE TABLE app_users(id uuid PRIMARY KEY)");
        }
    }

    @Test
    void copiesLegacyRowsBeforeDroppingRetiredTables() throws Exception {
        DataSource dataSource = dataSource();
        seedLegacyTables(dataSource, true);

        migrate(dataSource).migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "user_credits")).isFalse();
            assertThat(tableExists(statement, "credit_transactions")).isFalse();
            assertThat(singleLong(statement, "SELECT count(*) FROM credits_account")).isEqualTo(1);
            assertThat(singleLong(statement, "SELECT count(*) FROM credits_transaction")).isEqualTo(1);
        }
    }

    @Test
    void copiesOlderLegacyTransactionsWithoutOperationId() throws Exception {
        DataSource dataSource = dataSource();
        seedLegacyTables(dataSource, false);

        migrate(dataSource).migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "credit_transactions")).isFalse();
            assertThat(singleLong(statement, "SELECT count(*) FROM credits_transaction WHERE operation_id IS NULL"))
                    .isEqualTo(1);
        }
    }

    @Test
    void refusesToDropWhenTheBackfillIsIncomplete() throws Exception {
        DataSource dataSource = dataSource();
        seedLegacyTables(dataSource, true);
        migrate(dataSource, "12").migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO app_users VALUES ('00000000-0000-0000-0000-000000000002')");
            statement.execute("INSERT INTO user_credits VALUES "
                    + "('00000000-0000-0000-0000-000000000002', 3, 3, 0, now(), now())");
        }

        assertThatThrownBy(() -> migrate(dataSource).migrate())
                .hasMessageContaining("cannot retire user_credits");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(tableExists(statement, "user_credits")).isTrue();
        }
    }

    private static void seedLegacyTables(DataSource dataSource, boolean withOperationId) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO app_users VALUES ('00000000-0000-0000-0000-000000000001')");
            statement.execute("""
                    CREATE TABLE user_credits(
                        user_id uuid PRIMARY KEY,
                        balance integer NOT NULL,
                        total_earned integer NOT NULL,
                        total_spent integer NOT NULL,
                        created_at timestamptz NOT NULL,
                        updated_at timestamptz NOT NULL
                    )
                    """);
            String operationIdColumn = withOperationId ? "operation_id text," : "";
            statement.execute("""
                    CREATE TABLE credit_transactions(
                        id uuid PRIMARY KEY,
                        user_id uuid NOT NULL,
                        amount integer NOT NULL,
                        balance_after integer NOT NULL,
                        type text NOT NULL,
                        feature text,
                        note text,
                        %s
                        created_at timestamptz NOT NULL
                    )
                    """.formatted(operationIdColumn));
            statement.execute("""
                    INSERT INTO user_credits VALUES
                    ('00000000-0000-0000-0000-000000000001', 8, 10, 2, now(), now())
                    """);
            String operationIdValue = withOperationId ? "'consume:seed'," : "";
            statement.execute("""
                    INSERT INTO credit_transactions VALUES
                    ('10000000-0000-0000-0000-000000000001',
                     '00000000-0000-0000-0000-000000000001', -2, 8, 'consume',
                     'video_analysis', 'seed', %s now())
                    """.formatted(operationIdValue));
        }
    }

    private static Flyway migrate(DataSource dataSource) {
        return migrate(dataSource, null);
    }

    private static Flyway migrate(DataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .table("finance_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static boolean tableExists(Statement statement, String table) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT to_regclass('public." + table + "') IS NOT NULL")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static long singleLong(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
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
