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
