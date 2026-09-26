package com.grassland.intelligence.hypit.job;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_job 仓储（任务书 #107-1 C107-04 / K05/K06.1）。
 *
 * <p>
 * durable claim：{@code FOR UPDATE SKIP LOCKED} 认领，lease 30s、续租 10s 步进由调用方
 * 控制；崩溃后租约过期可被其他 worker 接手。project_id 可空（全局运维任务）。
 */
@Component
public class HypitJobRepository {

	private final DatabaseClient db;

	public HypitJobRepository(DatabaseClient db) {
		this.db = db;
	}

	public record JobRow(UUID id, UUID commandId, UUID projectId, String accountId, String kind, String state,
			String phase, String progressJson, String checkpointJson, Long baseRevision, UUID grantId, int stepIndex,
			int attempt, UUID leaseOwner, Instant leaseUntil, long version, Instant cancelRequestedAt,
			String blockedReason, String errorCode, String errorMessage, Instant createdAt, Instant updatedAt) {

		public boolean terminal() {
			return state.equals("succeeded") || state.equals("failed") || state.equals("cancelled");
		}

		public boolean active() {
			return state.equals("queued") || state.equals("running") || state.equals("waiting_input")
					|| state.equals("cancel_requested");
		}
	}

	private static final String COLS = """
			id::text, command_id::text, project_id::text, account_id, kind, state, phase, progress_json::text,
			checkpoint_json::text, base_revision, grant_id::text, step_index, attempt, lease_owner::text,
			lease_until, version, cancel_requested_at, blocked_reason, error_code, error_message,
			created_at, updated_at
			""";

	/** 同事务插入（与 command 一起）；command_id 唯一防双发。 */
	public Mono<JobRow> insert(JobRow row) {
		var statement = db
				.sql("INSERT INTO hypit_job(id, command_id, project_id, account_id, kind, state,"
						+ " phase, checkpoint_json, base_revision, grant_id) VALUES (CAST(:id AS uuid),"
						+ " CAST(:command AS uuid), CAST(:project AS uuid), :account, :kind, :state, :phase,"
						+ " CAST(:checkpoint AS jsonb), :baseRevision, CAST(:grant AS uuid)) RETURNING " + COLS)
				.bind("id", row.id().toString()).bind("account", row.accountId()).bind("kind", row.kind())
				.bind("state", row.state()).bind("phase", row.phase() == null ? "pending" : row.phase())
				.bind("baseRevision", row.baseRevision() == null ? -1L : row.baseRevision());
		statement = row.commandId() == null
				? statement.bindNull("command", String.class)
				: statement.bind("command", row.commandId().toString());
		statement = row.projectId() == null
				? statement.bindNull("project", String.class)
				: statement.bind("project", row.projectId().toString());
		statement = row.checkpointJson() == null
				? statement.bindNull("checkpoint", String.class)
				: statement.bind("checkpoint", row.checkpointJson());
		statement = row.grantId() == null
				? statement.bindNull("grant", String.class)
				: statement.bind("grant", row.grantId().toString());
		return statement.map(HypitJobRepository::mapRow).one();
	}

	public Mono<JobRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_job WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitJobRepository::mapRow).one();
	}

	/** 活跃工作探测（删除守卫，04.8）。 */
	public Mono<Boolean> hasActiveWork(UUID projectId) {
		return db
				.sql("SELECT 1 FROM hypit_job WHERE project_id = CAST(:project AS uuid) AND state IN"
						+ " ('queued', 'running', 'waiting_input', 'cancel_requested') LIMIT 1")
				.bind("project", projectId.toString()).map(row -> true).one().defaultIfEmpty(false);
	}

	/** FOR UPDATE SKIP LOCKED 认领（K06.1.5）。 */
	public Flux<JobRow> claimDue(UUID leaseOwner, Instant leaseUntil, int limit) {
		return db
				.sql("UPDATE hypit_job SET state = 'running', lease_owner = CAST(:owner AS uuid),"
						+ " lease_until = :lease, attempt = attempt, updated_at = now() WHERE id IN (SELECT id"
						+ " FROM hypit_job WHERE state = 'queued' ORDER BY created_at LIMIT :limit"
						+ " FOR UPDATE SKIP LOCKED) RETURNING " + COLS)
				.bind("owner", leaseOwner.toString()).bind("lease", leaseUntil).bind("limit", limit)
				.map(HypitJobRepository::mapRow).all();
	}

	/** 续租（仅限当前 owner 持有的租约值，防止过期后被他人重领还续上）。 */
	public Mono<Long> renewLease(UUID id, UUID leaseOwner, Instant leaseUntil) {
		return db
				.sql("UPDATE hypit_job SET lease_until = :lease, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND lease_owner = CAST(:owner AS uuid)")
				.bind("id", id.toString()).bind("owner", leaseOwner.toString()).bind("lease", leaseUntil).fetch()
				.rowsUpdated();
	}

	public Mono<Long> updateState(UUID id, String state, String errorCode, String errorMessage) {
		var statement = db
				.sql("UPDATE hypit_job SET state = :state, error_code = :code,"
						+ " error_message = :message, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("state", state);
		statement = errorCode == null ? statement.bindNull("code", String.class) : statement.bind("code", errorCode);
		statement = errorMessage == null
				? statement.bindNull("message", String.class)
				: statement.bind("message", errorMessage);
		return statement.fetch().rowsUpdated();
	}

	public Mono<Long> saveCheckpoint(UUID id, String checkpointJson) {
		return db
				.sql("UPDATE hypit_job SET checkpoint_json = CAST(:checkpoint AS jsonb), updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("checkpoint", checkpointJson).fetch().rowsUpdated();
	}

	private static JobRow mapRow(io.r2dbc.spi.Readable row) {
		String commandId = row.get("command_id", String.class);
		String projectId = row.get("project_id", String.class);
		String grantId = row.get("grant_id", String.class);
		String leaseOwner = row.get("lease_owner", String.class);
		Long baseRevision = row.get("base_revision", Long.class);
		return new JobRow(UUID.fromString(row.get("id", String.class)),
				commandId == null ? null : UUID.fromString(commandId),
				projectId == null ? null : UUID.fromString(projectId), row.get("account_id", String.class),
				row.get("kind", String.class), row.get("state", String.class), row.get("phase", String.class),
				row.get("progress_json", String.class), row.get("checkpoint_json", String.class),
				baseRevision != null && baseRevision >= 0 ? baseRevision : null,
				grantId == null ? null : UUID.fromString(grantId),
				row.get("step_index", Integer.class) == null ? 0 : row.get("step_index", Integer.class),
				row.get("attempt", Integer.class) == null ? 1 : row.get("attempt", Integer.class),
				leaseOwner == null ? null : UUID.fromString(leaseOwner), row.get("lease_until", Instant.class),
				row.get("version", Long.class) == null ? 1L : row.get("version", Long.class),
				row.get("cancel_requested_at", Instant.class), row.get("blocked_reason", String.class),
				row.get("error_code", String.class), row.get("error_message", String.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}

	public record ClaimStats(int claimed) {
	}

	public Mono<List<JobRow>> claimDueList(UUID leaseOwner, Instant leaseUntil, int limit) {
		return claimDue(leaseOwner, leaseUntil, limit).collectList();
	}
}
