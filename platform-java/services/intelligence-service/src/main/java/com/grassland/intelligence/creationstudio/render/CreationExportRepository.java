package com.grassland.intelligence.creationstudio.render;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-18：导出产物持久层。 owner+request_id 幂等（冲突读回既有行）；building 超时由读侧清扫
 * （§6.4：150s 无结果标 failed，同键重试可重建非付费产物）；ready 行 7 天生命周期由清理 worker 删除。
 */
@Component
public class CreationExportRepository {

	private final DatabaseClient db;

	public CreationExportRepository(DatabaseClient db) {
		this.db = db;
	}

	private static final String COLS = "id, owner_account_id, request_id, draft_id, version, format, theme,"
			+ " include_title, cite_external_links, payload_hash, state, manifest_json::text AS manifest,"
			+ " content_hash, error_code, created_at, ready_at, failed_at";

	public record ExportRow(UUID id, String ownerAccountId, String requestId, UUID draftId, int version, String format,
			String theme, boolean includeTitle, boolean citeExternalLinks, String payloadHash, String state,
			String manifestJson, String contentHash, String errorCode, OffsetDateTime createdAt, OffsetDateTime readyAt,
			OffsetDateTime failedAt) {
	}

	/** 幂等落位：插入 building 行；同键既有行直接读回（异参由 service 层判冲突）。 */
	public Mono<ExportRow> claimOrGet(UUID id, String ownerAccountId, String requestId, UUID draftId, int version,
			String format, String theme, boolean includeTitle, boolean citeExternalLinks, String payloadHash) {
		return db.sql("""
				INSERT INTO creation_export (id, owner_account_id, request_id, draft_id, version, format, theme,
				    include_title, cite_external_links, payload_hash, state)
				VALUES (CAST(:id AS uuid), :owner, :requestId, CAST(:draft AS uuid), :version, :format, :theme,
				    :includeTitle, :cite, CAST(:hash AS char(64)), 'building')
				ON CONFLICT (owner_account_id, request_id) DO NOTHING
				""").bind("id", id.toString()).bind("owner", ownerAccountId).bind("requestId", requestId)
				.bind("draft", draftId.toString()).bind("version", version).bind("format", format).bind("theme", theme)
				.bind("includeTitle", includeTitle).bind("cite", citeExternalLinks).bind("hash", payloadHash).fetch()
				.rowsUpdated().then(findByOwnerAndRequestId(ownerAccountId, requestId));
	}

	public Mono<ExportRow> findByOwnerAndRequestId(String ownerAccountId, String requestId) {
		return db.sql("SELECT " + COLS + " FROM creation_export WHERE owner_account_id = :owner AND request_id = :rid")
				.bind("owner", ownerAccountId).bind("rid", requestId).map(CreationExportRepository::map).one();
	}

	public Mono<ExportRow> findByIdAndOwner(UUID id, String ownerAccountId) {
		return db
				.sql("SELECT " + COLS
						+ " FROM creation_export WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", ownerAccountId).map(CreationExportRepository::map).one();
	}

	public Mono<Integer> markReady(UUID id, String manifestJson, String contentHash) {
		return db.sql("""
				UPDATE creation_export SET state='ready', manifest_json = CAST(:manifest AS jsonb),
				    content_hash = CAST(:hash AS char(64)), ready_at = now(), error_code = NULL
				WHERE id = CAST(:id AS uuid) AND state = 'building'
				""").bind("id", id.toString()).bind("manifest", manifestJson).bind("hash", contentHash).fetch()
				.rowsUpdated().map(count -> count == null ? 0 : count.intValue());
	}

	public Mono<Integer> markFailed(UUID id, String errorCode) {
		return db.sql("""
				UPDATE creation_export SET state='failed', error_code = :code, failed_at = now()
				WHERE id = CAST(:id AS uuid) AND state IN ('building', 'failed')
				""").bind("id", id.toString()).bind("code", errorCode).fetch().rowsUpdated()
				.map(count -> count == null ? 0 : count.intValue());
	}

	/** 读侧超时清扫：building 超过 150s 且无产物 → failed（同键重试可重建）。 */
	public Mono<Integer> failStaleBuilding(OffsetDateTime cutoff) {
		return db
				.sql("UPDATE creation_export SET state='failed', error_code='STUDIO_EXPORT_TIMEOUT', failed_at=now()"
						+ " WHERE state='building' AND created_at < :cutoff")
				.bind("cutoff", cutoff).fetch().rowsUpdated().map(count -> count == null ? 0 : count.intValue());
	}

	/** 7 天生命周期删除（含对象清理由 worker 依据 manifest objectKey 执行）。 */
	public reactor.core.publisher.Flux<ExportRow> findExpired(OffsetDateTime cutoff, int limit) {
		return db
				.sql("SELECT " + COLS + " FROM creation_export WHERE state='ready' AND ready_at < :cutoff"
						+ " ORDER BY ready_at LIMIT :limit")
				.bind("cutoff", cutoff).bind("limit", limit).map(CreationExportRepository::map).all();
	}

	public Mono<Boolean> delete(UUID id) {
		return db.sql("DELETE FROM creation_export WHERE id = CAST(:id AS uuid)").bind("id", id.toString()).fetch()
				.rowsUpdated().map(count -> count != null && count > 0);
	}

	private static ExportRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new ExportRow(row.get("id", UUID.class), row.get("owner_account_id", String.class),
				row.get("request_id", String.class), row.get("draft_id", UUID.class), row.get("version", Integer.class),
				row.get("format", String.class), row.get("theme", String.class),
				row.get("include_title", Boolean.class), row.get("cite_external_links", Boolean.class),
				row.get("payload_hash", String.class), row.get("state", String.class),
				row.get("manifest", String.class), row.get("content_hash", String.class),
				row.get("error_code", String.class), row.get("created_at", OffsetDateTime.class),
				row.get("ready_at", OffsetDateTime.class), row.get("failed_at", OffsetDateTime.class));
	}
}
