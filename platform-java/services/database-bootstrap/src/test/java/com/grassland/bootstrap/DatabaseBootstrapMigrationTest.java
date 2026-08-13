package com.grassland.bootstrap;

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

class DatabaseBootstrapMigrationTest {

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
        }
    }

    @Test
    void bootstrapsAnEmptyDatabaseAndIsIdempotent() throws Exception {
        DataSource dataSource = dataSource();
        Flyway flyway = flyway(dataSource);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        new DatabaseSchemaVerifier(dataSource).verify();
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_default FROM information_schema.columns
                     WHERE table_schema='public' AND table_name='app_users' AND column_name='role'
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).contains("user");
        }
    }

    @Test
    void upgradesExistingNodeOwnedBaseTablesInPlace() throws Exception {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("""
                    CREATE TABLE app_users (
                        id uuid PRIMARY KEY,
                        email text NOT NULL UNIQUE,
                        password_hash text NOT NULL,
                        display_name text,
                        role text NOT NULL DEFAULT 'admin',
                        status text NOT NULL DEFAULT 'active',
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now(),
                        last_login_at timestamptz
                    )
                    """);
            statement.execute("""
                    CREATE TABLE session (
                        sid varchar PRIMARY KEY,
                        sess json NOT NULL,
                        expire timestamp(6) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE user_settings (
                        id uuid PRIMARY KEY,
                        user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                        settings_type text NOT NULL,
                        settings_json jsonb NOT NULL,
                        version integer NOT NULL DEFAULT 1,
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now(),
                        CONSTRAINT user_settings_type_check
                            CHECK (settings_type IN ('analysis', 'homepage')),
                        CONSTRAINT user_settings_unique_user_type UNIQUE (user_id, settings_type)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE email_verification_codes (
                        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                        email text NOT NULL,
                        code text NOT NULL,
                        used boolean NOT NULL DEFAULT false,
                        expires_at timestamptz NOT NULL,
                        created_at timestamptz NOT NULL DEFAULT now()
                    )
                    """);
        }

        DataSource dataSource = dataSource();
        assertThat(flyway(dataSource).migrate().migrationsExecuted).isEqualTo(1);
        new DatabaseSchemaVerifier(dataSource).verify();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO app_users(id, email, password_hash)
                    VALUES (gen_random_uuid(), 'bootstrap@test.local', 'hash')
                    """);
            statement.execute("""
                    INSERT INTO user_settings(id, user_id, settings_type, settings_json)
                    SELECT gen_random_uuid(), id, 'image-review-style', '{}'::jsonb
                    FROM app_users WHERE email='bootstrap@test.local'
                    """);
        }
    }

    @Test
    void failsWhenAnExistingRequiredColumnHasDrifted() throws Exception {
        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE app_users (
                        id uuid PRIMARY KEY,
                        email text NOT NULL UNIQUE,
                        password_hash text NOT NULL,
                        display_name text NOT NULL,
                        role text NOT NULL DEFAULT 'user',
                        status text NOT NULL DEFAULT 'active',
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now(),
                        last_login_at timestamptz
                    )
                    """);
        }

        DataSource dataSource = dataSource();
        assertThatThrownBy(() -> {
            flyway(dataSource).migrate();
            new DatabaseSchemaVerifier(dataSource).verify();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("public.app_users.display_name");
    }

    private static Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/bootstrap")
                .table("database_bootstrap_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
