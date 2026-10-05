package com.grassland.intelligence.hypit.variant;

import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 变体持久层（任务书 #107-3 C107-19 / K05）：hypit_variant 行级读写。 state 机
 * draft→planned→queued→running→succeeded|failed|cancelled；attempt>0 每次重试 +1（旧
 * Build 引用清空，显式复用语义由调用方声明）。
 */
@Repository
public class HypitVariantRepository {

	private final DatabaseClient db;

	public HypitVariantRepository(DatabaseClient db) {
		this.db = db;
	}

	public record VariantRow(UUID id, UUID projectId, UUID batchJobId, int ordinal, long baseRevision,
			String parametersJson, String runFile, UUID planId, UUID buildId, String state, int attempt) {
	}

	private static final String COLS = """
			id::text, project_id::text, batch_job_id::text, ordinal, base_revision, parameters_json,
			run_file, plan_id::text, build_id::text, state, attempt
			""";

	private static VariantRow mapRow(io.r2dbc.spi.Readable row) {
		String plan = row.get("plan_id", String.class);
		String build = row.get("build_id", String.class);
		return new VariantRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("project_id", String.class)),
				UUID.fromString(row.get("batch_job_id", String.class)),
				row.get("ordinal", Integer.class) == null ? 0 : row.get("ordinal", Integer.class),
				row.get("base_revision", Long.class) == null ? 0L : row.get("base_revision", Long.class),
				row.get("parameters_json", String.class), row.get("run_file", String.class),
				plan == null ? null : UUID.fromString(plan), build == null ? null : UUID.fromString(build),
				row.get("state", String.class),
				row.get("attempt", Integer.class) == null ? 1 : row.get("attempt", Integer.class));
	}

	public Mono<VariantRow> insert(UUID id, UUID projectId, UUID batchJobId, int ordinal, long baseRevision,
			String parametersJson, String runFile) {
		return db.sql("""
				INSERT INTO hypit_variant(id, project_id, batch_job_id, ordinal, base_revision,
				parameters_json, run_file, state, attempt)
				VALUES (CAST(:id AS uuid), CAST(:p AS uuid), CAST(:b AS uuid), :ordinal, :rev,
				CAST(:params AS jsonb), :run, 'draft', 1) RETURNING """ + " " + COLS).bind("id", id.toString())
				.bind("p", projectId.toString()).bind("b", batchJobId.toString()).bind("ordinal", ordinal)
				.bind("rev", baseRevision).bind("params", parametersJson).bind("run", runFile)
				.map(HypitVariantRepository::mapRow).one();
	}

	public Mono<VariantRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_variant WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitVariantRepository::mapRow).one();
	}

	public Flux<VariantRow> listByProject(UUID projectId, int limit) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_variant WHERE project_id = CAST(:p AS uuid)"
						+ " ORDER BY batch_job_id, ordinal LIMIT :limit")
				.bind("p", projectId.toString()).bind("limit", limit).map(HypitVariantRepository::mapRow).all();
	}

	public Flux<VariantRow> listByBatch(UUID batchJobId) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_variant WHERE batch_job_id = CAST(:b AS uuid)"
						+ " ORDER BY ordinal")
				.bind("b", batchJobId.toString()).map(HypitVariantRepository::mapRow).all();
	}

	/**
	 * draft/planned → queued：CAS 绑定 plan（每变体独立计划）。 C107F3-11（W78，§13.3 增量四扩展）：retry
	 * 复位行（queued 且 plan_id 双空， 形态由 TC-F3-01-01 锁定）重新规划后也需落 plan——WHERE 放行该形态， 否则
	 * UPDATE 0 行 → 空 Mono → planVariant 502「变体计划未返回」（smoke12/13 两轮确定性复现的实证根因；已持
	 * plan_id 的在途 queued 行仍不重复绑定， 幂等闸保持）。
	 */
	public Mono<VariantRow> markQueued(UUID id, UUID planId) {
		return db.sql("""
				UPDATE hypit_variant SET plan_id = CAST(:plan AS uuid), state = 'queued', updated_at = now()
				WHERE id = CAST(:id AS uuid) AND (state IN ('draft', 'planned')
				OR (state = 'queued' AND plan_id IS NULL)) RETURNING """ + " " + COLS).bind("id", id.toString())
				.bind("plan", planId.toString()).map(HypitVariantRepository::mapRow).one();
	}

	/** queued → running：CAS 绑定 build（同批次同请求只有一个提交者）。 */
	public Mono<VariantRow> markRunning(UUID id, UUID buildId) {
		return db
				.sql("""
						UPDATE hypit_variant SET build_id = CAST(:b AS uuid), state = 'running', updated_at = now(), next_poll_at = now()
						WHERE id = CAST(:id AS uuid) AND state = 'queued' RETURNING """
						+ " " + COLS)
				.bind("id", id.toString()).bind("b", buildId.toString()).map(HypitVariantRepository::mapRow).one();
	}

	/** 终态收敛：succeeded/failed/cancelled；重试清空 build 引用并 +attempt。 */
	public Mono<VariantRow> markTerminal(UUID id, String state) {
		return db.sql("""
				UPDATE hypit_variant SET state = :state, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND state IN ('queued', 'running') RETURNING """ + " " + COLS)
				.bind("id", id.toString()).bind("state", state).map(HypitVariantRepository::mapRow).one();
	}

	/**
	 * 重试失败/已取消项（C107F2-26 步骤 3）：新 attempt、清空 plan/build、回 queued；
	 * 成功项与在途项拒绝（重复重试在状态闸被拒——attempt 不增）。
	 */
	public Mono<VariantRow> retry(UUID id) {
		return db.sql("""
				UPDATE hypit_variant SET state = 'queued', attempt = attempt + 1, plan_id = NULL,
				       build_id = NULL, updated_at = now(), next_poll_at = now(),
				                   observation_token = NULL, observation_lease_until = NULL,
				                   observation_failures = 0, last_observation_error = NULL
				WHERE id = CAST(:id AS uuid) AND state IN ('failed', 'cancelled') RETURNING """ + " " + COLS)
				.bind("id", id.toString()).map(HypitVariantRepository::mapRow).one();
	}

	/** 取消非终态项；已产生媒体的成功项保留（state 只在 queued/running 可取消）。 */
	public Mono<VariantRow> cancel(UUID id) {
		return db.sql("""
				UPDATE hypit_variant SET state = 'cancelled', updated_at = now()
				WHERE id = CAST(:id AS uuid) AND state IN ('draft', 'planned', 'queued', 'running')
				RETURNING """ + " " + COLS).bind("id", id.toString()).map(HypitVariantRepository::mapRow).one();
	}

	public record ObservationClaim(VariantRow variant, UUID token) {
	}

	/**
	 * Claim just before observation; one SQL statement, safe across workers and
	 * restarts.
	 */
	public Mono<ObservationClaim> claimObservation(UUID token) {
		return db.sql("""
				UPDATE hypit_variant SET observation_token = CAST(:token AS uuid),
				    observation_lease_until = now() + interval '45 seconds'
				WHERE id IN (
				    SELECT id FROM hypit_variant
				    WHERE state IN ('queued', 'running') AND build_id IS NOT NULL
				      AND next_poll_at <= now()
				      AND (observation_lease_until IS NULL OR observation_lease_until <= now())
				    ORDER BY next_poll_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
				) RETURNING """ + " " + COLS).bind("token", token.toString()).map(HypitVariantRepository::mapRow).one()
				.map(row -> new ObservationClaim(row, token));
	}

	/**
	 * Token + attempt + Build fence prevents an expired observation changing a
	 * retried variant.
	 */
	public Mono<VariantRow> finishObservation(ObservationClaim claim, String terminalState, String errorCode) {
		var row = claim.variant();
		var query = db.sql("""
				UPDATE hypit_variant SET state = COALESCE(:terminal, state),
				    updated_at = CASE WHEN CAST(:terminal AS varchar) IS NULL THEN updated_at ELSE now() END,
				    last_observed_at = now(), observation_token = NULL, observation_lease_until = NULL,
				    observation_failures = CASE WHEN CAST(:error AS varchar) IS NULL THEN 0
				        ELSE LEAST(observation_failures + 1, 10) END,
				    last_observation_error = :error,
				    next_poll_at = now() + (CASE WHEN CAST(:error AS varchar) IS NULL THEN 5
				        ELSE LEAST(300, 5 * power(2, LEAST(observation_failures, 6))) END * interval '1 second')
				WHERE id = CAST(:id AS uuid) AND observation_token = CAST(:token AS uuid)
				    AND attempt = :attempt AND build_id = CAST(:build AS uuid)
				    AND state IN ('queued', 'running')
				RETURNING """ + " " + COLS).bind("id", row.id().toString()).bind("token", claim.token().toString())
				.bind("attempt", row.attempt()).bind("build", row.buildId().toString());
		query = terminalState == null
				? query.bindNull("terminal", String.class)
				: query.bind("terminal", terminalState);
		query = errorCode == null ? query.bindNull("error", String.class) : query.bind("error", errorCode);
		return query.map(HypitVariantRepository::mapRow).one();
	}

	public Mono<Long> countByProject(UUID projectId) {
		return db.sql("SELECT count(*) AS n FROM hypit_variant WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one();
	}
}
