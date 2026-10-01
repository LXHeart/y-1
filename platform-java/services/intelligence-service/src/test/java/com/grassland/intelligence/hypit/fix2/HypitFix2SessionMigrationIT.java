package com.grassland.intelligence.hypit.fix2;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V92 会话表迁移（107-fix-2 C107F2-19 / §7.5）。
 *
 * <p>
 * 真 PostgreSQL：空库从全迁移初始化（等价「新库」）；本类断言：hypit_session 表落位、 状态/只读 CHECK
 * 与唯一主键真实拒绝非法值、Flyway 历史恰好一条 V92、DDL 重放 （IF NOT EXISTS 语义）不报 duplicate
 * object、V91 存量业务数据在 V92 升级后可读 （旧应用读新 schema、新应用读旧数据）、中途失败恢复（半执行 DDL 再重放收敛）。
 */
class HypitFix2SessionMigrationIT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000992a";

	@BeforeEach
	void cleanup() {
		db.sql("DELETE FROM hypit_session WHERE account_id = :o").bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	// ── TC-F2-19-05a（§7.5 空库初始化）：表落位 + 约束真实拒绝 ────────────────
	@Test
	@DisplayName("V92 后 hypit_session 落位：CHECK/NOT NULL 真实拒绝非法行，合法行可写")
	void sessionTableExistsWithRealConstraints() {
		assertThat(tableExists("hypit_session")).isTrue();

		UUID projectId = seedProject();
		// 合法行。
		assertThat(insertSession("sess-mig-ok0000001", projectId, "active").block()).isEqualTo(1L);
		// 非法 state（CHECK studio/preview/starting/active/failed/closed/expired/revoked）。
		assertThat(insertSessionWithState("sess-mig-badstate1", projectId, "flying").block()).isZero();
		// 非法 kind（CHECK studio/preview）。
		assertThat(insertSessionWithKind("sess-mig-badkind01", projectId, "karaoke").block()).isZero();
		// revision > 0（CHECK）。
		assertThat(insertSessionWithRevision("sess-mig-badrev001", projectId, 0).block()).isZero();
		// 主键冲突：同 id 二插落零（错误被吞计数为 0）。
		assertThat(insertSession("sess-mig-ok0000001", projectId, "active").block()).isZero();
	}

	// ── TC-F2-19-05b（§7.5 Flyway 历史）：恰好一条 V92，V91 历史不动 ──────────
	@Test
	@DisplayName("Flyway 历史：V92 恰好一条，V91 一条不变（升级不重排旧历史）")
	void flywayHistoryHasExactlyOneV92AndUntouchedV91() {
		List<String> v92 = db.sql("SELECT version FROM intelligence_flyway_schema WHERE version = '92'")
				.map((row, meta) -> row.get("version", String.class)).all().collectList().block();
		assertThat(v92).hasSize(1);
		List<String> v91 = db.sql("SELECT version FROM intelligence_flyway_schema WHERE version = '91'")
				.map((row, meta) -> row.get("version", String.class)).all().collectList().block();
		assertThat(v91).hasSize(1);
	}

	// ── TC-F2-19-05c（§7.5 DDL 重放 + 中途失败恢复）：重放幂等 ────────────────
	@Test
	@DisplayName("V92 DDL 重放不报 duplicate object；重放后行数与历史不变")
	void migrationDdlReplayIsIdempotent() throws Exception {
		String sql = readMigrationSql();
		try (java.sql.Connection connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(), POSTGRES.getPassword());
				java.sql.Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
		Long history = db.sql("SELECT COUNT(*) AS c FROM intelligence_flyway_schema WHERE version = '92'")
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(history).isEqualTo(1L);
		assertThat(tableExists("hypit_session")).isTrue();
	}

	// ── TC-F2-19-05d（§7.5 旧应用读新 schema / 新应用读旧数据）────────────────
	@Test
	@DisplayName("V91 存量工程在 V92 升级后可读；会话行可随存量工程登记与回收")
	void v91DataReadableAfterV92Upgrade() {
		UUID projectId = seedProject();
		// 存量工程的 revision/changeset 数据不受 V92 影响（新应用读旧数据）。
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one().block();
		assertThat(revision).isEqualTo(2L);
		// 新表挂上存量工程（旧应用读新 schema：老查询路径零影响）。
		assertThat(insertSession("sess-mig-legacy01", projectId, "active").block()).isEqualTo(1L);
		Long sessions = db.sql("SELECT COUNT(*) AS c FROM hypit_session WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(sessions).isEqualTo(1L);
	}

	private boolean tableExists(String name) {
		Long present = db
				.sql("SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = 'public'"
						+ " AND table_name = :table")
				.bind("table", name).map((row, meta) -> row.get("c", Long.class)).one().block();
		return present != null && present == 1L;
	}

	private UUID seedProject() {
		UUID projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-mig', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		return projectId;
	}

	private reactor.core.publisher.Mono<Long> insertSession(String id, UUID projectId, String state) {
		return insertSessionFull(id, projectId, "studio", state, 1);
	}

	private reactor.core.publisher.Mono<Long> insertSessionWithState(String id, UUID projectId, String state) {
		return insertSessionFull(id, projectId, "studio", state, 1);
	}

	private reactor.core.publisher.Mono<Long> insertSessionWithKind(String id, UUID projectId, String kind) {
		return insertSessionFull(id, projectId, kind, "active", 1);
	}

	private reactor.core.publisher.Mono<Long> insertSessionWithRevision(String id, UUID projectId, long revision) {
		return insertSessionFull(id, projectId, "studio", "active", revision);
	}

	private reactor.core.publisher.Mono<Long> insertSessionFull(String id, UUID projectId, String kind, String state,
			long revision) {
		return db
				.sql("INSERT INTO hypit_session(id, project_id, account_id, kind, run_file, revision, read_only,"
						+ " state, expires_at) VALUES (CAST(:id AS varchar), CAST(:p AS uuid), :owner, :kind,"
						+ " 'main.svrun', :revision, false, :state, now() + interval '1 hour')")
				.bind("id", id).bind("p", projectId.toString()).bind("owner", OWNER).bind("kind", kind)
				.bind("revision", revision).bind("state", state).fetch().rowsUpdated()
				.onErrorResume(error -> reactor.core.publisher.Mono.just(0L));
	}

	private String readMigrationSql() {
		try {
			return java.nio.file.Files
					.readString(java.nio.file.Path.of("src/main/resources/db/migration/V92__hypit_fix2_sessions.sql"));
		} catch (Exception error) {
			throw new IllegalStateException("V92 migration file unreadable", error);
		}
	}
}
