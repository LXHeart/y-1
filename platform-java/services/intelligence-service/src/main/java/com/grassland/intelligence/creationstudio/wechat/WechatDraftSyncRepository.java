package com.grassland.intelligence.creationstudio.wechat;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-21：公众号草稿同步持久层。 行状态与派发标记都在数据库（workflow 只是推进骨架）； 状态推进一律
 * CAS（state+version），重放/并发推进不重复副作用。
 */
@Component
public class WechatDraftSyncRepository {

	private final DatabaseClient db;

	public WechatDraftSyncRepository(DatabaseClient db) {
		this.db = db;
	}

	public record SyncRow(UUID id, String ownerAccountId, String requestId, UUID accountId, int accountVersion,
			UUID draftId, int draftVersion, UUID exportId, String payloadHash, String payloadJson, String state,
			String externalDraftMediaId, String errorCode, String dispatchState, boolean draftAddDone, int version,
			OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime verifiedAt) {
	}

	public record MediaMappingRow(UUID id, UUID syncId, String ownerAccountId, UUID accountId, int accountVersion,
			UUID mediaRefId, String purpose, int ordinal, String contentHash, String mediaId, String mediaUrl,
			String derivedObjectKey, String state, int attempts) {
	}

	private static final String SYNC_COLS = "id, owner_account_id, request_id, account_id, account_version,"
			+ " draft_id, draft_version, export_id, payload_hash, payload_json, state, external_draft_media_id,"
			+ " error_code, dispatch_state, draft_add_done, version, created_at, updated_at, verified_at";

	/** 首建；owner+request 唯一冲突时读回既有行（重放）。 */
	public Mono<SyncRow> insertOrGet(UUID id, String ownerAccountId, String requestId, UUID accountId,
			int accountVersion, UUID draftId, int draftVersion, UUID exportId, String payloadHash, String payloadJson) {
		return db
				.sql("INSERT INTO creation_wechat_draft_sync (id, owner_account_id, request_id, account_id,"
						+ " account_version, draft_id, draft_version, export_id, payload_hash, payload_json)"
						+ " VALUES (CAST(:id AS uuid), :owner, :requestId, CAST(:accountId AS uuid), :accountVersion,"
						+ " CAST(:draftId AS uuid), :draftVersion, CAST(:exportId AS uuid), :payloadHash,"
						+ " CAST(:payloadJson AS jsonb))")
				.bind("id", id.toString()).bind("owner", ownerAccountId).bind("requestId", requestId)
				.bind("accountId", accountId.toString()).bind("accountVersion", accountVersion)
				.bind("draftId", draftId.toString()).bind("draftVersion", draftVersion)
				.bind("exportId", exportId.toString()).bind("payloadHash", payloadHash).bind("payloadJson", payloadJson)
				.fetch().rowsUpdated().then(findByOwnerAndRequest(ownerAccountId, requestId));
	}

	/** 「同账号＋draftVersion＋payloadHash」的活动记录（部分唯一索引兜底并发）。 */
	public Mono<SyncRow> findActiveBySnapshot(UUID accountId, UUID draftId, int draftVersion, String payloadHash) {
		return db
				.sql("SELECT " + SYNC_COLS + " FROM creation_wechat_draft_sync"
						+ " WHERE account_id = CAST(:accountId AS uuid) AND draft_id = CAST(:draftId AS uuid)"
						+ " AND draft_version = :draftVersion AND payload_hash = :payloadHash"
						+ " AND state IN ('preparing','uploading','submitting','verifying','unknown','succeeded')"
						+ " ORDER BY created_at LIMIT 1")
				.bind("accountId", accountId.toString()).bind("draftId", draftId.toString())
				.bind("draftVersion", draftVersion).bind("payloadHash", payloadHash).map(this::mapSync).one();
	}

