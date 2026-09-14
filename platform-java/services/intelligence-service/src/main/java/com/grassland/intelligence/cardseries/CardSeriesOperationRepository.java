package com.grassland.intelligence.cardseries;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 图卡生成操作记录（AI内容中心改造-02 / T21）。{@code owner + request_id} 唯一：首次占位
 * （running）后才进入执行；执行结束写终态与完整响应体。相同 requestId 回读时先比对
 * {@code request_digest}——一致返回原结果，不一致由调用方返回 409。
 */
@Component
public class CardSeriesOperationRepository {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String STATUS_RUNNING = "running";
	public static final String STATUS_SUCCEEDED = "succeeded";
	public static final String STATUS_FAILED = "failed";

	private final DatabaseClient db;

	public CardSeriesOperationRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 占位：插入 running 行；唯一冲突时读回既有行。返回行由调用方按 digest/状态决策。
	 * 并发下两个请求都可能拿到「既有行」，语义收敛到先占位者执行。
	 */
	/**
	 * 占位：插入 running 行；唯一冲突时读回既有行。{@code inserted} 区分「本次占位成功」与 「已存在操作」——后者由调用方按
	 * digest/状态决策（重放 / 409 / 待确认）。
	 */
	public Mono<ClaimOutcome> claim(String accountId, String requestId, String digest, UUID contextSnapshotId) {
		DatabaseClient.GenericExecuteSpec spec = db
				.sql("""
						INSERT INTO card_series_operation (id, owner_account_id, request_id, request_digest, status, context_snapshot_id)
						VALUES (CAST(:id AS uuid), :owner, :requestId, :digest, :status, CAST(:snapshot AS uuid))
						ON CONFLICT (owner_account_id, request_id) DO NOTHING
						""")
				.bind("id", UUID.randomUUID().toString()).bind("owner", accountId).bind("requestId", requestId)
				.bind("digest", digest).bind("status", STATUS_RUNNING);
		spec = contextSnapshotId == null
				? spec.bindNull("snapshot", String.class)
				: spec.bind("snapshot", contextSnapshotId.toString());
		return spec.fetch().rowsUpdated().onErrorResume(error -> Mono.just(0L)) // 唯一冲突以外的写失败让读取路径兜底
				.flatMap(inserted -> find(accountId, requestId)
						.map(row -> new ClaimOutcome(row, inserted != null && inserted > 0)));
	}

