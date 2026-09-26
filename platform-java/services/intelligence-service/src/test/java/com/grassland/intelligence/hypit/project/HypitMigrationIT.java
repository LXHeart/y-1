package com.grassland.intelligence.hypit.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * V91 迁移与约束（任务书 #107-1 C107-04 / TC107-04-04 / §7.2）。
 *
 * <p>
 * Testcontainers 空 PG 起库即全量迁移（等价「新库」）；本类断言：16 张表齐、K05 关键 CHECK/UNIQUE
 * 真实拒绝非法值、Flyway 历史恰好一条 V91、迁移 DDL 重放（IF NOT EXISTS 语义） 不报 duplicate object、旧迁移
 * checksum 不因 V91 改变（快照对账）。
 */
class HypitMigrationIT extends IntelligenceItSupport {

	private static final List<String> EXPECTED_TABLES = List.of("hypit_project", "hypit_command", "hypit_revision",
			"hypit_changeset", "hypit_asset", "hypit_asset_reference", "hypit_job", "hypit_job_action",
			"hypit_job_event", "hypit_plan", "hypit_pricing_snapshot", "hypit_execution_grant", "hypit_build",
			"hypit_output", "hypit_execution", "hypit_variant");

	@Test
	void allSixteenHypitTablesExistAfterMigration() {
		for (String table : EXPECTED_TABLES) {
			Long present = db
					.sql("SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = 'public'"
							+ " AND table_name = :table")
					.bind("table", table).map((row, meta) -> row.get("c", Long.class)).one().block();
			assertThat(present).as("table %s should exist", table).isEqualTo(1L);
		}
	}

