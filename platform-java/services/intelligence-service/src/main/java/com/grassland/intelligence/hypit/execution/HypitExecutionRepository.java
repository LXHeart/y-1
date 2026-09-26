package com.grassland.intelligence.hypit.execution;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * hypit_execution / hypit_execution_grant 仓储（任务书 #107-1 C107-07 / K12）。
 *
 * <p>
 * 外部执行授权链的持久面：grant 是 scope/预算/有效期的不可变授权记录； execution 每个 operationId 一行，从
 * prepared 走到终态，receipt 只补不清。 并发领取预算由 {@code claimExecution} 的 FOR UPDATE
 * 短事务保证。
 */
@Component
public class HypitExecutionRepository {

	private final DatabaseClient db;

	public HypitExecutionRepository(DatabaseClient db) {
		this.db = db;
	}

	public record GrantRow(UUID id, UUID projectId, String accountId, UUID planId, UUID pricingId, String scopeHash,
			String scopeJson, java.math.BigDecimal maxCost, String currency, boolean allowUnknown, int variantCount,
			Instant expiresAt, Instant revokedAt) {
	}

	public record ExecutionRow(UUID operationId, UUID jobId, UUID buildId, UUID grantId, String needId, UUID aiRunId,
			String endpointId, String capability, String model, String requestHash, String state, String receiptJson,
			java.math.BigDecimal estimatedCost, java.math.BigDecimal actualCost, String currency,
			String providerCancelState) {
	}

	private static final String GRANT_COLS = """
			id::text, project_id::text, account_id, plan_id::text, pricing_id::text, scope_hash,
			scope_json::text, max_cost, currency, allow_unknown, variant_count, expires_at, revoked_at
			""";

	private static final String EXECUTION_COLS = """
			operation_id::text, job_id::text, build_id::text, grant_id::text, need_id, ai_run_id::text,
			endpoint_id, capability, model, request_hash, state, receipt::text AS receipt_json, estimated_cost,
			actual_cost, currency, provider_cancel_state
			""";

	/** 新建授权（POST /execution-grants 落库；scope hash 由服务层计算）。 */
	public Mono<GrantRow> insertGrant(UUID id, UUID projectId, String accountId, UUID planId, UUID pricingId,
			String scopeHash, String scopeJson, java.math.BigDecimal maxCost, String currency, boolean allowUnknown,
			int variantCount, Instant expiresAt) {
		var statement = db
				.sql("INSERT INTO hypit_execution_grant(id, project_id, account_id, plan_id,"
						+ " pricing_id, scope_hash, scope_json, max_cost, currency, allow_unknown, variant_count,"
						+ " expires_at) VALUES (CAST(:id AS uuid), CAST(:project AS uuid), :account,"
						+ " CAST(:plan AS uuid), CAST(:pricing AS uuid), :scopeHash, CAST(:scope AS jsonb),"
						+ " :maxCost, :currency, :allowUnknown, :variantCount, :expiresAt) RETURNING " + GRANT_COLS)
				.bind("id", id.toString()).bind("project", projectId.toString()).bind("account", accountId)
				.bind("scopeHash", scopeHash).bind("scope", scopeJson).bind("currency", currency)
				.bind("allowUnknown", allowUnknown).bind("variantCount", variantCount).bind("expiresAt", expiresAt);
		statement = planId == null
				? statement.bindNull("plan", String.class)
				: statement.bind("plan", planId.toString());
		statement = pricingId == null
				? statement.bindNull("pricing", String.class)
				: statement.bind("pricing", pricingId.toString());
		statement = maxCost == null
				? statement.bindNull("maxCost", java.math.BigDecimal.class)
				: statement.bind("maxCost", maxCost);
		return statement.map(HypitExecutionRepository::mapGrant).one();
	}

	/**
	 * 锁定授权行（短事务内 FOR UPDATE）——预算并发领取的串行点。 返回空表示授权不存在。
	 */
	public Mono<GrantRow> lockGrant(UUID grantId) {
		return db.sql(
				"SELECT " + GRANT_COLS + " FROM hypit_execution_grant WHERE id = CAST(:id AS uuid)" + " FOR UPDATE")
				.bind("id", grantId.toString()).map(HypitExecutionRepository::mapGrant).one();
	}

	public Mono<GrantRow> findGrant(UUID grantId) {
		return db.sql("SELECT " + GRANT_COLS + " FROM hypit_execution_grant WHERE id = CAST(:id AS uuid)")
				.bind("id", grantId.toString()).map(HypitExecutionRepository::mapGrant).one();
	}

	public Mono<Long> revokeGrant(UUID grantId) {
		return db
				.sql("UPDATE hypit_execution_grant SET revoked_at = now(), updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND revoked_at IS NULL")
				.bind("id", grantId.toString()).fetch().rowsUpdated();
	}

	/** 该授权下未取消的执行数（variant 预算口径）。 */
	public Mono<Long> countActiveExecutions(UUID grantId) {
		return db
				.sql("SELECT count(*) AS n FROM hypit_execution WHERE grant_id = CAST(:id AS uuid)"
						+ " AND state <> 'cancelled'")
				.bind("id", grantId.toString())
				.map((row, metadata) -> Long.valueOf(row.get("n", Number.class).longValue())).one();
	}

