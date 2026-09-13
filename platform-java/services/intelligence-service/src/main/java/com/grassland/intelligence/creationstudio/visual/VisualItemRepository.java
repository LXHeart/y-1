package com.grassland.intelligence.creationstudio.visual;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-08（§7.2）：视觉子项持久层。
 *
 * <p>
 * 状态机（§4.3）：waiting_anchor → queued →（claim）dispatching →（原图已存+mediaId 持久化）
 * generated_unsettled →（结算完成）succeeded；失败／取消／未知见各标记方法。 dispatching 后无确定结果标
 * unknown，不得重新派发（TC101-037）；generated_unsettled 只恢复结算（TC101-038）。 租约 240s + 30s
 * heartbeat（§5.6）；claim_token 保证同一 attempt 只有一个派发者（TC101-036）。
 */
@Component
public class VisualItemRepository {

	public static final String STATE_WAITING_ANCHOR = "waiting_anchor";
	public static final String STATE_QUEUED = "queued";
	public static final String STATE_DISPATCHING = "dispatching";
	public static final String STATE_GENERATED_UNSETTLED = "generated_unsettled";
	public static final String STATE_SUCCEEDED = "succeeded";
	public static final String STATE_FAILED = "failed";
	public static final String STATE_CANCELLED = "cancelled";
	public static final String STATE_UNKNOWN = "unknown";

	private static final String LEASE_SECONDS = "240";

	private final DatabaseClient db;

	public VisualItemRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ItemRow(UUID id, UUID operationId, String itemId, int position, String state,
			UUID executionOperationId, UUID runId, String inputHash, UUID budgetId, LocalDate budgetReservationDate,
			Integer reservedCents, UUID originalMediaId, UUID artifactId, UUID anchorArtifactId, String errorCode,
			UUID claimToken, OffsetDateTime claimedUntil, OffsetDateTime heartbeatAt, OffsetDateTime createdAt,
			OffsetDateTime updatedAt) {
	}

	public record NewItem(UUID attemptId, String itemId, int position, String state) {
	}

	/** 子项批量落库（父任务占位同事务由 C101-10 编排；execution_operation_id 确定性生成）。 */
	public Mono<Void> insertItems(UUID operationId, List<NewItem> items) {
		if (items == null || items.isEmpty()) {
			return Mono.empty();
		}
		return Flux
				.fromIterable(
						items)
				.concatMap(item -> db
						.sql("""
								INSERT INTO creation_visual_item (id, operation_id, item_id, position, state, execution_operation_id)
								VALUES (CAST(:id AS uuid), CAST(:operationId AS uuid), :itemId, :position, :state,
								    CAST(:execOp AS uuid))
								ON CONFLICT (operation_id, item_id) DO NOTHING
								""")
						.bind("id", item.attemptId().toString()).bind("operationId", operationId.toString())
						.bind("itemId", item.itemId()).bind("position", item.position()).bind("state", item.state())
						.bind("execOp", deterministicExecutionOperationId(operationId, item.itemId(), item.attemptId())
								.toString())
						.fetch().rowsUpdated().then())
				.then();
	}