	public Mono<SyncRow> findByIdAndOwner(UUID id, String ownerAccountId) {
		return db
				.sql("SELECT " + SYNC_COLS + " FROM creation_wechat_draft_sync"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", ownerAccountId).map(this::mapSync).one();
	}

	public Mono<SyncRow> findByOwnerAndRequest(String ownerAccountId, String requestId) {
		return db
				.sql("SELECT " + SYNC_COLS + " FROM creation_wechat_draft_sync"
						+ " WHERE owner_account_id = :owner AND request_id = :requestId")
				.bind("owner", ownerAccountId).bind("requestId", requestId).map(this::mapSync).one();
	}

	public Flux<SyncRow> listByOwnerAndDraft(String ownerAccountId, UUID draftId, int limit, OffsetDateTime cursorAt,
			UUID cursorId) {
		StringBuilder sql = new StringBuilder("SELECT " + SYNC_COLS + " FROM creation_wechat_draft_sync"
				+ " WHERE owner_account_id = :owner AND draft_id = CAST(:draftId AS uuid)");
		if (cursorAt != null && cursorId != null) {
			sql.append(" AND (created_at < :cursorAt OR (created_at = :cursorAt AND id < :cursorId))");
		}
		sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
		var spec = db.sql(sql.toString()).bind("owner", ownerAccountId).bind("draftId", draftId.toString())
				.bind("limit", limit);
		if (cursorAt != null && cursorId != null) {
			spec = spec.bind("cursorAt", cursorAt).bind("cursorId", cursorId);
		}
		return spec.map(this::mapSync).all();
	}

	public Mono<SyncRow> findById(UUID id) {
		return db.sql("SELECT " + SYNC_COLS + " FROM creation_wechat_draft_sync" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).map(this::mapSync).one();
	}

	/** 无副作用状态推进（CAS：期望 state 匹配；version 随之 +1）。 */
	public Mono<SyncRow> casState(UUID id, String expectState, String nextState, String errorCode) {
		var spec = db
				.sql("UPDATE creation_wechat_draft_sync SET state = :next, error_code = :code,"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid) AND state = :expect"
						+ " RETURNING " + SYNC_COLS)
				.bind("id", id.toString()).bind("expect", expectState).bind("next", nextState);
		spec = errorCode == null ? spec.bindNull("code", String.class) : spec.bind("code", errorCode);
		return spec.map(this::mapSync).one();
	}

	/** 断开连接时取消该连接全部未提交同步（§6.8：断开取消未提交任务，不删外部草稿）。 */
	public Mono<Long> cancelPendingForAccount(UUID accountId) {
		return db
				.sql("UPDATE creation_wechat_draft_sync SET state = 'cancelled',"
						+ " error_code = 'STUDIO_CHANNEL_ACCOUNT_INVALID', version = version + 1, updated_at = now()"
						+ " WHERE account_id = CAST(:accountId AS uuid) AND state IN ('preparing','uploading')")
				.bind("accountId", accountId.toString()).fetch().rowsUpdated().map(count -> count == null ? 0L : count);
	}

	/** draft/add 完成落库（先持久化 media_id 再回读——§6.8 步骤 6）。 */
	public Mono<SyncRow> markSubmitted(UUID id, String externalMediaId) {
		return db.sql("UPDATE creation_wechat_draft_sync SET state = 'verifying', draft_add_done = true,"
				+ " external_draft_media_id = :mediaId, error_code = NULL, version = version + 1, updated_at = now()"
				+ " WHERE id = CAST(:id AS uuid) AND state = 'submitting' RETURNING " + SYNC_COLS)
				.bind("id", id.toString()).bind("mediaId", externalMediaId).map(this::mapSync).one();
	}

	/** 派发结果不确定（超时/5xx/解析失败/提交标记悬置）→ unknown，禁止自动重派。 */
	public Mono<SyncRow> markUnknown(UUID id, String errorCode) {
		return db
				.sql("UPDATE creation_wechat_draft_sync SET state = 'unknown', draft_add_done = true,"
						+ " error_code = :code, version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) RETURNING " + SYNC_COLS)
				.bind("id", id.toString()).bind("code", errorCode).map(this::mapSync).one();
	}

	public Mono<SyncRow> markFailed(UUID id, String errorCode) {
		return db.sql("UPDATE creation_wechat_draft_sync SET state = 'failed', error_code = :code,"
				+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid) RETURNING " + SYNC_COLS)
				.bind("id", id.toString()).bind("code", errorCode).map(this::mapSync).one();
	}

	public Mono<SyncRow> markSucceeded(UUID id, String externalMediaId) {
		return db.sql("UPDATE creation_wechat_draft_sync SET state = 'succeeded', draft_add_done = true,"
				+ " external_draft_media_id = :mediaId, error_code = NULL, verified_at = now(), version = version + 1,"
				+ " updated_at = now() WHERE id = CAST(:id AS uuid) RETURNING " + SYNC_COLS).bind("id", id.toString())
				.bind("mediaId", externalMediaId).map(this::mapSync).one();
	}

	/** 派发标记（workflow 启动/收口；收养清扫依据）。 */
	public Mono<Void> markDispatch(String id, String dispatchState) {
		return db
				.sql("UPDATE creation_wechat_draft_sync SET dispatch_state = :dispatch, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", id).bind("dispatch", dispatchState).fetch().rowsUpdated().then();
	}

	public Flux<UUID> findAdoptable(int batch) {
		return db
				.sql("SELECT id FROM creation_wechat_draft_sync WHERE dispatch_state <> 'completed'"
						+ " AND state IN ('preparing','uploading','submitting','verifying')"
						+ " AND updated_at < now() - INTERVAL '10 seconds' ORDER BY updated_at LIMIT " + batch)
				.map((row, metadata) -> row.get("id", UUID.class)).all();
	}

	// ---- 上传映射 ----

