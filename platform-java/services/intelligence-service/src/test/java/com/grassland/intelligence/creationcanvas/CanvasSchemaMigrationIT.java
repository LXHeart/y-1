package com.grassland.intelligence.creationcanvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务书 #100 C100-20（TC-041 / V-MIGRATE）：V71～V76 迁移兼容演练。
 *
 * <p>
 * 每个场景用独立 DATABASE（生产形制=每库单 schema，见 docs/架构「共库逻辑隔离」备忘）——
 * 不能与 public 共库演练：V72/V75 的次级索引由 DO 块按 pg_indexes 库内同名跳过，共库时
 * 索引不会落到合成 schema（PlatformModelConcurrencyMigrationTest 已登记的边界），独立库
 * 才能确定性地断言全部索引。
 *
 * <p>四个场景（§11 C100-20 实施步骤 3）：
 * <ol>
 * <li>空库：V1～V76 全链一次通过，六条画布迁移的表/列/约束/索引全部落地；
 * <li>V70 存量：只建 V60 形态的 video_storyboard / video_production_task 并预置存量行，
 * baseline 70 后 V71～V76 应用——存量行按默认值回填（edit_version=1 / selection_version=0），
 * 新 CHECK 生效，V74 的 media_id 保持裸 UUID（不建 FK，§7.4 兼容边界）；
 * <li>重复执行：删除 V71～V76 历史行后重跑——幂等 DDL（IF NOT EXISTS / DO 块）在已填充
 * 库上原样重放成功，数据零变化；
 * <li>部分执行副本与失败恢复：V75 索引缺失（建表后崩溃的中间态）由 DO 块自愈；
 * V76 被同名视图阻塞失败后清除障碍重试成功（PG 事务性 DDL：失败不落历史行）。
 * </ol>
 */
@DisplayName("Canvas schema migration (C100-20 / TC-041)")
class CanvasSchemaMigrationIT extends IntelligenceItSupport {

