package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Flyway 回填回归（GL-P1-TASK-001）：V11 既有 published 任务须拿到 {@code published_at=created_at}、{@code version=1}
 * 和一行 {@code task_version} 快照；V14 既有 task_application 须从 task 回填 {@code bounty_cents} 并 NOT NULL。
 * 迁移绝不伪造 outbox 事件。
 *
 * <p>镜像 identity {@code RecommenderIdentityBackfillMigrationTest} 的隔离 schema 模式：手动建 post-V10 的 task、
 * task_application、submission attachment 与 marketplace_outbox，baseline=10，跑 V11+。后续 migration 会 ALTER
 * 这些 V10 前已存在的表，故夹具必须完整，避免 PostgreSQL search_path 意外回退到 public schema。
 */
class TaskLifecycleMigrationTest extends MarketplaceItSupport {

    @Test
    void v11AndV14BackfillTaskSnapshotAndApplicationBountyWithoutOutboxSideEffects() throws Exception {
        String schema = "task_v11_" + UUID.randomUUID().toString().replace("-", "");
        String publishedTask = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String historicalApp = UUID.randomUUID().toString();

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
            // post-V10 task_application 形状（V2，**无 bounty_cents**——V14 加）。V14 ALTER 它，故须存在。
            statement.execute("CREATE TABLE " + schema + ".task_application ("
                    + "id uuid PRIMARY KEY, task_id uuid NOT NULL, recommender_account_id uuid NOT NULL,"
                    + " status varchar(32) NOT NULL DEFAULT 'pending', note text, reviewed_by_account_id uuid,"
                    + " decided_at timestamptz, confirmed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),"
                    + " updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(task_id, recommender_account_id))");
            statement.execute("CREATE TABLE " + schema + ".marketplace_outbox (id uuid PRIMARY KEY)");
            statement.execute("CREATE TABLE " + schema + ".engagement_submission ("
                    + "id uuid PRIMARY KEY, application_id uuid NOT NULL REFERENCES " + schema
                    + ".task_application(id), recommender_account_id uuid NOT NULL, content_url text NOT NULL,"
                    + " note text, status varchar(32) NOT NULL DEFAULT 'submitted', review_note text,"
                    + " reviewed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(),"
                    + " updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE " + schema + ".engagement_submission_attachment ("
                    + "id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES " + schema
                    + ".engagement_submission(id), media_reference_id uuid NOT NULL, mime_type text,"
                    + " size_bytes bigint, created_at timestamptz NOT NULL DEFAULT now())");
            // V10 table must live in the isolated schema; otherwise later ALTER may fall through to public.
            statement.execute("CREATE TABLE " + schema + ".engagement_verification ("
                    + "id uuid PRIMARY KEY, submission_id uuid NOT NULL REFERENCES " + schema
                    + ".engagement_submission(id), status varchar(32) NOT NULL, checks jsonb NOT NULL DEFAULT '[]',"
                    + " last_checked_at timestamptz NOT NULL DEFAULT now(), created_at timestamptz NOT NULL DEFAULT now(),"
                    + " updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(submission_id))");
            statement.execute("INSERT INTO " + schema + ".task(id, owner_account_id, organization_id, title, status, bounty_cents) "
                    + "VALUES ('" + publishedTask + "', '" + owner + "', '" + org + "', '历史任务', 'published', 500)");
            statement.execute("INSERT INTO " + schema
                    + ".task_application(id, task_id, recommender_account_id, status) VALUES ('"
                    + historicalApp + "', '" + publishedTask + "', '" + recommender + "', 'accepted')");
        }

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .defaultSchema(schema)
                .schemas(schema)
                .table("task_v11_history")
                .locations("filesystem:" + MarketplaceItSupport.marketplaceMigrationDir())
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
            // V14：历史 app 从 task 回填 bounty_cents=500，且 NOT NULL（getObject 非空）。
            try (var rs = statement.executeQuery(
                    "SELECT bounty_cents FROM " + schema + ".task_application WHERE id = '" + historicalApp + "'")) {
                rs.next();
                assertThat(rs.getObject("bounty_cents")).isNotNull();  // NOT NULL 生效
                assertThat(rs.getLong("bounty_cents")).isEqualTo(500L);
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
