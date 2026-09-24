package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationStage;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InvocationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SettlementState;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * dh_invocation 仓储（任务书 #105D C105D-01 / 共享契约 K05、K08）。
 *
 * <p>
 * 稳定经济键：业务侧 {@code (owner_account_id, resource_id, stage, segment_index)} + 财务侧
 * {@code operation_id}（UNIQUE，同 invocation 重试复用，绝不换键）。防「第二次 run」由数据库事实保证：
 * {@code UNIQUE(operation_id)} 与 {@code UNIQUE(ai_run_id)}；ai_run.operation_id
 * 只是普通索引，不冒充全局 唯一。K05 冻结 DDL 无 lease 列，preparing 进程租约以 {@code next_attempt_at}
 * 承载（K08：CAS 领 prepared 工作、过期无 run 绑定才允许同键恢复）。
 */
@Component
public class DigitalHumanInvocationRepository {

	private final DatabaseClient db;

	public DigitalHumanInvocationRepository(DatabaseClient db) {
		this.db = db;
	}

	private static final String COLS = """
			id::text, owner_account_id, session_id::text, turn_id::text, stage, resource_id::text, segment_index,
			operation_id::text, ai_run_id::text, state, settlement_state, provider_snapshot::text,
			budget_snapshot::text, usage_json::text, provider_run_id, request_hash, deadline_at, next_attempt_at,
			version, created_at, updated_at
			""";

	/**
	 * 幂等登记（经济键落库）：同 (owner,resource,stage,segment) 已存在时读原行返回（原 operation_id 复用）。
	 */
	public Mono<InvocationRow> insert(InvocationRow row) {
		var statement = db.sql("INSERT INTO dh_invocation(id, owner_account_id, session_id, turn_id,"
				+ " stage, resource_id, segment_index, operation_id, state, settlement_state, provider_snapshot,"
				+ " budget_snapshot, request_hash, deadline_at) VALUES (CAST(:id AS uuid), :owner,"
				+ " CAST(:session AS uuid), CAST(:turn AS uuid), :stage, CAST(:resource AS uuid), :segment,"
				+ " CAST(:operation AS uuid), 'reserved', 'not_required', CAST(:provider AS jsonb),"
				+ " CAST(:budget AS jsonb), :requestHash, :deadline) ON CONFLICT (owner_account_id, resource_id,"
				+ " stage, segment_index) DO NOTHING RETURNING " + COLS).bind("id", row.id())
				.bind("owner", row.ownerAccountId()).bind("stage", row.stage().name())
				.bind("resource", row.resourceId()).bind("segment", row.segmentIndex())
				.bind("operation", row.operationId()).bind("provider", row.providerSnapshot())
				.bind("budget", row.budgetSnapshot()).bind("requestHash", row.requestHash())
				.bind("deadline", row.deadlineAt());
		// R2DBC null String 需显式类型（session/turn 对 preview 为 null）。
		statement = row.sessionId() == null
				? statement.bindNull("session", String.class)
				: statement.bind("session", row.sessionId());
		statement = row.turnId() == null
				? statement.bindNull("turn", String.class)
				: statement.bind("turn", row.turnId());
		Mono<InvocationRow> body = statement.map(DigitalHumanInvocationRepository::mapRow).one()
				.switchIfEmpty(Mono.defer(() -> findByEconomicKey(row.ownerAccountId(),
						UUID.fromString(row.resourceId()), row.stage(), row.segmentIndex())));
		return body;
	}

