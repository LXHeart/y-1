package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** V14-V16 upgrade regression for historical invalid max_concurrency values. */
class PlatformModelConcurrencyMigrationTest extends IntelligenceItSupport {

    @Test
    void invalidHistoricalConcurrencyIsDisabledAuditedAndDoesNotBlockUpgrade() throws Exception {
        String schema = "ai_concurrency_" + UUID.randomUUID().toString().replace("-", "");
        String validId = UUID.randomUUID().toString();
        String zeroId = UUID.randomUUID().toString();
        String highId = UUID.randomUUID().toString();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE TABLE " + schema + ".platform_model_config ("
                    + "id uuid PRIMARY KEY, capability varchar(64) NOT NULL, model_role varchar(16) NOT NULL,"
                    + "provider varchar(64) NOT NULL, model varchar(128) NOT NULL, base_url text NOT NULL,"
                    + "max_concurrency int, health_status varchar(16) NOT NULL, enabled boolean NOT NULL,"
                    + "version int NOT NULL, updated_by text, created_at timestamptz NOT NULL DEFAULT now(),"
                    + "updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE " + schema + ".platform_model_config_history ("
                    + "id uuid PRIMARY KEY DEFAULT gen_random_uuid(), capability varchar(64) NOT NULL,"
                    + "model_role varchar(16) NOT NULL, provider varchar(64) NOT NULL, model varchar(128) NOT NULL,"
                    + "base_url text NOT NULL, max_concurrency int, health_status varchar(16) NOT NULL,"
                    + "version int NOT NULL, changed_by text NOT NULL, change_type varchar(16) NOT NULL,"
                    + "changed_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE " + schema + ".ai_run (id uuid PRIMARY KEY)");
            insertConfig(statement, schema, validId, "valid", 2);
            insertConfig(statement, schema, zeroId, "zero", 0);
            insertConfig(statement, schema, highId, "high", 1001);
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .table("ai_concurrency_history")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("13")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(count(statement, schema + ".platform_model_concurrency_slot",
                    "config_id = '" + validId + "'")).isEqualTo(2);
            assertThat(count(statement, schema + ".platform_model_concurrency_slot",
                    "config_id IN ('" + zeroId + "','" + highId + "')")).isZero();
            assertThat(count(statement, schema + ".platform_model_config",
                    "id = '" + zeroId + "' AND enabled = false AND max_concurrency = 1")).isEqualTo(1);
            assertThat(count(statement, schema + ".platform_model_config",
                    "id = '" + highId + "' AND enabled = false AND max_concurrency = 1000")).isEqualTo(1);
            assertThat(count(statement, schema + ".platform_model_config_history",
                    "changed_by = 'migration:v16' AND change_type = 'repair'")).isEqualTo(2);
            try (var result = statement.executeQuery("SELECT convalidated FROM pg_constraint "
                    + "WHERE conname='chk_platform_model_max_concurrency' "
                    + "AND conrelid='" + schema + ".platform_model_config'::regclass")) {
                result.next();
                assertThat(result.getBoolean(1)).isTrue();
            }
        }
    }

    private static void insertConfig(
            java.sql.Statement statement, String schema, String id, String capability, int concurrency)
            throws Exception {
        statement.execute("INSERT INTO " + schema + ".platform_model_config("
                + "id, capability, model_role, provider, model, base_url, max_concurrency,"
                + "health_status, enabled, version) VALUES ('" + id + "','" + capability
                + "','primary','qwen','qwen-plus','https://example.com'," + concurrency
                + ",'healthy',true,1)");
    }

    private static int count(java.sql.Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }
}