	private static final String MAP_COLS = "id, sync_id, owner_account_id, account_id, account_version,"
			+ " media_ref_id, purpose, ordinal, content_hash, media_id, media_url, derived_object_key, state, attempts";

	/**
	 * 建映射行（ordinal=冻结载荷顺序：封面 0，正文图 1..n——文档序）；(account,version,hash,purpose)
	 * 唯一命中即复用（跨同步缓存）。
	 */
	public Mono<MediaMappingRow> insertOrGetMapping(UUID syncId, String ownerAccountId, UUID accountId,
			int accountVersion, UUID mediaRefId, String purpose, int ordinal, String contentHash) {
		return db
				.sql("INSERT INTO creation_wechat_media_mapping (id, sync_id, owner_account_id, account_id,"
						+ " account_version, media_ref_id, purpose, ordinal, content_hash) VALUES (CAST(:id AS uuid),"
						+ " CAST(:syncId AS uuid), :owner, CAST(:accountId AS uuid), :accountVersion,"
						+ " CAST(:mediaRefId AS uuid), :purpose, :ordinal, :contentHash)"
						+ " ON CONFLICT (account_id, account_version, content_hash, purpose) DO UPDATE SET"
						+ " sync_id = EXCLUDED.sync_id, ordinal = EXCLUDED.ordinal RETURNING " + MAP_COLS)
				.bind("id", UUID.randomUUID().toString()).bind("syncId", syncId.toString())
				.bind("owner", ownerAccountId).bind("accountId", accountId.toString())
				.bind("accountVersion", accountVersion).bind("mediaRefId", mediaRefId.toString())
				.bind("purpose", purpose).bind("ordinal", ordinal).bind("contentHash", contentHash)
				.map(this::mapMapping).one();
	}

	public Flux<MediaMappingRow> mappingsOfSync(UUID syncId) {
		return db.sql("SELECT " + MAP_COLS + " FROM creation_wechat_media_mapping WHERE sync_id = CAST(:id AS uuid)"
				+ " ORDER BY ordinal").bind("id", syncId.toString()).map(this::mapMapping).all();
	}

	public Mono<MediaMappingRow> markMappingUploaded(UUID id, String mediaId, String mediaUrl,
			String derivedObjectKey) {
		var spec = db.sql("UPDATE creation_wechat_media_mapping SET state = 'uploaded', media_id = :mediaId,"
				+ " media_url = :mediaUrl, derived_object_key = :objectKey WHERE id = CAST(:id AS uuid)" + " RETURNING "
				+ MAP_COLS).bind("id", id.toString());
		spec = mediaId == null ? spec.bindNull("mediaId", String.class) : spec.bind("mediaId", mediaId);
		spec = mediaUrl == null ? spec.bindNull("mediaUrl", String.class) : spec.bind("mediaUrl", mediaUrl);
		spec = derivedObjectKey == null
				? spec.bindNull("objectKey", String.class)
				: spec.bind("objectKey", derivedObjectKey);
		return spec.map(this::mapMapping).one();
	}

	public Mono<MediaMappingRow> bumpMappingAttempts(UUID id) {
		return db
				.sql("UPDATE creation_wechat_media_mapping SET attempts = attempts + 1"
						+ " WHERE id = CAST(:id AS uuid) RETURNING " + MAP_COLS)
				.bind("id", id.toString()).map(this::mapMapping).one();
	}

	private SyncRow mapSync(Row row, RowMetadata metadata) {
		return new SyncRow(row.get("id", UUID.class), row.get("owner_account_id", String.class),
				row.get("request_id", String.class), row.get("account_id", UUID.class),
				row.get("account_version", Integer.class), row.get("draft_id", UUID.class),
				row.get("draft_version", Integer.class), row.get("export_id", UUID.class),
				row.get("payload_hash", String.class), row.get("payload_json", String.class),
				row.get("state", String.class), row.get("external_draft_media_id", String.class),
				row.get("error_code", String.class), row.get("dispatch_state", String.class),
				Boolean.TRUE.equals(row.get("draft_add_done", Boolean.class)), row.get("version", Integer.class),
				row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class),
				row.get("verified_at", OffsetDateTime.class));
	}

	private MediaMappingRow mapMapping(Row row, RowMetadata metadata) {
		return new MediaMappingRow(row.get("id", UUID.class), row.get("sync_id", UUID.class),
				row.get("owner_account_id", String.class), row.get("account_id", UUID.class),
				row.get("account_version", Integer.class), row.get("media_ref_id", UUID.class),
				row.get("purpose", String.class), row.get("ordinal", Integer.class),
				row.get("content_hash", String.class), row.get("media_id", String.class),
				row.get("media_url", String.class), row.get("derived_object_key", String.class),
				row.get("state", String.class), row.get("attempts", Integer.class));
	}
}
