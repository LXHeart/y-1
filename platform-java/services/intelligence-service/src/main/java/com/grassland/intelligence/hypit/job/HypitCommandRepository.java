package com.grassland.intelligence.hypit.job;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import com.grassland.intelligence.security.IntelligenceException;

/**
 * hypit_command 仓储（任务书 #107-1 C107-04 / K06.1 幂等命令）。
 *
 * <p>
 * 写入先于任何外部副作用：同 {@code (account_id, action, request_id)} 的重复请求读原行； hash
 * 相同复用同一资源，hash 不同 409 {@code hypit_idempotency_conflict}，不执行副作用。
 */
@Component
public class HypitCommandRepository {

	private final DatabaseClient db;

	public HypitCommandRepository(DatabaseClient db) {
		this.db = db;
	}

	public record CommandRow(UUID id, String accountId, UUID projectId, String targetKey, String action, UUID requestId,
			String payloadHash, String payloadJson, String state, String resultJson, String errorCode,
			String errorMessage, Instant createdAt, Instant updatedAt) {
	}

	public record Accepted(boolean existing, CommandRow row) {
	}

	private static final String COLS = """
			id::text, account_id, project_id::text, target_key, action, request_id::text, payload_hash,
			payload_json::text, state, result_json::text, error_code, error_message, created_at, updated_at
			""";

	/**
	 * K06.1 幂等落库：UNIQUE(account_id, action, request_id)。返回 existing=true 表示
	 * 命中已存在命令（调用方必须比对 payloadHash 决定复用或 409）。
	 */
	public Mono<Accepted> insert(String accountId, String action, UUID requestId, String targetKey, String payloadHash,
			String payloadJson, UUID projectId) {
		var statement = db
				.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
						+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :account,"
						+ " CAST(:project AS uuid), :target, :action, CAST(:request AS uuid), :hash,"
						+ " CAST(:payload AS jsonb), 'queued') ON CONFLICT (account_id, action, request_id)"
						+ " DO NOTHING RETURNING " + COLS)
				.bind("id", UUID.randomUUID().toString()).bind("account", accountId).bind("target", targetKey)
				.bind("action", action).bind("request", requestId.toString()).bind("hash", payloadHash)
				.bind("payload", payloadJson);
		// R2DBC null 绑定须显式类型。
		statement = projectId == null
				? statement.bindNull("project", String.class)
				: statement.bind("project", projectId.toString());
		return statement.map(HypitCommandRepository::mapRow).one().map(row -> new Accepted(false, row)).switchIfEmpty(
				Mono.defer(() -> findByKey(accountId, action, requestId).map(row -> new Accepted(true, row))));
	}

	public Mono<CommandRow> findByKey(String accountId, String action, UUID requestId) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_command WHERE account_id = :account"
						+ " AND action = :action AND request_id = CAST(:request AS uuid)")
				.bind("account", accountId).bind("action", action).bind("request", requestId.toString())
				.map(HypitCommandRepository::mapRow).one();
	}

	public Mono<CommandRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_command WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitCommandRepository::mapRow).one();
	}

	public Mono<Long> markState(UUID id, String state) {
		return db.sql("UPDATE hypit_command SET state = :state, updated_at = now()" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("state", state).fetch().rowsUpdated();
	}

	/** 终态回执回写（幂等重放的依据；重复回写只覆盖相同内容）。 */
	public Mono<Long> saveResult(UUID id, String state, String resultJson) {
		return db
				.sql("UPDATE hypit_command SET state = :state, result_json = CAST(:result AS jsonb),"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND result_json IS NULL")
				.bind("id", id.toString()).bind("state", state).bind("result", resultJson).fetch().rowsUpdated();
	}

	public static IntelligenceException conflict(CommandRow existing) {
		return new IntelligenceException(409, "hypit_idempotency_conflict", "同 requestId 已提交不同内容：" + existing.action());
	}

	private static CommandRow mapRow(io.r2dbc.spi.Readable row) {
		return new CommandRow(UUID.fromString(row.get("id", String.class)), row.get("account_id", String.class),
				row.get("project_id", String.class) == null
						? null
						: UUID.fromString(row.get("project_id", String.class)),
				row.get("target_key", String.class), row.get("action", String.class),
				UUID.fromString(row.get("request_id", String.class)), row.get("payload_hash", String.class),
				row.get("payload_json", String.class), row.get("state", String.class),
				row.get("result_json", String.class), row.get("error_code", String.class),
				row.get("error_message", String.class), row.get("created_at", Instant.class),
				row.get("updated_at", Instant.class));
	}
}
