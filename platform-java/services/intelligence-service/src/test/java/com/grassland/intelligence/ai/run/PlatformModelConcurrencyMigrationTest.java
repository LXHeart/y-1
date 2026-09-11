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
            // 任务书 #100 V71 重放兼容：合成 schema 只手工建了三张表，V60 链上创建的
            // video_storyboard / video_production_task 必须能被 V71 无损补列（无 FK、幂等 DDL）
            assertThat(count(statement, "information_schema.columns",
                    "table_schema='" + schema + "' AND table_name='video_storyboard' AND column_name='edit_version'"
                            + " AND data_type='bigint' AND column_default='1'")).isEqualTo(1);
            assertThat(count(statement, "information_schema.columns",
                    "table_schema='" + schema + "' AND table_name='video_production_task'"
                            + " AND column_name='selection_version' AND data_type='bigint'"
                            + " AND column_default='0'")).isEqualTo(1);
            assertThat(count(statement, "pg_constraint",
                    "conname='video_storyboard_edit_version_check'"
                            + " AND conrelid='" + schema + ".video_storyboard'::regclass")).isEqualTo(1);
            assertThat(count(statement, "pg_constraint",
                    "conname='video_production_task_selection_version_check'"
                            + " AND conrelid='" + schema + ".video_production_task'::regclass")).isEqualTo(1);
            // 任务书 #100 C100-08（TC-041）：V72 绑定表在合成 schema 上整链重放后存在且
            // 主键落地。两个次级唯一索引（draft_key / account_operation_key）由 V72 的
            // DO 块按「库内同名即跳过」存在性检查创建——本测试与 public 共用一个数据库
            // （主上下文已建过同名索引），合成 schema 重放会跳过，属已登记的行为边界：
            // 生产形制是每库单 schema（共库逻辑隔离靠 Flyway 历史表），跨 schema 重放
            // 只发生在本合成测试；旧迁移按规约不修改。
            assertThat(count(statement, "information_schema.tables",
                    "table_schema='" + schema + "' AND table_name='video_storyboard_workspace'")).isEqualTo(1);
            assertThat(count(statement, "pg_indexes",
                    "schemaname='" + schema + "' AND tablename='video_storyboard_workspace'"
                            + " AND indexname='video_storyboard_workspace_pkey'")).isEqualTo(1);
            // 任务书 #100 C100-09（TC-041）：V73 画布文档表同口径重放存在且主键/唯一索引落地
            //（次级唯一索引由 DO 块存在性检查创建，合成 schema 与 public 共库时跳过属登记边界）。
            assertThat(count(statement, "information_schema.tables",
                    "table_schema='" + schema + "' AND table_name='creation_canvas_document'")).isEqualTo(1);
            assertThat(count(statement, "pg_indexes",
                    "schemaname='" + schema + "' AND tablename='creation_canvas_document'"
                            + " AND indexname='creation_canvas_document_pkey'")).isEqualTo(1);
            // 任务书 #100 C100-11（TC-041）：V74 每镜来源表同口径重放存在且主键落地。
            assertThat(count(statement, "information_schema.tables",
                    "table_schema='" + schema + "' AND table_name='video_shot_media_source'")).isEqualTo(1);
            assertThat(count(statement, "pg_indexes",
                    "schemaname='" + schema + "' AND tablename='video_shot_media_source'"
                            + " AND indexname='video_shot_media_source_pkey'")).isEqualTo(1);
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

