package com.grassland.intelligence.hypit.job;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_job_action 仓储（任务书 #107-2 C107-14 / K05）：agent 每次工具调用的 持久动作行（input_hash
 * 定位、result_json 回执）。行不可变。
 */
@Component
public class HypitJobActionRepository {

	private final DatabaseClient db;

	public HypitJobActionRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ActionRow(UUID id, UUID jobId, int stepIndex, String kind, String state, String inputHash,
			String inputJson, String resultJson, Instant createdAt) {
	}

	private static final String COLS = """
			id::text, job_id::text, step_index, kind, state, input_hash, input_json::text,
			result_json::text, created_at
			""";

	public Mono<ActionRow> insert(UUID id, UUID jobId, int stepIndex, String kind, String state, String inputHash,
			String inputJson, String resultJson) {
		var statement = db.sql("""
				INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, input_json, result_json)
				VALUES (CAST(:id AS uuid), CAST(:job AS uuid), :step, :kind, :state, :hash,
				        CAST(:input AS jsonb), CAST(:result AS jsonb))
				ON CONFLICT (job_id, step_index) DO NOTHING
				RETURNING """ + " " + COLS).bind("id", id.toString()).bind("job", jobId.toString())
				.bind("step", stepIndex).bind("kind", kind).bind("state", state).bind("hash", inputHash)
				.bind("input", inputJson);
		statement = resultJson == null
				? statement.bindNull("result", String.class)
				: statement.bind("result", resultJson);
		return statement.map(HypitJobActionRepository::map).one().switchIfEmpty(
				// 同 (job, step) 已有行：读原行（动作幂等，不产生第二行）。
				db.sql("SELECT " + COLS + " FROM hypit_job_action"
						+ " WHERE job_id = CAST(:job AS uuid) AND step_index = :step").bind("job", jobId.toString())
						.bind("step", stepIndex).map(HypitJobActionRepository::map).one());
	}

	public Flux<ActionRow> findByJob(UUID jobId) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_job_action WHERE job_id = CAST(:job AS uuid)"
						+ " ORDER BY step_index, created_at")
				.bind("job", jobId.toString()).map(HypitJobActionRepository::map).all();
	}

	/** 当前槽位的既有动作行（幂等协议入口；无行返回空）。 */
	public Mono<ActionRow> findBySlot(UUID jobId, int stepIndex) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_job_action"
						+ " WHERE job_id = CAST(:job AS uuid) AND step_index = :step")
				.bind("job", jobId.toString()).bind("step", stepIndex).map(HypitJobActionRepository::map).one();
	}

	/**
	 * C107F2-15（RULE-09 步骤 2）：副作用前原子预留——先落 prepared 行（result_json 携带稳定
	 * operation_id），工具执行完成后经 {@link #transition} CAS 收敛。同 (job, step) 已有行时读原行
	 * （不产生第二行）；同 slot 不同 inputHash 由调用方判定冲突。
	 */
	public Mono<ActionRow> insertPrepared(UUID id, UUID jobId, int stepIndex, String kind, String inputHash,
			String inputJson, UUID operationId) {
		return db.sql("""
				INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, input_json, result_json)
				VALUES (CAST(:id AS uuid), CAST(:job AS uuid), :step, :kind, 'prepared', :hash,
				        CAST(:input AS jsonb), CAST(:result AS jsonb))
				ON CONFLICT (job_id, step_index) DO NOTHING
				RETURNING """ + " " + COLS).bind("id", id.toString()).bind("job", jobId.toString())
				.bind("step", stepIndex).bind("kind", kind).bind("hash", inputHash).bind("input", inputJson)
				.bind("result", "{\"operationId\":\"" + operationId + "\"}").map(HypitJobActionRepository::map).one()
				.switchIfEmpty(findBySlot(jobId, stepIndex));
	}

	/**
	 * prepared → 终态的 CAS 收敛：仅当行仍处 fromState 时写入（崩溃重放/并发下不覆盖他人收敛结果）。
	 */
	public Mono<ActionRow> transition(UUID id, String fromState, String toState, String resultJson) {
		return db
				.sql("UPDATE hypit_job_action SET state = :to, result_json = CAST(:result AS jsonb)"
						+ " WHERE id = CAST(:id AS uuid) AND state = :from RETURNING " + COLS)
				.bind("id", id.toString()).bind("from", fromState).bind("to", toState).bind("result", resultJson)
				.map(HypitJobActionRepository::map).one()
				.switchIfEmpty(db.sql("SELECT " + COLS + " FROM hypit_job_action WHERE id = CAST(:id AS uuid)")
						.bind("id", id.toString()).map(HypitJobActionRepository::map).one());
	}

	private static ActionRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new ActionRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("job_id", String.class)), row.get("step_index", Integer.class),
				row.get("kind", String.class), row.get("state", String.class), row.get("input_hash", String.class),
				row.get("input_json", String.class), row.get("result_json", String.class),
				row.get("created_at", java.time.Instant.class));
	}
}
