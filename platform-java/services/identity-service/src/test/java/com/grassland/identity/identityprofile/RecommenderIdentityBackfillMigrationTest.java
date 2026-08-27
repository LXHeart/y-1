package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Flyway V13 回填回归：V9 允许 recommender_profile 独立存在时，历史画像账号必须获得可激活的
 * recommender identity_profile；但迁移绝不伪造 session/audit/outbox 副作用。
 */
class RecommenderIdentityBackfillMigrationTest extends IdentityItSupport {

    @Test
    void v13BackfillsLegacyProfileOnlyAccountWithoutChangingExistingIdentityOrCreatingSideEffects()
            throws Exception {
        String schema = "identity_v13_" + UUID.randomUUID().toString().replace("-", "");
        String profileOnlyAccount = UUID.randomUUID().toString();
        String existingAccount = UUID.randomUUID().toString();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE TABLE " + schema + ".identity_profile ("
                    + "id uuid PRIMARY KEY, account_id uuid NOT NULL, identity_type varchar(32) NOT NULL, "
                    + "organization_id uuid, status varchar(32) NOT NULL, "
                    + "created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), "
                    + "UNIQUE(account_id, identity_type))");
            statement.execute("CREATE TABLE " + schema + ".recommender_profile (account_id uuid PRIMARY KEY)");
            // 列结构与 V5 真实表对齐：V45 存量清理按 search_path 命中本 schema 的表并引用其列
            statement.execute("CREATE TABLE " + schema + ".identity_session (session_token text PRIMARY KEY, "
                    + "account_id uuid NOT NULL, active_identity_type varchar(32))");
            statement.execute("CREATE TABLE " + schema + ".identity_audit_log (id uuid PRIMARY KEY)");
            statement.execute("CREATE TABLE " + schema + ".outbox (id uuid PRIMARY KEY)");
            statement.execute("INSERT INTO " + schema + ".recommender_profile(account_id) VALUES ('"
                    + profileOnlyAccount + "'), ('" + existingAccount + "')");
            // 既有 merchant 身份必须原样保留；V13 只补它缺失的 recommender 行。
            statement.execute("INSERT INTO " + schema + ".identity_profile("
                    + "id, account_id, identity_type, organization_id, status) VALUES ('"
                    + UUID.randomUUID() + "', '" + existingAccount + "', 'merchant', NULL, 'suspended')");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .table("identity_v13_history")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("12")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(count(statement, schema + ".identity_profile", "account_id = '" + profileOnlyAccount
                    + "' AND identity_type = 'recommender' AND organization_id IS NULL AND status = 'active'"))
                    .isEqualTo(1);
            assertThat(count(statement, schema + ".identity_profile", "account_id = '" + existingAccount
                    + "' AND identity_type = 'recommender'"))
                    .isEqualTo(1);
            assertThat(count(statement, schema + ".identity_profile", "account_id = '" + existingAccount
                    + "' AND identity_type = 'merchant' AND status = 'suspended'"))
                    .isEqualTo(1);
            assertThat(count(statement, schema + ".identity_session", "true")).isZero();
            assertThat(count(statement, schema + ".identity_audit_log", "true")).isZero();
            assertThat(count(statement, schema + ".outbox", "true")).isZero();
        }
    }

    private static int count(java.sql.Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }
}