	/** executionOperationId = f(父操作, itemId, attempt)（§6.6：确定性生成，重放同键）。 */
	public static UUID deterministicExecutionOperationId(UUID operationId, String itemId, UUID attemptId) {
		return UUID.nameUUIDFromBytes(("visual-exec:" + operationId + ":" + itemId + ":" + attemptId)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	public Flux<ItemRow> findByOperation(UUID operationId) {
		return select("WHERE operation_id = CAST(:p AS uuid) ORDER BY position").bind("p", operationId.toString())
				.map(VisualItemRepository::map).all();
	}

	public Mono<ItemRow> findItem(UUID operationId, String itemId) {
		return select("WHERE operation_id = CAST(:p AS uuid) AND item_id = :item").bind("p", operationId.toString())
				.bind("item", itemId).map(VisualItemRepository::map).one();
	}

	public Mono<ItemRow> findById(UUID attemptId) {
		return select("WHERE id = CAST(:p AS uuid)").bind("p", attemptId.toString()).map(VisualItemRepository::map)
				.one();
	}

	/** 待恢复结算的子项（generated_unsettled）——收尾扫描/手动 reconcile 共用。 */
	public Flux<ItemRow> findRecoverable(OffsetDateTime updatedBefore, int limit) {
		return db
				.sql(selectSql() + " WHERE state = 'generated_unsettled' AND updated_at <= :before "
						+ "ORDER BY updated_at LIMIT :limit")
				.bind("before", updatedBefore).bind("limit", limit).map(VisualItemRepository::map).all();
	}

	/**
	 * 派发权认领：queued → dispatching（租约 240s + claim_token）。 只有一个 worker
	 * 能成功（TC101-036）； 租约过期允许重认领（仅回读状态核实，不据此自动重发——§5.6）。
	 */
	public Mono<UUID> claimForDispatch(UUID attemptId) {
		return db
				.sql("""
						UPDATE creation_visual_item
						SET state='dispatching', claim_token=gen_random_uuid(),
						    claimed_until=now() + INTERVAL '""" + LEASE_SECONDS
						+ " seconds', heartbeat_at=now(), updated_at=now()\n" + """
								WHERE id=CAST(:id AS uuid) AND state='queued'
								RETURNING claim_token
								""")
				.bind("id", attemptId.toString()).map((row, metadata) -> row.get("claim_token", UUID.class)).one();
	}

	/** run 绑定（observer.prepared）：执行操作/输入摘要/预算句柄落库；claim 丢失返回 false。 */
	public Mono<Boolean> markRunBound(UUID attemptId, UUID claimToken, UUID runId, String inputHash, UUID budgetId,
			LocalDate reservationDate, Integer reservedCents) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE creation_visual_item
				SET run_id=CAST(:run AS uuid), input_hash=CAST(:hash AS char(64)),
				    budget_id=CAST(:budget AS uuid), budget_reservation_date=CAST(:date AS date),
				    reserved_cents=:cents, updated_at=now()
				WHERE id=CAST(:id AS uuid) AND claim_token=CAST(:claim AS uuid) AND state='dispatching'
				""").bind("id", attemptId.toString()).bind("claim", claimToken.toString()).bind("run", runId.toString())
				.bind("hash", inputHash).bind("cents", reservedCents == null ? 0 : reservedCents);
		spec = budgetId == null ? spec.bindNull("budget", String.class) : spec.bind("budget", budgetId.toString());
		spec = reservationDate == null
				? spec.bindNull("date", String.class)
				: spec.bind("date", reservationDate.toString());
		return spec.fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	/** 预算句柄落库（observer.reserved，在 run 绑定之后；恢复结算重建 BudgetCheckResult 用）。 */
	public Mono<Boolean> markBudgetHandles(UUID attemptId, UUID claimToken, UUID budgetId, LocalDate reservationDate,
			Integer reservedCents) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE creation_visual_item
				SET budget_id=CAST(:budget AS uuid), budget_reservation_date=CAST(:date AS date),
				    reserved_cents=:cents, updated_at=now()
				WHERE id=CAST(:id AS uuid) AND claim_token=CAST(:claim AS uuid) AND run_id IS NOT NULL
				""").bind("id", attemptId.toString()).bind("claim", claimToken.toString()).bind("cents",
				reservedCents == null ? 0 : reservedCents);
		spec = budgetId == null ? spec.bindNull("budget", String.class) : spec.bind("budget", budgetId.toString());
		spec = reservationDate == null
				? spec.bindNull("date", String.class)
				: spec.bind("date", reservationDate.toString());
		return spec.fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	/** 原图已存（observer.generated）：mediaId 落库，状态 → generated_unsettled（结算未完成）。 */
	public Mono<Boolean> markGeneratedUnsettled(UUID attemptId, UUID mediaId) {
		return db.sql("""
				UPDATE creation_visual_item
				SET state='generated_unsettled', original_media_id=CAST(:media AS uuid), updated_at=now()
				WHERE id=CAST(:id AS uuid) AND state='dispatching'
				""").bind("id", attemptId.toString()).bind("media", mediaId.toString()).fetch().rowsUpdated()
				.map(count -> count != null && count > 0);
	}

	/** 成品登记（C101-09 artifact）：与 succeeded 分步写（先 artifact 后结算收敛）。 */
	public Mono<Boolean> markArtifact(UUID attemptId, UUID artifactId) {
		return db.sql("""
				UPDATE creation_visual_item SET artifact_id=CAST(:artifact AS uuid), updated_at=now()
				WHERE id=CAST(:id AS uuid) AND artifact_id IS NULL
				""").bind("id", attemptId.toString()).bind("artifact", artifactId.toString()).fetch().rowsUpdated()
				.map(count -> count != null && count > 0);
	}

	public Mono<Boolean> markSucceeded(UUID attemptId) {
		return casState(attemptId, STATE_GENERATED_UNSETTLED, STATE_SUCCEEDED, null);
	}

	public Mono<Boolean> markFailed(UUID attemptId, String errorCode) {
		return casState(attemptId, STATE_DISPATCHING, STATE_FAILED, errorCode);
	}

	/** dispatching 崩溃（无可确认产物）→ unknown；不允许自动重派（TC101-037）。 */
	public Mono<Boolean> markUnknown(UUID attemptId) {
		return casState(attemptId, STATE_DISPATCHING, STATE_UNKNOWN, null);
	}

	public Mono<Boolean> markCancelled(UUID attemptId) {
		return db.sql("""
				UPDATE creation_visual_item SET state='cancelled', updated_at=now()
				WHERE id=CAST(:id AS uuid) AND state IN ('waiting_anchor','queued')
				""").bind("id", attemptId.toString()).fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	/** 租约心跳（30s；§5.6）。 */
	public Mono<Boolean> heartbeat(UUID attemptId, UUID claimToken) {
		return db
				.sql("""
						UPDATE creation_visual_item
						SET heartbeat_at=now(), claimed_until=now() + INTERVAL '""" + LEASE_SECONDS
						+ " seconds', updated_at=now()\n" + """
								WHERE id=CAST(:id AS uuid) AND claim_token=CAST(:claim AS uuid)
								""")
				.bind("id", attemptId.toString()).bind("claim", claimToken.toString()).fetch().rowsUpdated()
				.map(count -> count != null && count > 0);
	}

	private Mono<Boolean> casState(UUID attemptId, String expected, String target, String errorCode) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE creation_visual_item
				SET state=:target, error_code=:errorCode, updated_at=now()
				WHERE id=CAST(:id AS uuid) AND state=:expected
				""").bind("id", attemptId.toString()).bind("target", target).bind("expected", expected);
		spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
		return spec.fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	private static String selectSql() {
		return "SELECT id, operation_id, item_id, position, state, execution_operation_id, run_id, input_hash,"
				+ " budget_id, budget_reservation_date, reserved_cents, original_media_id, artifact_id,"
				+ " anchor_artifact_id, error_code, claim_token, claimed_until, heartbeat_at, created_at,"
				+ " updated_at" + " FROM creation_visual_item";
	}

	private DatabaseClient.GenericExecuteSpec select(String suffix) {
		return db.sql(selectSql() + " " + suffix);
	}

	private static ItemRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new ItemRow(row.get("id", UUID.class), row.get("operation_id", UUID.class),
				row.get("item_id", String.class), row.get("position", Integer.class), row.get("state", String.class),
				row.get("execution_operation_id", UUID.class), row.get("run_id", UUID.class),
				row.get("input_hash", String.class), row.get("budget_id", UUID.class),
				row.get("budget_reservation_date", java.time.LocalDate.class), row.get("reserved_cents", Integer.class),
				row.get("original_media_id", UUID.class), row.get("artifact_id", UUID.class),
				row.get("anchor_artifact_id", UUID.class), row.get("error_code", String.class),
				row.get("claim_token", UUID.class), row.get("claimed_until", OffsetDateTime.class),
				row.get("heartbeat_at", OffsetDateTime.class), row.get("created_at", OffsetDateTime.class),
				row.get("updated_at", OffsetDateTime.class));
	}
}
