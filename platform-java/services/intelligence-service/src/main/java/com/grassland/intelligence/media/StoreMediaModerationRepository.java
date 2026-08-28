package com.grassland.intelligence.media;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code store_media_moderation} 仓储（缺口清偿之五）：门店公开媒体多模态审核结论。
 * 一行一媒体（PK=media_reference_id），重复审核 UPSERT 覆盖；无行=未审（advisory 降级）。 人工复核（V39
 * 遗留清偿）：{@link #listQueue} 按状态列队（联 media_reference 带媒体上下文）， {@link #decide}
 * 就地裁决——乐观锁比对 moderated_at，findings/model/run_id 保留自动审核证据。
 */
@Component
public class StoreMediaModerationRepository {

	private final DatabaseClient db;

	public StoreMediaModerationRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ModerationRow(UUID mediaReferenceId, String status, String findingsJson, String model, String runId,
			Instant moderatedAt) {
	}

	/**
	 * 人工复核队列行：审核结论 + 媒体上下文（object_key 仅服务端签预览 URL 用， 响应不外泄；domain_id 即 storeId）。
	 */
	public record QueueRow(UUID mediaReferenceId, String status, String findingsJson, String model, String runId,
			Instant moderatedAt, String reviewedBy, Instant reviewedAt, String reviewNote, String mimeType,
			long sizeBytes, String organizationId, String domainId, String objectKey, Instant mediaCreatedAt) {
	}

	/** UPSERT：同一媒体的最新结论生效（保留旧结论语义 = 覆盖）。 */
	public Mono<ModerationRow> upsert(ModerationRow row) {
		var spec = db.sql("""
				INSERT INTO store_media_moderation (
				    media_reference_id, status, findings, model, run_id, moderated_at)
				VALUES (CAST(:id AS uuid), :status, CAST(:findings AS jsonb), :model, :runId, :moderatedAt)
				ON CONFLICT (media_reference_id) DO UPDATE SET
				    status = excluded.status,
				    findings = excluded.findings,
				    model = excluded.model,
				    run_id = excluded.run_id,
				    moderated_at = excluded.moderated_at
				RETURNING media_reference_id::text, status, findings::text, model, run_id, moderated_at
				""").bind("id", row.mediaReferenceId().toString()).bind("status", row.status())
				.bind("findings", row.findingsJson())
				.bind("moderatedAt", row.moderatedAt().atOffset(java.time.ZoneOffset.UTC));
		spec = bindNullable(spec, "model", row.model());
		spec = bindNullable(spec, "runId", row.runId());
		return spec.map(StoreMediaModerationRepository::mapRow).one();
	}

	public Mono<ModerationRow> find(UUID mediaReferenceId) {
		return db
				.sql("SELECT media_reference_id::text, status, findings::text, model, run_id, moderated_at"
						+ " FROM store_media_moderation WHERE media_reference_id=CAST(:id AS uuid)")
				.bind("id", mediaReferenceId.toString()).map(StoreMediaModerationRepository::mapRow).one();
	}

	public Mono<Boolean> exists(UUID mediaReferenceId) {
		return db.sql("SELECT 1 FROM store_media_moderation WHERE media_reference_id=CAST(:id AS uuid)")
				.bind("id", mediaReferenceId.toString()).map(row -> Boolean.TRUE).one().defaultIfEmpty(false);
	}

	/** 队列口径（行查与 COUNT 共用，防分页漂移）：联媒体上下文 + 按状态筛。 */
	private static final String QUEUE_FROM_WHERE = """
			FROM store_media_moderation s
			JOIN media_reference m ON m.id = s.media_reference_id
			WHERE s.status = :status
			""";
	
	/** 人工复核队列：按状态列队（moderated_at 倒序——最新的先处理），联媒体上下文：分页。 */
	public Flux<QueueRow> listQueue(String status, int limit, int offset) {
		return db.sql("""
				SELECT s.media_reference_id::text, s.status, s.findings::text, s.model, s.run_id,
				       s.moderated_at, s.reviewed_by, s.reviewed_at, s.review_note,
				       m.mime_type, m.size_bytes, m.organization_id, m.domain_id,
				       m.object_key, m.created_at
				""" + QUEUE_FROM_WHERE + """
				ORDER BY s.moderated_at DESC
				LIMIT :limit OFFSET :offset
				""").bind("status", status).bind("limit", limit).bind("offset", offset)
				.map(StoreMediaModerationRepository::mapQueueRow).all();
	}
	
	/** 队列总数（与 {@link #listQueue(String, int, int)} 同 WHERE 口径）。 */
	public Mono<Long> countQueue(String status) {
		return db.sql("SELECT COUNT(*)::bigint AS c " + QUEUE_FROM_WHERE).bind("status", status)
				.map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
	}

	/**
	 * 人工裁决：就地覆盖 status（approve→pass / reject→blocked 由调用方映射），留痕裁决人/时间/备注。
	 * 乐观锁=moderated_at 期望值（裁决者看到的版本）：不匹配/无行返回 empty（409/404 由端点映射）。
	 */
	public Mono<ModerationRow> decide(UUID mediaReferenceId, String status, Instant expectedModeratedAt,
			String reviewedBy, String reviewNote, Instant decidedAt) {
		var spec = db.sql("""
				UPDATE store_media_moderation SET
				    status = :status,
				    reviewed_by = :reviewedBy,
				    reviewed_at = :decidedAt,
				    review_note = :reviewNote,
				    moderated_at = :decidedAt
				WHERE media_reference_id = CAST(:id AS uuid)
				  AND moderated_at = CAST(:expected AS timestamptz)
				RETURNING media_reference_id::text, status, findings::text, model, run_id, moderated_at
				""").bind("id", mediaReferenceId.toString()).bind("status", status)
				.bind("expected", expectedModeratedAt.atOffset(java.time.ZoneOffset.UTC))
				.bind("decidedAt", decidedAt.atOffset(java.time.ZoneOffset.UTC)).bind("reviewedBy", reviewedBy);
		String note = reviewNote == null ? "" : reviewNote.trim();
		spec = note.isEmpty() ? spec.bindNull("reviewNote", String.class) : spec.bind("reviewNote", note);
		return spec.map(StoreMediaModerationRepository::mapRow).one();
	}

	private static ModerationRow mapRow(Readable row) {
		OffsetDateTime at = row.get("moderated_at", OffsetDateTime.class);
		return new ModerationRow(UUID.fromString(row.get("media_reference_id", String.class)),
				row.get("status", String.class), row.get("findings", String.class), row.get("model", String.class),
				row.get("run_id", String.class), at == null ? null : at.toInstant());
	}

	private static QueueRow mapQueueRow(Readable row) {
		OffsetDateTime moderatedAt = row.get("moderated_at", OffsetDateTime.class);
		OffsetDateTime reviewedAt = row.get("reviewed_at", OffsetDateTime.class);
		OffsetDateTime mediaCreatedAt = row.get("created_at", OffsetDateTime.class);
		return new QueueRow(UUID.fromString(row.get("media_reference_id", String.class)),
				row.get("status", String.class), row.get("findings", String.class), row.get("model", String.class),
				row.get("run_id", String.class), moderatedAt == null ? null : moderatedAt.toInstant(),
				row.get("reviewed_by", String.class), reviewedAt == null ? null : reviewedAt.toInstant(),
				row.get("review_note", String.class), row.get("mime_type", String.class),
				row.get("size_bytes", Long.class), row.get("organization_id", String.class),
				row.get("domain_id", String.class), row.get("object_key", String.class),
				mediaCreatedAt == null ? null : mediaCreatedAt.toInstant());
	}

	private static DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec, String name,
			String value) {
		return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
}
