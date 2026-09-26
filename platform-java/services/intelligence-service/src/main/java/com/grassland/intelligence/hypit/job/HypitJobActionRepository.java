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

	private static ActionRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new ActionRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("job_id", String.class)), row.get("step_index", Integer.class),
				row.get("kind", String.class), row.get("state", String.class), row.get("input_hash", String.class),
				row.get("input_json", String.class), row.get("result_json", String.class),
				row.get("created_at", java.time.Instant.class));
	}
}