	public Mono<OperationRow> find(String accountId, String requestId) {
		return db.sql("""
				SELECT id, request_digest, status, error_code, error_message,
				       result::text AS result, context_snapshot_id, created_at, updated_at
				FROM card_series_operation
				WHERE owner_account_id=:owner AND request_id=:requestId
				""").bind("owner", accountId).bind("requestId", requestId)
				.map((row, metadata) -> new OperationRow(row.get("id", UUID.class),
						row.get("request_digest", String.class), row.get("status", String.class),
						row.get("error_code", String.class), row.get("error_message", String.class),
						row.get("result", String.class),
						row.get("context_snapshot_id", java.util.UUID.class) == null
								? null
								: row.get("context_snapshot_id", java.util.UUID.class).toString(),
						row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class)))
				.one();
	}

	public Mono<Boolean> succeed(UUID id, String resultJson) {
		return terminal(id, STATUS_SUCCEEDED, null, null, resultJson);
	}

	public Mono<Boolean> fail(UUID id, String errorCode, String errorMessage) {
		return terminal(id, STATUS_FAILED, errorCode, errorMessage, null);
	}

	private Mono<Boolean> terminal(UUID id, String status, String errorCode, String errorMessage, String resultJson) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE card_series_operation
				SET status=:status, error_code=:errorCode, error_message=:errorMessage,
				    result=CAST(:result AS jsonb), updated_at=now()
				WHERE id=CAST(:id AS uuid)
				""").bind("id", id.toString()).bind("status", status);
		spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
		spec = errorMessage == null
				? spec.bindNull("errorMessage", String.class)
				: spec.bind("errorMessage", errorMessage);
		spec = resultJson == null ? spec.bindNull("result", String.class) : spec.bind("result", resultJson);
		return spec.map((row, metadata) -> row.get("updated_at", OffsetDateTime.class) != null).one()
				.onErrorResume(error -> Mono.just(false));
	}

	// ---- 任务书 #101 C101-08：v2 视觉任务（api_version=2，旧行不改） ----

	/**
	 * v2 视觉父任务占位：api_version=2 + job_kind='visual'，携带草稿／计划／quote 引用与执行快照。
	 * owner+request_id 唯一冲突读回既有行（与 v1 claim 同语义）；旧行缺 v2 列由 DEFAULT 兜底。
	 */
	public Mono<ClaimOutcome> claimVisualJob(String accountId, String requestId, String digest, UUID draftId,
			UUID planId, int planRevision, UUID quoteId, String snapshotJson) {
		return db.sql("""
				INSERT INTO card_series_operation (id, owner_account_id, request_id, request_digest, status,
				    context_snapshot_id, api_version, job_kind, draft_id, plan_id, plan_revision, quote_id,
				    snapshot_json, dispatch_state)
				VALUES (CAST(:id AS uuid), :owner, :requestId, :digest, :status, NULL, 2, 'visual',
				    CAST(:draft AS uuid), CAST(:plan AS uuid), :planRevision, CAST(:quote AS uuid),
				    CAST(:snapshot AS jsonb), 'pending')
				ON CONFLICT (owner_account_id, request_id) DO NOTHING
				""").bind("id", UUID.randomUUID().toString()).bind("owner", accountId).bind("requestId", requestId)
				.bind("digest", digest).bind("status", STATUS_RUNNING).bind("draft", draftId.toString())
				.bind("plan", planId.toString()).bind("planRevision", planRevision).bind("quote", quoteId.toString())
				.bind("snapshot", snapshotJson).fetch().rowsUpdated().onErrorResume(error -> Mono.just(0L))
				.flatMap(inserted -> find(accountId, requestId)
						.map(row -> new ClaimOutcome(row, inserted != null && inserted > 0)));
	}

	/** v2 行读取（owner 校验；api_version != 2 的行返回 empty——缺字段旧行不当 v2 job）。 */
	public Mono<VisualJobRow> findVisualJob(UUID id, String accountId) {
		return db.sql("""
				SELECT id, owner_account_id, request_id, request_digest, status, error_code, error_message,
				       result::text AS result, context_snapshot_id, created_at, updated_at, api_version, job_kind,
				       draft_id, plan_id, plan_revision, quote_id, snapshot_json::text AS snapshot, job_version,
				       cancel_requested, workflow_id, dispatch_state, settlement_state
				FROM card_series_operation
				WHERE id=CAST(:id AS uuid) AND owner_account_id=:owner AND api_version=2
				""").bind("id", id.toString()).bind("owner", accountId)
				.map((row, metadata) -> new VisualJobRow(row.get("id", UUID.class),
						row.get("owner_account_id", String.class), row.get("request_id", String.class),
						row.get("request_digest", String.class), row.get("status", String.class),
						row.get("error_code", String.class), row.get("error_message", String.class),
						row.get("result", String.class), row.get("context_snapshot_id", UUID.class),
						row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class),
						row.get("api_version", Integer.class), row.get("job_kind", String.class),
						row.get("draft_id", UUID.class), row.get("plan_id", UUID.class),
						row.get("plan_revision", Integer.class), row.get("quote_id", UUID.class),
						row.get("snapshot", String.class), row.get("job_version", Integer.class),
						row.get("cancel_requested", Boolean.class), row.get("workflow_id", String.class),
						row.get("dispatch_state", String.class), row.get("settlement_state", String.class)))
				.one();
	}

	/** v2 行按 ID 读取（workflow activity 推进路径——owner 校验在创建/HTTP 侧完成）。 */
	public Mono<VisualJobRow> findVisualJobById(UUID id) {
		return db.sql("""
				SELECT id, owner_account_id, request_id, request_digest, status, error_code, error_message,
				       result::text AS result, context_snapshot_id, created_at, updated_at, api_version, job_kind,
				       draft_id, plan_id, plan_revision, quote_id, snapshot_json::text AS snapshot, job_version,
				       cancel_requested, workflow_id, dispatch_state, settlement_state
				FROM card_series_operation
				WHERE id=CAST(:id AS uuid) AND api_version=2
				""").bind("id", id.toString())
				.map((row, metadata) -> new VisualJobRow(row.get("id", UUID.class),
						row.get("owner_account_id", String.class), row.get("request_id", String.class),
						row.get("request_digest", String.class), row.get("status", String.class),
						row.get("error_code", String.class), row.get("error_message", String.class),
						row.get("result", String.class), row.get("context_snapshot_id", UUID.class),
						row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class),
						row.get("api_version", Integer.class), row.get("job_kind", String.class),
						row.get("draft_id", UUID.class), row.get("plan_id", UUID.class),
						row.get("plan_revision", Integer.class), row.get("quote_id", UUID.class),
						row.get("snapshot", String.class), row.get("job_version", Integer.class),
						row.get("cancel_requested", Boolean.class), row.get("workflow_id", String.class),
						row.get("dispatch_state", String.class), row.get("settlement_state", String.class)))
				.one();
	}

	/** 父任务派发状态 CAS（pending→dispatched→completed 等；concurrency 守卫）。 */
	public Mono<Boolean> casDispatchState(UUID id, String expected, String target) {
		return db.sql("""
				UPDATE card_series_operation SET dispatch_state=:target, updated_at=now()
				WHERE id=CAST(:id AS uuid) AND dispatch_state=:expected
				""").bind("id", id.toString()).bind("target", target).bind("expected", expected).fetch().rowsUpdated()
				.map(count -> count != null && count > 0);
	}

	/**
	 * v2 父任务终态（result 落完整 items
	 * 视图；status=succeeded/partial/failed/cancelled/unknown）。
	 */
	public Mono<Boolean> finishVisualJob(UUID id, String state, String resultJson) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				UPDATE card_series_operation
				SET status=:status, dispatch_state='completed', settlement_state='completed',
				    result=CAST(:result AS jsonb), updated_at=now()
				WHERE id=CAST(:id AS uuid) AND dispatch_state <> 'completed'
				""").bind("id", id.toString()).bind("status", state);
		spec = resultJson == null ? spec.bindNull("result", String.class) : spec.bind("result", resultJson);
		return spec.fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	/** v2 父任务按草稿分页（owner 限定；keyset: updated_at + id）。 */
	public reactor.core.publisher.Flux<VisualJobRow> findVisualJobsByDraft(String accountId, UUID draftId, int limit,
			String cursorCreatedAt, String cursorId) {
		StringBuilder sql = new StringBuilder("SELECT id, owner_account_id, request_id, request_digest, status,"
				+ " error_code, error_message, result::text AS result, context_snapshot_id, created_at,"
				+ " updated_at, api_version, job_kind, draft_id, plan_id, plan_revision, quote_id,"
				+ " snapshot_json::text AS snapshot, job_version, cancel_requested, workflow_id, dispatch_state,"
				+ " settlement_state" + " FROM card_series_operation WHERE owner_account_id=:owner AND api_version=2"
				+ " AND draft_id=CAST(:draft AS uuid)");
		if (cursorCreatedAt != null && cursorId != null) {
			sql.append(" AND (created_at < CAST(:cursorAt AS timestamptz)"
					+ " OR (created_at = CAST(:cursorAt AS timestamptz) AND id < CAST(:cursorId AS uuid)))");
		}
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
		DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString()).bind("owner", accountId)
				.bind("draft", draftId.toString()).bind("limit", limit);
		if (cursorCreatedAt != null && cursorId != null) {
			spec = spec.bind("cursorAt", cursorCreatedAt).bind("cursorId", cursorId);
		}
		return spec.map((row, metadata) -> new VisualJobRow(row.get("id", UUID.class),
				row.get("owner_account_id", String.class), row.get("request_id", String.class),
				row.get("request_digest", String.class), row.get("status", String.class),
				row.get("error_code", String.class), row.get("error_message", String.class),
				row.get("result", String.class), row.get("context_snapshot_id", UUID.class),
				row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class),
				row.get("api_version", Integer.class), row.get("job_kind", String.class),
				row.get("draft_id", UUID.class), row.get("plan_id", UUID.class),
				row.get("plan_revision", Integer.class), row.get("quote_id", UUID.class),
				row.get("snapshot", String.class), row.get("job_version", Integer.class),
				row.get("cancel_requested", Boolean.class), row.get("workflow_id", String.class),
				row.get("dispatch_state", String.class), row.get("settlement_state", String.class))).all();
	}

	/** 取消标记 + 版本推进（expectedVersion 不符返回 false——STUDIO_VERSION_CONFLICT 由调用方映射）。 */
	public Mono<Boolean> requestCancel(UUID id, int expectedVersion) {
		return db.sql("""
				UPDATE card_series_operation
				SET cancel_requested=true, job_version=job_version+1, updated_at=now()
				WHERE id=CAST(:id AS uuid) AND job_version=:expectedVersion AND cancel_requested=false
				""").bind("id", id.toString()).bind("expectedVersion", expectedVersion).fetch().rowsUpdated()
				.map(count -> count != null && count > 0);
	}

	static String digestOf(Map<String, Object> canonicalPayload) {
		try {
			ObjectMapper sorted = new ObjectMapper()
					.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
			return com.grassland.intelligence.media.MediaChecksums.sha256(sorted.writeValueAsBytes(canonicalPayload));
		} catch (Exception error) {
			throw new IllegalStateException("图卡操作摘要序列化失败", error);
		}
	}

	public record ClaimOutcome(OperationRow row, boolean inserted) {
	}

	public record VisualJobRow(UUID id, String ownerAccountId, String requestId, String requestDigest, String status,
			String errorCode, String errorMessage, String resultJson, UUID contextSnapshotId, OffsetDateTime createdAt,
			OffsetDateTime updatedAt, int apiVersion, String jobKind, UUID draftId, UUID planId, Integer planRevision,
			UUID quoteId, String snapshotJson, int jobVersion, boolean cancelRequested, String workflowId,
			String dispatchState, String settlementState) {

		public String ownerId() {
			return ownerAccountId;
		}
	}

	public record OperationRow(UUID id, String requestDigest, String status, String errorCode, String errorMessage,
			String resultJson, String contextSnapshotId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

		public JsonNode result() {
			if (resultJson == null || resultJson.isBlank()) {
				return MAPPER.missingNode();
			}
			try {
				return MAPPER.readTree(resultJson);
			} catch (Exception error) {
				return MAPPER.missingNode();
			}
		}
	}
}