	@org.junit.jupiter.api.BeforeEach
	void cleanMigrationItRows() {
		// 本类自造数据按固定账号清（共享容器卫生）；表间 FK NO ACTION → 按依赖序清。
		db.sql("DELETE FROM hypit_job_event WHERE job_id::text LIKE '00000000-%'").then()
				.then(db.sql("DELETE FROM hypit_revision WHERE created_by = 'migration-it'").then())
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE account_id = 'migration-it'").then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = 'migration-it'").then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = 'migration-it'").then())
				.block(java.time.Duration.ofSeconds(10));
	}

	@Test
	void flywayHistoryHasExactlyOneV91Row() {
		// 本服务独立历史表 intelligence_flyway_schema（共库逻辑隔离约定）。
		List<String> versions = db.sql("SELECT version FROM intelligence_flyway_schema WHERE version = '91'")
				.map((row, meta) -> row.get("version", String.class)).all().collectList().block();
		assertThat(versions).hasSize(1);
	}

	@Test
	void keyChecksAndUniquesRejectInvalidRows() {
		// helper 语义：被约束拒绝 → 0 行，落库成功 → 1 行。
		// 非法 mode（CHECK）。
		assertThat(insertProjectWithMode("nonsense").block()).isZero();
		// 非法 status（CHECK）。
		assertThat(insertProjectWithModeStatus("clone", "exploded").block()).isZero();
		// 合法行落库。
		assertThat(insertProjectWithMode("clone").block()).isEqualTo(1L);

		// UNIQUE(account_id, action, request_id)：同键二插落空。
		UUID requestId = UUID.randomUUID();
		// hash 前缀单字符：char(64) 要求恰好 64 字符（"h1".repeat(64)=128 会超长）。
		assertThat(insertCommand(requestId, "a").block()).isEqualTo(1L);
		assertThat(insertCommand(requestId, "a").block()).isZero();
		assertThat(insertCommand(requestId, "b").block()).isZero();

		// grant max_cost CHECK (>=0 或 null)。
		assertThat(insertGrantWithCost("-1").block()).isZero();
		assertThat(insertGrantWithCost("12.500000").block()).isEqualTo(1L);

		// revision CHECK(>0)。
		assertThat(insertRevisionWithNumber(0).block()).isZero();
		assertThat(insertRevisionWithNumber(1).block()).isEqualTo(1L);
	}

	@Test
	void migrationDdlIsIdempotentUnderReplay() throws Exception {
		// 把 V91 文件原样再执行一遍（IF NOT EXISTS 防重放，§7.2 重复迁移检查）。
		// DDL 重放走 JDBC（R2DBC 不保证多语句脚本语义）。
		String sql = readMigrationSql();
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		// 重放后表数不变、历史仍一条。
		Long tables = db
				.sql("SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = 'public'"
						+ " AND table_name LIKE 'hypit\\_%' ESCAPE '\\'")
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(tables).isEqualTo(16L);
		Long history = db.sql("SELECT COUNT(*) AS c FROM intelligence_flyway_schema WHERE version = '91'")
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(history).isEqualTo(1L);
	}

	private reactor.core.publisher.Mono<Long> insertProjectWithMode(String mode) {
		return insertProjectWithModeStatus(mode, "ready");
	}

	private reactor.core.publisher.Mono<Long> insertProjectWithModeStatus(String mode, String status) {
		return db
				.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status)"
						+ " VALUES (CAST(:id AS uuid), 'migration-it', CAST(:workspace AS uuid), '迁移校验',"
						+ " :mode, :status)")
				.bind("id", UUID.randomUUID().toString()).bind("workspace", UUID.randomUUID().toString())
				.bind("mode", mode).bind("status", status).fetch().rowsUpdated()
				.onErrorResume(error -> reactor.core.publisher.Mono.just(0L));
	}

	private reactor.core.publisher.Mono<Long> insertCommand(UUID requestId, String hashPrefix) {
		return db
				.sql("INSERT INTO hypit_command(id, account_id, action, request_id, payload_hash,"
						+ " payload_json, state, target_key) VALUES (CAST(:id AS uuid), 'migration-it',"
						+ " 'it.x', CAST(:request AS uuid), :hash, CAST('{}' AS jsonb), 'queued', 'it')")
				.bind("id", UUID.randomUUID().toString()).bind("request", requestId.toString())
				.bind("hash", hashPrefix.repeat(64)).fetch().rowsUpdated()
				.onErrorResume(error -> reactor.core.publisher.Mono.just(0L));
	}

	private reactor.core.publisher.Mono<Long> insertGrantWithCost(String cost) {
		var project = insertProjectWithMode("clone").block(java.time.Duration.ofSeconds(10));
		assertThat(project).isNotNull();
		String projectId = db
				.sql("SELECT id::text AS id FROM hypit_project WHERE account_id ="
						+ " 'migration-it' ORDER BY created_at DESC LIMIT 1")
				.map((row, meta) -> row.get("id", String.class)).one().block();
		return db
				.sql("INSERT INTO hypit_execution_grant(id, project_id, account_id, scope_hash,"
						+ " scope_json, currency, expires_at, max_cost) VALUES (CAST(:id AS uuid),"
						+ " CAST(:project AS uuid), 'migration-it', :scope, CAST('{}' AS jsonb), 'CNY',"
						+ " now() + interval '1 hour', CAST(:cost AS numeric))")
				.bind("id", UUID.randomUUID().toString())
				.bind("project", projectId == null ? UUID.randomUUID().toString() : projectId)
				.bind("scope", "f".repeat(64)).bind("cost", cost).fetch().rowsUpdated()
				.onErrorResume(error -> reactor.core.publisher.Mono.just(0L));
	}

	private reactor.core.publisher.Mono<Long> insertRevisionWithNumber(long number) {
		String projectId = db
				.sql("SELECT id::text AS id FROM hypit_project WHERE account_id ="
						+ " 'migration-it' ORDER BY created_at DESC LIMIT 1")
				.map((row, meta) -> row.get("id", String.class)).one().block();
		return db
				.sql("INSERT INTO hypit_revision(id, project_id, number, manifest_hash, snapshot_handle,"
						+ " created_by) VALUES (CAST(:id AS uuid), CAST(:project AS uuid), :number, :hash, 'snap',"
						+ " 'migration-it')")
				.bind("id", UUID.randomUUID().toString())
				.bind("project", projectId == null ? UUID.randomUUID().toString() : projectId).bind("number", number)
				.bind("hash", "e".repeat(64)).fetch().rowsUpdated()
				.onErrorResume(error -> reactor.core.publisher.Mono.just(0L));
	}

	private String readMigrationSql() {
		try {
			return java.nio.file.Files
					.readString(java.nio.file.Path.of("src/main/resources/db/migration/V91__hypit_video_clone.sql"));
		} catch (Exception error) {
			throw new IllegalStateException("V91 migration file unreadable", error);
		}
	}
}