	/** 幂等插入执行：同 operationId 冲突时读原行（replay 由调用方比对 request_hash）。 */
	public Mono<AcceptedExecution> insertExecution(UUID operationId, UUID jobId, UUID buildId, UUID grantId,
			String needId, String endpointId, String capability, String model, String requestHash,
			java.math.BigDecimal estimatedCost, String currency) {
		var statement = db
				.sql("INSERT INTO hypit_execution(operation_id, job_id, build_id, grant_id,"
						+ " need_id, endpoint_id, capability, model, request_hash, state, estimated_cost, currency)"
						+ " VALUES (CAST(:operation AS uuid), CAST(:job AS uuid), CAST(:build AS uuid),"
						+ " CAST(:grant AS uuid), :need, :endpoint, :capability, :model, :requestHash,"
						+ " 'prepared', :estimatedCost, :currency) ON CONFLICT (operation_id) DO NOTHING"
						+ " RETURNING " + EXECUTION_COLS)
				.bind("operation", operationId.toString()).bind("grant", grantId.toString()).bind("need", needId)
				.bind("endpoint", endpointId).bind("capability", capability).bind("model", model)
				.bind("requestHash", requestHash).bind("currency", currency);
		statement = jobId == null ? statement.bindNull("job", String.class) : statement.bind("job", jobId.toString());
		statement = buildId == null
				? statement.bindNull("build", String.class)
				: statement.bind("build", buildId.toString());
		statement = estimatedCost == null
				? statement.bindNull("estimatedCost", java.math.BigDecimal.class)
				: statement.bind("estimatedCost", estimatedCost);
		return statement.map(HypitExecutionRepository::mapExecution).one().map(row -> new AcceptedExecution(false, row))
				.switchIfEmpty(
						Mono.defer(() -> findExecution(operationId).map(row -> new AcceptedExecution(true, row))));
	}

	public record AcceptedExecution(boolean existing, ExecutionRow row) {
	}

	public Mono<ExecutionRow> findExecution(UUID operationId) {
		return db.sql("SELECT " + EXECUTION_COLS + " FROM hypit_execution" + " WHERE operation_id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).map(HypitExecutionRepository::mapExecution).one();
	}

	/** 状态推进（非终态→任意；终态只允许幂等重复回执补缺，不产生第二次结算语义）。 */
	public Mono<Long> updateState(UUID operationId, String state) {
		return db
				.sql("UPDATE hypit_execution SET state = :state, updated_at = now()"
						+ " WHERE operation_id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("state", state).fetch().rowsUpdated();
	}

	/** 回执落库：只在 receipt 为空或内容一致时生效；actual_cost 只补不清（重复 callback 不再结算）。 */
	public Mono<Long> settleExecution(UUID operationId, String state, String receiptJson,
			java.math.BigDecimal actualCost) {
		var statement = db.sql("UPDATE hypit_execution SET state = :state, updated_at = now(),"
				+ " receipt = COALESCE(receipt, CAST(:receipt AS jsonb)),"
				+ " actual_cost = COALESCE(actual_cost, :actualCost)" + " WHERE operation_id = CAST(:id AS uuid)")
				.bind("id", operationId.toString()).bind("state", state);
		statement = receiptJson == null
				? statement.bindNull("receipt", String.class)
				: statement.bind("receipt", receiptJson);
		statement = actualCost == null
				? statement.bindNull("actualCost", java.math.BigDecimal.class)
				: statement.bind("actualCost", actualCost);
		return statement.fetch().rowsUpdated();
	}

	private static GrantRow mapGrant(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new GrantRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("project_id", String.class)), row.get("account_id", String.class),
				nullableUuid(row.get("plan_id", String.class)), nullableUuid(row.get("pricing_id", String.class)),
				row.get("scope_hash", String.class), row.get("scope_json", String.class),
				row.get("max_cost", java.math.BigDecimal.class), row.get("currency", String.class),
				Boolean.TRUE.equals(row.get("allow_unknown", Boolean.class)), row.get("variant_count", Integer.class),
				row.get("expires_at", Instant.class), row.get("revoked_at", Instant.class));
	}

	private static ExecutionRow mapExecution(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new ExecutionRow(UUID.fromString(row.get("operation_id", String.class)),
				nullableUuid(row.get("job_id", String.class)), nullableUuid(row.get("build_id", String.class)),
				UUID.fromString(row.get("grant_id", String.class)), row.get("need_id", String.class),
				nullableUuid(row.get("ai_run_id", String.class)), row.get("endpoint_id", String.class),
				row.get("capability", String.class), row.get("model", String.class),
				row.get("request_hash", String.class), row.get("state", String.class),
				row.get("receipt_json", String.class), row.get("estimated_cost", java.math.BigDecimal.class),
				row.get("actual_cost", java.math.BigDecimal.class), row.get("currency", String.class),
				row.get("provider_cancel_state", String.class));
	}

	private static UUID nullableUuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}
}