	public Mono<InvocationRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM dh_invocation WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(DigitalHumanInvocationRepository::mapRow).one();
	}

	public Mono<InvocationRow> findByEconomicKey(String owner, UUID resourceId, InvocationStage stage,
			int segmentIndex) {
		return db
				.sql("SELECT " + COLS + " FROM dh_invocation WHERE owner_account_id = :owner"
						+ " AND resource_id = CAST(:resource AS uuid) AND stage = :stage AND segment_index = :segment")
				.bind("owner", owner).bind("resource", resourceId.toString()).bind("stage", stage.name())
				.bind("segment", segmentIndex).map(DigitalHumanInvocationRepository::mapRow).one();
	}

	/**
	 * CAS 领 prepare 工作：reserved→preparing（或 preparing 租约过期且未绑 run 的同键恢复）。仅一个事务能赢。
	 */
	public Mono<InvocationRow> casPreparing(UUID id, Instant leaseExpiresAt) {
		return db
				.sql("UPDATE dh_invocation SET state = 'preparing', next_attempt_at = :lease,"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " AND (state = 'reserved' OR (state = 'preparing' AND next_attempt_at IS NOT NULL"
						+ " AND next_attempt_at < now() AND ai_run_id IS NULL)) RETURNING " + COLS)
				.bind("id", id.toString()).bind("lease", leaseExpiresAt).map(DigitalHumanInvocationRepository::mapRow)
				.one();
	}

	/**
	 * 事务内原子绑定 run（bindPrepared 回调落点）：preparing→prepared 并写 budget_snapshot。
	 *
	 * <p>
	 * 行已绑定 run（同键重试、前一次事务已提交）时抛 {@code dh_invocation_bound}——让本次新事务整体回滚 （其中的新
	 * ai_run 一并消失），调用方随后读原行/原 run 恢复，绝不产生第二个 run。
	 *
	 * <p>
	 * {@code leaseExpiresAt} 必须与本次 casPreparing 写入的租约值一致：陈旧进程（租约已被他人过期重领） 的绑定
	 * UPDATE 落空，防止两个进程先后给同一 invocation 绑两个 run。
	 */
	public Mono<Void> bindPrepared(UUID id, UUID aiRunId, String budgetSnapshotJson, Instant leaseExpiresAt) {
		return db
				.sql("UPDATE dh_invocation SET state = 'prepared', ai_run_id = CAST(:run AS uuid),"
						+ " budget_snapshot = CAST(:budget AS jsonb), next_attempt_at = NULL, version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND state = 'preparing'"
						+ " AND next_attempt_at = :lease")
				.bind("id", id.toString()).bind("run", aiRunId.toString()).bind("budget", budgetSnapshotJson)
				.bind("lease", leaseExpiresAt).fetch().rowsUpdated()
				.flatMap(updated -> updated == 1
						? Mono.<Void>empty()
						: findById(id).flatMap(existing -> Mono.<Void>error(existing.aiRunId() != null
								? new IntelligenceException(409, "dh_invocation_bound", "调用已绑定执行记录，按原记录恢复。")
								: new IllegalStateException("dh_invocation 状态或租约不匹配，无法绑定: "
										+ (existing == null ? "missing" : existing.state())))));
	}

	/**
	 * provider 派发前持久 dispatched 标记：prepared→dispatched 只允许 CAS 一次；派发后不可回 prepared。
	 */
	public Mono<InvocationRow> claimDispatch(UUID id) {
		return db
				.sql("UPDATE dh_invocation SET state = 'dispatched', version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND state = 'prepared' RETURNING " + COLS)
				.bind("id", id.toString()).map(DigitalHumanInvocationRepository::mapRow).one();
	}

	/** 派发后结果不明 → unknown（K08 表第 5 行）：保留经济事实，待核对，不重发推理。 */
	public Mono<InvocationRow> markUnknown(UUID id) {
		return db
				.sql("UPDATE dh_invocation SET state = 'unknown', settlement_state = 'pending',"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " AND state = 'dispatched' RETURNING " + COLS)
				.bind("id", id.toString()).map(DigitalHumanInvocationRepository::mapRow).one();
	}

	/**
	 * 终态收尾（cancelled/failed/succeeded）与 settlement_state；usageJson 允许
	 * null（未计量的取消/失败）。
	 */
	public Mono<InvocationRow> markTerminal(UUID id, InvocationState state, SettlementState settlement,
			String usageJson) {
		var update = db
				.sql("UPDATE dh_invocation SET state = :state, settlement_state = :settlement,"
						+ " usage_json = CAST(:usage AS jsonb), version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND (state IN ('reserved', 'preparing', 'prepared',"
						+ " 'dispatched') OR (state = :state AND settlement_state IS DISTINCT FROM :settlement))"
						+ " RETURNING " + COLS)
				.bind("id", id.toString()).bind("state", state.name()).bind("settlement", settlement.name());
		update = usageJson == null ? update.bindNull("usage", String.class) : update.bind("usage", usageJson);
		return update.map(DigitalHumanInvocationRepository::mapRow).one();
	}

	/** 行映射（包内共享：worker 等同包组件复用同一 SELECT 列集）。 */
	static InvocationRow rowOf(io.r2dbc.spi.Readable r) {
		return mapRow(r);
	}

	private static InvocationRow mapRow(io.r2dbc.spi.Readable r) {
		return new InvocationRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("session_id", String.class), r.get("turn_id", String.class),
				InvocationStage.valueOf(r.get("stage", String.class)), r.get("resource_id", String.class),
				r.get("segment_index", Integer.class), r.get("operation_id", String.class),
				r.get("ai_run_id", String.class), InvocationState.valueOf(r.get("state", String.class)),
				SettlementState.valueOf(r.get("settlement_state", String.class)),
				r.get("provider_snapshot", String.class), r.get("budget_snapshot", String.class),
				r.get("usage_json", String.class), r.get("provider_run_id", String.class),
				r.get("request_hash", String.class), toInstant(r.get("deadline_at", OffsetDateTime.class)),
				toInstant(r.get("next_attempt_at", OffsetDateTime.class)), r.get("version", Integer.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
