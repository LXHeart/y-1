package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.trust.TrustItSupport;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class JudgeAdmissionMigrationTest {

    private static final String SYSTEM_ACTOR = "00000000-0000-0000-0000-000000000000";

    @Test
    void supportQueueIndexUsesNonTransactionalConcurrentMigration() throws Exception {
        String schema = "trust_v10_index_" + UUID.randomUUID().toString().replace("-", "");
        String v9 = resourceText("db/migration/V9__premium_support_queue.sql");
        String v10 = resourceText("db/migration/V10__premium_support_queue_index.sql");
        String config = resourceText("db/migration/V10__premium_support_queue_index.sql.conf");

        assertThat(v9).doesNotContain("CREATE INDEX")
                .contains("NOT VALID", "VALIDATE CONSTRAINT ck_dispute_support_priority");
        assertThat(v10).contains("CREATE INDEX CONCURRENTLY")
                .containsOnlyOnce("idx_dispute_support_queue");
        assertThat(config.strip()).isEqualTo("executeInTransaction=false");

        Flyway.configure()
                .dataSource(TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                        TrustItSupport.POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .load().migrate();
        try {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT i.indisready, i.indisvalid"
                         + " FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid"
                         + " JOIN pg_namespace n ON n.oid=c.relnamespace"
                         + " WHERE n.nspname='" + schema + "'"
                         + " AND c.relname='idx_dispute_support_queue'")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean("indisready")).isTrue();
                assertThat(row.getBoolean("indisvalid")).isTrue();
                assertThat(row.next()).isFalse();
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void onlyActiveJudgesOnCurrentVotingRoundAreGrandfatheredWithAudit() throws Exception {
        String schema = "trust_v8_" + UUID.randomUUID().toString().replace("-", "");
        String inflight = UUID.randomUUID().toString();
        String historical = UUID.randomUUID().toString();
        String unrelated = UUID.randomUUID().toString();
        String dispute = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String opener = UUID.randomUUID().toString();
        Flyway.configure()
                .dataSource(TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                        TrustItSupport.POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .target(MigrationVersion.fromVersion("7"))
                .load().migrate();

        try (var connection = DriverManager.getConnection(
                TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                TrustItSupport.POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.execute("INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,"
                    + " opened_by_role, status, round) VALUES ('" + dispute + "', 'eng-inflight', '" + org + "', '"
                    + opener + "', 'merchant', 'voting', 2)");
            statement.execute("INSERT INTO judge(id, account_id, eligibility_tier, active) VALUES ('"
                    + UUID.randomUUID() + "', '" + inflight + "', 5, true), ('"
                    + UUID.randomUUID() + "', '" + historical + "', 5, true), ('"
                    + UUID.randomUUID() + "', '" + unrelated + "', 5, true)");
            statement.execute("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id) VALUES ('"
                    + dispute + "', 1, '" + historical + "'), ('"
                    + dispute + "', 2, '" + inflight + "')");
        }

        Flyway.configure()
                .dataSource(TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                        TrustItSupport.POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .load().migrate();

        try (var connection = DriverManager.getConnection(
                TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                TrustItSupport.POSTGRES.getPassword())) {
            connection.setSchema(schema);
            try (var query = connection.prepareStatement(
                    "SELECT ops_admitted, version, ops_admitted_by::text FROM judge WHERE account_id=?")) {
                query.setObject(1, UUID.fromString(inflight));
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getBoolean(1)).isTrue();
                    assertThat(row.getLong(2)).isEqualTo(1);
                    assertThat(row.getString(3)).isEqualTo(SYSTEM_ACTOR);
                }
                query.setObject(1, UUID.fromString(historical));
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getBoolean(1)).isFalse();
                    assertThat(row.getLong(2)).isZero();
                }
                query.setObject(1, UUID.fromString(unrelated));
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getBoolean(1)).isFalse();
                    assertThat(row.getLong(2)).isZero();
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT action, actor_account_id::text, previous_version, new_version, reason"
                            + " FROM judge_admission_audit")) {
                try (var row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("granted");
                    assertThat(row.getString(2)).isEqualTo(SYSTEM_ACTOR);
                    assertThat(row.getLong(3)).isZero();
                    assertThat(row.getLong(4)).isEqualTo(1);
                    assertThat(row.getString(5)).isEqualTo("migration_v8_grandfather_inflight_panel");
                    assertThat(row.next()).isFalse();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void databaseBoundaryRejectsLegacyPanelAndVoteWritesAfterAdmissionChanges() throws Exception {
        String schema = "trust_v8_boundary_" + UUID.randomUUID().toString().replace("-", "");
        String dispute = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String opener = UUID.randomUUID().toString();
        String pendingJudge = UUID.randomUUID().toString();
        String admittedJudge = UUID.randomUUID().toString();
        String admittedJudgeId = UUID.randomUUID().toString();
        String inactiveJudge = UUID.randomUUID().toString();
        String lowTierJudge = UUID.randomUUID().toString();
        String sameOrgJudge = UUID.randomUUID().toString();
        String conflictedJudge = UUID.randomUUID().toString();
        String conflictedJudgeId = UUID.randomUUID().toString();
        String outsiderJudge = UUID.randomUUID().toString();

        Flyway.configure()
                .dataSource(TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                        TrustItSupport.POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .load().migrate();

        try (var connection = DriverManager.getConnection(
                TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                TrustItSupport.POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.execute("INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,"
                    + " opened_by_role, status, round) VALUES ('" + dispute + "', 'eng-boundary', '" + org
                    + "', '" + opener + "', 'merchant', 'voting', 1)");
            statement.execute("INSERT INTO judge(id, account_id, eligibility_tier, active, ops_admitted) VALUES ('"
                    + UUID.randomUUID() + "', '" + pendingJudge + "', 5, true, false)");

            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id) VALUES ('"
                            + dispute + "', 1, '" + pendingJudge + "')"))
                    .isInstanceOf(java.sql.SQLException.class);

            statement.execute("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
                    + " ops_admitted, ops_admitted_at, ops_admitted_by) VALUES "
                    + "('" + admittedJudgeId + "', '" + admittedJudge + "', NULL, 5, true, true, now(), '"
                    + opener + "'), ('" + UUID.randomUUID() + "', '" + inactiveJudge
                    + "', NULL, 5, false, true, now(), '" + opener + "'), ('" + UUID.randomUUID() + "', '"
                    + lowTierJudge + "', NULL, 4, true, true, now(), '" + opener + "'), ('"
                    + UUID.randomUUID() + "', '" + sameOrgJudge + "', '" + org
                    + "', 5, true, true, now(), '" + opener + "'), ('" + conflictedJudgeId + "', '"
                    + conflictedJudge + "', NULL, 5, true, true, now(), '" + opener + "'), ('"
                    + UUID.randomUUID() + "', '" + outsiderJudge + "', NULL, 5, true, true, now(), '"
                    + opener + "')");
            statement.execute("INSERT INTO judge_conflict(judge_id, organization_id) VALUES ('"
                    + conflictedJudgeId + "', '" + org + "')");

            for (String ineligibleJudge : new String[]{
                    inactiveJudge, lowTierJudge, sameOrgJudge, conflictedJudge}) {
                assertThatThrownBy(() -> statement.execute(
                        "INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id) VALUES ('"
                                + dispute + "', 1, '" + ineligibleJudge + "')"))
                        .isInstanceOf(java.sql.SQLException.class);
            }

            statement.execute("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id) VALUES ('"
                    + dispute + "', 1, '" + admittedJudge + "')");

            statement.execute("INSERT INTO judge_conflict(judge_id, organization_id) VALUES ('"
                    + admittedJudgeId + "', '" + org + "')");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote) VALUES ('"
                            + dispute + "', 1, '" + admittedJudge + "', 'for_merchant')"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.execute("DELETE FROM judge_conflict WHERE judge_id='" + admittedJudgeId
                    + "' AND organization_id='" + org + "'");

            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote) VALUES ('"
                            + dispute + "', 1, '" + outsiderJudge + "', 'for_merchant')"))
                    .isInstanceOf(java.sql.SQLException.class);

            statement.execute("UPDATE dispute_case SET status='decided' WHERE id='" + dispute + "'");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote) VALUES ('"
                            + dispute + "', 1, '" + admittedJudge + "', 'for_merchant')"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.execute("UPDATE dispute_case SET status='voting', round=2 WHERE id='" + dispute + "'");
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote) VALUES ('"
                            + dispute + "', 1, '" + admittedJudge + "', 'for_merchant')"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.execute("UPDATE dispute_case SET round=1 WHERE id='" + dispute + "'");

            statement.execute("UPDATE judge SET ops_admitted=false, ops_admitted_at=NULL, ops_admitted_by=NULL"
                    + " WHERE account_id='" + admittedJudge + "'");

            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote) VALUES ('"
                            + dispute + "', 1, '" + admittedJudge + "', 'for_merchant')"))
                    .isInstanceOf(java.sql.SQLException.class);
        } finally {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void conflictWritesSerializeWithPanelEligibilityChecks() throws Exception {
        String schema = "trust_v8_conflict_lock_" + UUID.randomUUID().toString().replace("-", "");
        String dispute = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String opener = UUID.randomUUID().toString();
        String judgeId = UUID.randomUUID().toString();
        String judgeAccountId = UUID.randomUUID().toString();

        Flyway.configure()
                .dataSource(TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                        TrustItSupport.POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .schemas(schema).defaultSchema(schema).table("history")
                .load().migrate();

        try {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                connection.setSchema(schema);
                statement.execute("INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,"
                        + " opened_by_role, status, round) VALUES ('" + dispute + "', 'eng-lock', '" + org
                        + "', '" + opener + "', 'merchant', 'voting', 1)");
                statement.execute("INSERT INTO judge(id, account_id, eligibility_tier, active, ops_admitted,"
                        + " ops_admitted_at, ops_admitted_by) VALUES ('" + judgeId + "', '" + judgeAccountId
                        + "', 5, true, true, now(), '" + opener + "')");
            }

            try (var conflictConnection = DriverManager.getConnection(
                         TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                         TrustItSupport.POSTGRES.getPassword());
                 var assignmentConnection = DriverManager.getConnection(
                         TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                         TrustItSupport.POSTGRES.getPassword())) {
                conflictConnection.setSchema(schema);
                assignmentConnection.setSchema(schema);
                conflictConnection.setAutoCommit(false);
                assignmentConnection.setAutoCommit(false);
                try (var conflict = conflictConnection.createStatement();
                     var assignment = assignmentConnection.createStatement()) {
                    conflict.execute("INSERT INTO judge_conflict(judge_id, organization_id) VALUES ('"
                            + judgeId + "', '" + org + "')");
                    assignment.execute("SET LOCAL statement_timeout = '300ms'");

                    assertThatThrownBy(() -> assignment.execute(
                            "INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id) VALUES ('"
                                    + dispute + "', 1, '" + judgeAccountId + "')"))
                            .isInstanceOf(java.sql.SQLException.class)
                            .hasMessageContaining("statement timeout");
                } finally {
                    assignmentConnection.rollback();
                    conflictConnection.rollback();
                }
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    TrustItSupport.POSTGRES.getJdbcUrl(), TrustItSupport.POSTGRES.getUsername(),
                    TrustItSupport.POSTGRES.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static String resourceText(String path) throws Exception {
        try (var input = JudgeAdmissionMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