    @Test
    @DisplayName("空库：V1～V76 全链一次通过，画布六件套完整落地")
    void freshDatabaseRunsFullChain() throws Exception {
        Database db = createDatabase("fresh");
        try {
            migrate(db, null, "intelligence_flyway_schema");

            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                // V71：两列 + 两 CHECK
                assertThat(count(statement, "information_schema.columns",
                        cols("video_storyboard", "edit_version") + " AND column_default='1'")).isEqualTo(1);
                assertThat(count(statement, "information_schema.columns",
                        cols("video_production_task", "selection_version") + " AND column_default='0'"))
                        .isEqualTo(1);
                for (String check : new String[] {"video_storyboard_edit_version_check",
                        "video_production_task_selection_version_check"}) {
                    assertThat(count(statement, "pg_constraint", "conname='" + check + "'")).isEqualTo(1);
                }
                // V72～V76：四张新表 + 主键 + 关键唯一索引
                assertTableWithPrimaryKey(statement, "video_storyboard_workspace");
                assertThat(count(statement, "pg_indexes", index("video_storyboard_workspace",
                        "video_storyboard_workspace_draft_key"))).isEqualTo(1);
                assertTableWithPrimaryKey(statement, "creation_canvas_document");
                assertThat(count(statement, "pg_indexes", index("creation_canvas_document",
                        "creation_canvas_document_draft_key"))).isEqualTo(1);
                assertTableWithPrimaryKey(statement, "video_shot_media_source");
                assertTableWithPrimaryKey(statement, "video_storyboard_variant");
                assertThat(count(statement, "pg_indexes", index("video_storyboard_variant",
                        "video_storyboard_variant_account_operation_key"))).isEqualTo(1);
                assertTableWithPrimaryKey(statement, "creation_canvas_agent_plan");
                assertThat(count(statement, "pg_indexes", index("creation_canvas_agent_plan",
                        "creation_canvas_agent_plan_account_operation_key"))).isEqualTo(1);
                // 媒体裸 UUID：V74 来源表不建任何 FK（归属/状态由服务层校验）
                assertThat(count(statement, "pg_constraint",
                        "contype='f' AND conrelid='public.video_shot_media_source'::regclass")).isZero();
            }
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    @DisplayName("V70 存量：baseline 后 V71～V76 应用，存量行默认值回填、新约束生效")
    void v70BaselineUpgradesLegacyRowsWithoutDataLoss() throws Exception {
        Database db = createDatabase("legacy");
        String storyboardId = UUID.randomUUID().toString();
        try {
            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                createLegacyV60Shape(statement, storyboardId);
            }
            migrate(db, "70", "intelligence_flyway_schema");

            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                // 存量行保住且按默认值回填（V71 回填口径：edit_version=1 / selection_version=0）
                assertThat(count(statement, "video_storyboard",
                        "id='" + storyboardId + "' AND edit_version=1")).isEqualTo(1);
                assertThat(count(statement, "video_production_task",
                        "storyboard_id='" + storyboardId + "' AND selection_version=0")).isEqualTo(1);
                // 新 CHECK 生效：非法值被拒（旧数据不受影响、新写入受约束）
                assertThatThrownBy(() -> statement.execute("UPDATE video_storyboard "
                        + "SET edit_version=0 WHERE id='" + storyboardId + "'"))
                        .isInstanceOf(java.sql.SQLException.class);
                // V74 媒体裸 UUID：可插入任意 media_id（不存在也接受——服务层负责归属校验）
                statement.execute("INSERT INTO video_shot_media_source(shot_id, storyboard_id, "
                        + "source_kind, media_id, audio_mode) VALUES ('" + UUID.randomUUID() + "', '"
                        + storyboardId + "', 'own-media', '" + UUID.randomUUID() + "', 'mute')");
                assertThat(count(statement, "video_shot_media_source",
                        "storyboard_id='" + storyboardId + "'")).isEqualTo(1);
            }
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    @DisplayName("重复执行：删除 V71～V76 历史行后重跑，幂等 DDL 在已填充库上原样重放")
    void replayAfterHistoryPruneKeepsDataIntact() throws Exception {
        Database db = createDatabase("replay");
        String storyboardId = UUID.randomUUID().toString();
        try {
            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                createLegacyV60Shape(statement, storyboardId);
            }
            migrate(db, "70", "intelligence_flyway_schema");

            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                // 迁移后落一行带数据的新表行，验证重放不清洗既有业务行
                statement.execute("INSERT INTO video_storyboard_variant(storyboard_id, "
                        + "parent_storyboard_id, root_storyboard_id, account_id, operation_id, request_hash, "
                        + "source_edit_version, source_draft_version, title, shot_id_map) VALUES ('"
                        + UUID.randomUUID() + "', '" + storyboardId + "', '" + storyboardId
                        + "', 'replay-account', '" + UUID.randomUUID() + "', '" + "0".repeat(64)
                        + "', 1, 1, '重放行', '{}'::jsonb)");
                statement.execute("DELETE FROM intelligence_flyway_schema "
                        + "WHERE version IN ('71','72','73','74','75','76')");
            }
            // 重放：六条脚本对已存在对象与数据再次执行（IF NOT EXISTS / DO 块存在性检查）
            migrate(db, "70", "intelligence_flyway_schema");

            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                assertThat(count(statement, "video_storyboard",
                        "id='" + storyboardId + "' AND edit_version=1")).isEqualTo(1);
                assertThat(count(statement, "video_storyboard_variant", "title='重放行'")).isEqualTo(1);
                assertThat(count(statement, "intelligence_flyway_schema", "version IN "
                        + "('71','72','73','74','75','76') AND success=true")).isEqualTo(6);
            }
        } finally {
            dropDatabase(db);
        }
    }

    @Test
    @DisplayName("部分执行副本与失败恢复：缺索引中间态自愈；同名视图阻塞失败后重试成功")
    void partialExecutionHealsAndObstructedFailureRecovers() throws Exception {
        Database db = createDatabase("partial");
        try {
            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                createLegacyV60Shape(statement, UUID.randomUUID().toString());
            }
            // 先让 V71～V76 全部正常落地
            migrate(db, "70", "intelligence_flyway_schema");
            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                // 构造双故障现场：
                // a) V75「建表后、索引前」崩溃的中间态（表在、两个次级索引缺失）+ 历史 75/76 抹除
                statement.execute("DROP INDEX video_storyboard_variant_account_operation_key");
                statement.execute("DROP INDEX video_storyboard_variant_root_lineage");
                statement.execute("DROP TABLE creation_canvas_agent_plan");
                statement.execute("DELETE FROM intelligence_flyway_schema WHERE version IN ('75','76')");
                // b) 阻塞 V76：同名视图使 CREATE TABLE IF NOT EXISTS 报错（PG：同名非表关系不跳过）
                statement.execute("CREATE VIEW creation_canvas_agent_plan AS SELECT 1 AS x");
            }
            // 失败：V75 重放先自愈索引，随后 V76 被阻塞——PG 事务性 DDL 回滚该迁移自身改动
            assertThatThrownBy(() -> migrate(db, "70", "intelligence_flyway_schema"))
                    .isInstanceOf(org.flywaydb.core.api.FlywayException.class);

            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                // 中间态已被 V75 重放自愈：两个索引由 DO 块补回（失败发生在其后的 V76）
                assertThat(count(statement, "pg_indexes",
                        index("video_storyboard_variant",
                                "video_storyboard_variant_account_operation_key"))).isEqualTo(1);
                assertThat(count(statement, "pg_indexes",
                        index("video_storyboard_variant", "video_storyboard_variant_root_lineage")))
                        .isEqualTo(1);
                assertThat(count(statement, "intelligence_flyway_schema",
                        "version='75' AND success=true")).isEqualTo(1);
                // 清除障碍 → 失败恢复：重试成功，全链到位
                statement.execute("DROP VIEW creation_canvas_agent_plan");
            }
            migrate(db, "70", "intelligence_flyway_schema");
            try (Connection connection = open(db); Statement statement = connection.createStatement()) {
                assertThat(count(statement, "intelligence_flyway_schema",
                        "version='76' AND success=true")).isEqualTo(1);
                assertThat(count(statement, "information_schema.tables",
                        "table_name='creation_canvas_agent_plan'")).isEqualTo(1);
            }
        } finally {
            dropDatabase(db);
        }
    }

    // ---- 帮手 ----

    private record Database(String name, String jdbcUrl) {
    }

    /** 独立库（生产形制单库单 schema）；容器用户是超级用户可建库。 */
    private static Database createDatabase(String label) throws Exception {
        String name = "canvas_mig_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new Database(name, "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + name);
    }

    private static void dropDatabase(Database db) throws Exception {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + db.name() + " WITH (FORCE)");
        }
    }

    private static Connection open(Database db) throws Exception {
        return DriverManager.getConnection(db.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** V60 形态的最小存量表（无 edit_version / selection_version——V71 之前的真实形状）。 */
    private static void createLegacyV60Shape(Statement statement, String storyboardId) throws Exception {
        statement.execute("CREATE TABLE video_storyboard ("
                + "id uuid PRIMARY KEY, account_id text NOT NULL, organization_id text, "
                + "context_snapshot_id uuid, target_duration_seconds int NOT NULL, resolution text, "
                + "request_payload jsonb NOT NULL, status text NOT NULL, "
                + "created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), "
                + "grouping jsonb)");
        statement.execute("CREATE TABLE video_production_task ("
                + "id uuid PRIMARY KEY, storyboard_id uuid NOT NULL, account_id text NOT NULL, "
                + "status text NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
        statement.execute("INSERT INTO video_storyboard(id, account_id, target_duration_seconds, "
                + "request_payload, status) VALUES ('" + storyboardId + "', 'legacy-account', 25, '{}', 'draft')");
        statement.execute("INSERT INTO video_production_task(id, storyboard_id, account_id, status) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + storyboardId + "', 'legacy-account', 'queued')");
    }

    /** baseline 为 null 时从 V1 全链执行；否则跳过 baseline 之前的历史（存量演练）。 */
    private static MigrateResult migrate(Database db, String baselineVersion, String historyTable) {
        var configure = Flyway.configure()
                .dataSource(db.jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema("public")
                .schemas("public")
                .table(historyTable)
                .locations("classpath:db/migration");
        if (baselineVersion != null) {
            configure.baselineOnMigrate(true).baselineVersion(baselineVersion);
        }
        return configure.load().migrate();
    }

    private static String cols(String table, String column) {
        return "table_name='" + table + "' AND column_name='" + column + "'";
    }

    private static String index(String table, String name) {
        return "schemaname='public' AND tablename='" + table + "' AND indexname='" + name + "'";
    }

    private static void assertTableWithPrimaryKey(Statement statement, String table) throws Exception {
        assertThat(count(statement, "information_schema.tables",
                "table_name='" + table + "'")).as("表 %s 存在", table).isEqualTo(1);
        assertThat(count(statement, "pg_indexes",
                index(table, table + "_pkey"))).as("表 %s 主键落地", table).isEqualTo(1);
    }

    private static int count(Statement statement, String table, String predicate) throws Exception {
        try (var result = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            result.next();
            return result.getInt(1);
        }
    }
}
