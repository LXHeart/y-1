package com.grassland.intelligence.hypit.build;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_build 持久面（任务书 #107-1 §7.1 K05 / C107-09 卡步骤 09.1/09.4/09.6）。
 *
 * <p>
 * 公共 Build id 提交前创建；engineBuildId 是 sidecar 分配的原生 id（nullable UNIQUE）。 lifecycle/outcome
 * 只前进不倒退：CAS 迁移按「期望当前 lifecycle + 已知 outcome 不被覆盖」执行，迟到观察写不进终态。
 */
@Component
public class HypitBuildRepository {

	private final DatabaseClient db;

	public HypitBuildRepository(DatabaseClient db) {
		this.db = db;
	}

	public record BuildRow(UUID id, String engineBuildId, UUID commandId, UUID projectId, long revision, UUID planId,
			String runFile, String lifecycle, String outcome, String resultLocationJson, Instant submittedAt,
			Instant finishedAt, Instant createdAt, Instant updatedAt) {
	}

	private static final String COLS = """
			id::text, engine_build_id, command_id::text, project_id::text, revision, plan_id::text,
			run_file, lifecycle, outcome, result_location::text, submitted_at, finished_at, created_at, updated_at
			""";

	private static final List<String> LIFECYCLE_ORDER = List.of("submitting", "active", "execution_decided",
			"result_pending", "finished", "submission_incomplete");

	public static int lifecycleRank(String lifecycle) {
		int index = LIFECYCLE_ORDER.indexOf(lifecycle);
		return index < 0 ? 0 : index;
	}

	/** 提交即登记归属（commandId UNIQUE 保护重复派发；冲突读原行）。 */
	public Mono<BuildRow> insert(UUID id, UUID commandId, UUID projectId, long revision, UUID planId, String runFile) {
		return db.sql("""
				INSERT INTO hypit_build(id, command_id, project_id, revision, plan_id, run_file, lifecycle)
				VALUES (CAST(:id AS uuid), CAST(:command AS uuid), CAST(:project AS uuid), :revision,
				        CAST(:plan AS uuid), :runFile, 'submitting')
				ON CONFLICT (command_id) DO NOTHING
				RETURNING """ + " " + COLS)
				.bind("id", id.toString()).bind("command", commandId.toString()).bind("project", projectId.toString())
				.bind("revision", revision).bind("plan", planId.toString()).bind("runFile", runFile)
				.map(HypitBuildRepository::map).one()
				.switchIfEmpty(findByCommandId(commandId));
	}

	public Mono<BuildRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_build WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitBuildRepository::map).one();
	}

	public Mono<BuildRow> findByCommandId(UUID commandId) {
		return db.sql("SELECT " + COLS + " FROM hypit_build WHERE command_id = CAST(:id AS uuid)")
				.bind("id", commandId.toString()).map(HypitBuildRepository::map).one();
	}

	/** Build 提交 job 的 id（hypit_job.command_id 与 build.command_id 同源）。 */
	public Mono<UUID> jobIdByCommand(UUID commandId) {
		return db.sql("SELECT id::text FROM hypit_job WHERE command_id = CAST(:c AS uuid)")
				.bind("c", commandId.toString())
				.map((row, meta) -> UUID.fromString(row.get("id", String.class))).one();
	}

	/** owner 校验在查询里完成（join project 的 account_id），不依赖应用层内存态。 */
	public Mono<BuildRow> findOwned(String accountId, UUID buildId) {
		return db.sql("SELECT b.id::text, b.engine_build_id, b.command_id::text, b.project_id::text, b.revision,"
				+ " b.plan_id::text, b.run_file, b.lifecycle, b.outcome, b.result_location::text,"
				+ " b.submitted_at, b.finished_at, b.created_at, b.updated_at"
				+ " FROM hypit_build b JOIN hypit_project p ON p.id = b.project_id"
				+ " WHERE b.id = CAST(:id AS uuid) AND p.account_id = :account")
				.bind("id", buildId.toString()).bind("account", accountId).map(HypitBuildRepository::map).one();
	}

	public Flux<BuildRow> listByProject(UUID projectId, int limit) {
		return db.sql("SELECT " + COLS + " FROM hypit_build WHERE project_id = CAST(:project AS uuid)"
				+ " ORDER BY created_at DESC, id LIMIT :limit")
				.bind("project", projectId.toString()).bind("limit", limit).map(HypitBuildRepository::map).all();
	}

	/** 观察循环候选：非终态生命周期（submission_incomplete 由结果面 C10 收口）。 */
	public Flux<BuildRow> findObservable(int limit) {
		return db.sql("SELECT " + COLS + " FROM hypit_build"
				+ " WHERE lifecycle IN ('submitting', 'active', 'execution_decided', 'result_pending')"
				+ " ORDER BY created_at LIMIT :limit")
				.bind("limit", limit).map(HypitBuildRepository::map).all();
	}

	/**
	 * 09.4 前进式 CAS：只有 lifecycle 前进（或首次补 engineBuildId/finishedAt）才写入；
	 * outcome 只补空（COALESCE 保留已定事实），绝不被新观察翻转。Java 侧先按 rank 过滤
	 * 无效前进（同值刷新除外），SQL 以期望 lifecycle 兜底并发。
	 */
	public Mono<Boolean> advance(UUID id, String expectedLifecycle, String nextLifecycle, String outcome,
			String engineBuildId, Instant finishedAt) {
		if (lifecycleRank(nextLifecycle) < lifecycleRank(expectedLifecycle)) {
			return Mono.just(false);
		}
		var statement = db.sql("""
				UPDATE hypit_build SET
				  lifecycle = :next,
				  outcome = COALESCE(outcome, :outcome),
				  engine_build_id = COALESCE(engine_build_id, :engine),
				  finished_at = COALESCE(finished_at, :finished),
				  updated_at = now()
				WHERE id = CAST(:id AS uuid) AND lifecycle = :expected
				""").bind("id", id.toString()).bind("next", nextLifecycle).bind("expected", expectedLifecycle);
		statement = outcome == null ? statement.bindNull("outcome", String.class) : statement.bind("outcome", outcome);
		statement = engineBuildId == null || engineBuildId.isBlank()
				? statement.bindNull("engine", String.class)
				: statement.bind("engine", engineBuildId);
		statement = finishedAt == null ? statement.bindNull("finished", Instant.class)
				: statement.bind("finished", finishedAt);
		return statement.fetch().rowsUpdated().map(updated -> updated > 0);
	}

	private static BuildRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new BuildRow(UUID.fromString(row.get("id", String.class)),
				row.get("engine_build_id", String.class), UUID.fromString(row.get("command_id", String.class)),
				UUID.fromString(row.get("project_id", String.class)), row.get("revision", Long.class),
				row.get("plan_id", String.class) == null ? null : UUID.fromString(row.get("plan_id", String.class)),
				row.get("run_file", String.class), row.get("lifecycle", String.class),
				row.get("outcome", String.class),
				row.get("result_location", String.class), row.get("submitted_at", Instant.class),
				row.get("finished_at", Instant.class), row.get("created_at", Instant.class),
				row.get("updated_at", Instant.class));
	}
}
