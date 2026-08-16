package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.marketplace.MarketplaceItSupport;
import io.r2dbc.spi.ConnectionFactories;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

class ReputationEntitlementMigrationTest extends MarketplaceItSupport {

    @Test
    void reputationIndexesUseNonTransactionalConcurrentMigration() throws Exception {
        String schema = "marketplace_v22_indexes_" + UUID.randomUUID().toString().replace("-", "");
        String v21 = resourceText("db/migration/V21__reputation_entitlement_consumption.sql");
        String v22 = resourceText("db/migration/V22__reputation_batch_indexes.sql");
        String config = resourceText("db/migration/V22__reputation_batch_indexes.sql.conf");

        assertThat(v21).doesNotContain("CREATE INDEX");
        assertThat(v22).containsOnlyOnce("idx_task_feed_min_recommender_level")
                .containsOnlyOnce("idx_task_application_recommender")
                .containsOnlyOnce("idx_engagement_submission_recommender_application_created")
                .contains("CREATE INDEX CONCURRENTLY");
        assertThat(config.strip()).isEqualTo("executeInTransaction=false");

        try {
            flyway(schema, null).migrate();
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT COUNT(*) AS index_count,"
                         + " bool_and(i.indisready AND i.indisvalid) AS usable"
                         + " FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid"
                         + " JOIN pg_namespace n ON n.oid=c.relnamespace"
                         + " WHERE n.nspname='" + schema + "' AND c.relname IN ("
                         + "'idx_task_feed_min_recommender_level',"
                         + "'idx_task_application_recommender',"
                         + "'idx_engagement_submission_recommender_application_created')")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt("index_count")).isEqualTo(3);
                assertThat(row.getBoolean("usable")).isTrue();
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v21BackfillsInflightRightsAndRejectsIncompleteSettlementSnapshots() throws Exception {
        String schema = "marketplace_v21_" + UUID.randomUUID().toString().replace("-", "");
        String taskId = UUID.randomUUID().toString();
        String reservingId = UUID.randomUUID().toString();
        String acceptedId = UUID.randomUUID().toString();
        String refundedId = UUID.randomUUID().toString();
        String pendingId = UUID.randomUUID().toString();

        Flyway v20 = flyway(schema, MigrationVersion.fromVersion("20"));
        v20.migrate();
        try {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute("INSERT INTO task(id, owner_account_id, organization_id, title, status) VALUES ('"
                        + taskId + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                        + "', 'migration fixture', 'published')");
                insertApplication(statement, taskId, reservingId, "reserving", false);
                insertApplication(statement, taskId, acceptedId, "accepted", false);
                insertApplication(statement, taskId, refundedId, "refunded", true);
            }

            flyway(schema, null).migrate();

            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                for (String applicationId : new String[] {reservingId, acceptedId, refundedId}) {
                    try (var row = statement.executeQuery("SELECT reputation_level_at_accept,"
                            + " reputation_policy_version_at_accept, settlement_delay_days_at_accept,"
                            + " commission_bonus_bps_at_accept, premium_support_at_accept"
                            + " FROM task_application WHERE id='" + applicationId + "'")) {
                        assertThat(row.next()).isTrue();
                        assertThat(row.getInt("reputation_level_at_accept")).isEqualTo(1);
                        assertThat(row.getLong("reputation_policy_version_at_accept")).isEqualTo(1L);
                        assertThat(row.getInt("settlement_delay_days_at_accept")).isEqualTo(2);
                        assertThat(row.getInt("commission_bonus_bps_at_accept")).isZero();
                        assertThat(row.getBoolean("premium_support_at_accept")).isFalse();
                    }
                }

                insertApplication(statement, taskId, pendingId, "pending", false);
                assertThatThrownBy(() -> statement.execute("UPDATE task_application"
                        + " SET reputation_level_at_accept=1 WHERE id='" + pendingId + "'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("UPDATE task_application"
                        + " SET status='accepted' WHERE id='" + pendingId + "'"))
                        .isInstanceOf(SQLException.class);
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void repositoryGuardBlocksLegacyReservingRowsWithoutSnapshot() throws Exception {
        String schema = "marketplace_v21_guard_" + UUID.randomUUID().toString().replace("-", "");
        String taskId = UUID.randomUUID().toString();
        String applicationId = UUID.randomUUID().toString();

        flyway(schema, null).migrate();
        try {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute("ALTER TABLE task_application"
                        + " DROP CONSTRAINT ck_application_reputation_snapshot_required");
                statement.execute("INSERT INTO task(id, owner_account_id, organization_id, title, status) VALUES ('"
                        + taskId + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID()
                        + "', 'legacy guard fixture', 'published')");
                insertApplication(statement, taskId, applicationId, "reserving", false);
            }

            var repository = new TaskApplicationRepository(DatabaseClient.create(
                    ConnectionFactories.get(r2dbcUrl(schema))));

            assertThat(repository.acceptFromReserving(applicationId, taskId, 900L, 0L).block()).isNull();

            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                try (var row = statement.executeQuery("SELECT status, bounty_cents FROM task_application"
                        + " WHERE id='" + applicationId + "'")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("status")).isEqualTo("reserving");
                    assertThat(row.getLong("bounty_cents")).isEqualTo(500L);
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void legacyBeginAcceptanceAtomicallyFreezesConservativeEntitlementDefaults() throws Exception {
        String schema = "marketplace_v21_legacy_begin_" + UUID.randomUUID().toString().replace("-", "");
        String taskId = UUID.randomUUID().toString();
        String applicationId = UUID.randomUUID().toString();
        String reviewerId = UUID.randomUUID().toString();

        flyway(schema, null).migrate();
        try {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute("INSERT INTO task(id, owner_account_id, organization_id, title, status) VALUES ('"
                        + taskId + "', '" + reviewerId + "', '" + UUID.randomUUID()
                        + "', 'legacy begin fixture', 'published')");
                insertApplication(statement, taskId, applicationId, "pending", false);
            }

            var repository = new TaskApplicationRepository(DatabaseClient.create(
                    ConnectionFactories.get(r2dbcUrl(schema))));
            TaskApplication transitioned = repository.beginAcceptance(applicationId, taskId, reviewerId).block();

            assertThat(transitioned).isNotNull();
            assertThat(transitioned.status()).isEqualTo("reserving");
            assertThat(transitioned.reputationLevelAtAccept()).isEqualTo(1);
            assertThat(transitioned.reputationPolicyVersionAtAccept()).isEqualTo(1L);
            assertThat(transitioned.settlementDelayDaysAtAccept()).isEqualTo(2);
            assertThat(transitioned.commissionBonusBpsAtAccept()).isZero();
            assertThat(transitioned.premiumSupportAtAccept()).isFalse();
        } finally {
            try (var connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .locations("filesystem:" + MarketplaceItSupport.marketplaceMigrationDir());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String r2dbcUrl(String schema) {
        return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName() + "?schema=" + schema;
    }

    private static String resourceText(String path) throws Exception {
        try (var input = ReputationEntitlementMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void insertApplication(java.sql.Statement statement, String taskId,
                                          String applicationId, String status,
                                          boolean confirmed) throws SQLException {
        statement.execute("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents,"
                + " confirmed_at) VALUES ('" + applicationId + "', '" + taskId + "', '" + UUID.randomUUID()
                + "', '" + status + "', 500, " + (confirmed ? "now()" : "NULL") + ")");
    }
}
