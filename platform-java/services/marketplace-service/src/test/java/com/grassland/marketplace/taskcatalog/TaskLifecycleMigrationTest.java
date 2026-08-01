package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Flyway V11 回填回归（GL-P1-TASK-001 Stage 1）：既有 published 任务必须拿到 {@code published_at=created_at}、
 * {@code version=1} 和一行 {@code task_version} 快照；且迁移绝不伪造 outbox 事件。
 *
 * <p>镜像 identity {@code RecommenderIdentityBackfillMigrationTest} 的隔离 schema 模式：手动建 post-V10 的 task +
 * marketplace_outbox，baseline=10，只跑 V11。
 */
class TaskLifecycleMigrationTest extends MarketplaceItSupport {

    @Test
    void v11BackfillsPublishedTaskSnapshotAndTimestampsWithoutOutboxSideEffects() throws Exception {
        String schema = "task_v11_" + UUID.randomUUID().toString().replace("-", "");
        String publishedTask = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            // post-V10 task 形状（V1 + V2 max_slots + V3 bounty_cents）。
            statement.execute("CREATE TABLE " + schema + ".task ("
                    + "id uuid PRIMARY KEY, owner_account_id uuid NOT NULL, organization_id uuid NOT NULL,"
                    + " title text NOT NULL, description text, status varchar(32) NOT NULL DEFAULT 'published',"
                    + " content_form varchar(32), platform varchar(32),"
                    + " created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),"
                    + " max_slots int, bounty_cents bigint)");
            statement.execute("CREATE TABLE " + schema + ".marketplace_outbox (id uuid PRIMARY KEY)");
            statement.execute("INSERT INTO " + schema + ".task(id, owner_account_id, organization_id, title, status, bounty_cents) "
                    + "VALUES ('" + publishedTask + "', '" + owner + "', '" + org + "', '历史任务', 'published', 500)");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema)
                .schemas(schema)
                .table("task_v11_history")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("10")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var taskRow = statement.executeQuery(
                     "SELECT status, version, published_at, created_at FROM " + schema + ".task WHERE id = '" + publishedTask + "'")) {
            taskRow.next();
            assertThat(taskRow.getInt("version")).isEqualTo(1);
            assertThat(taskRow.getString("status")).isEqualTo("published");
            assertThat(taskRow.getTimestamp("published_at")).isEqualTo(taskRow.getTimestamp("created_at"));
        }

        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery(
                    "SELECT version, title, bounty_cents, published_by::text FROM " + schema
                            + ".task_version WHERE task_id = '" + publishedTask + "'")) {
                rs.next();
                assertThat(rs.getInt("version")).isEqualTo(1);
                assertThat(rs.getString("title")).isEqualTo("历史任务");
                assertThat(rs.getLong("bounty_cents")).isEqualTo(500L);
                assertThat(rs.getString("published_by")).isEqualTo(owner);
                assertThat(rs.isLast()).isTrue();  // 仅一行快照
            }
            assertThat(count(statement, schema + ".marketplace_outbox", "true")).isZero();  // 无事件副作用
        }
    }

    private static int count(java.sql.Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }
}
