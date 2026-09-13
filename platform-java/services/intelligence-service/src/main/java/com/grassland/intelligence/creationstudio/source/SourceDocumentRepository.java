package com.grassland.intelligence.creationstudio.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-02：来源文档仓储（V77）。沿用 {@code CreationDraftRepository} 惯例：
 * timestamptz 读 {@link OffsetDateTime} 转 {@link Instant}，jsonb 以 text
 * 读写、service-local mapper 序列化。幂等键 UNIQUE(owner_account_id, request_id)——同键冲突由
 * service 比较 request_hash 决定重放或 409。
 */
@Component
public class SourceDocumentRepository {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SELECT_BY_ID = """
			SELECT id::text, owner_account_id, draft_id::text AS draft_id, request_id, request_hash,
			       kind, schema_version, title, raw_text, normalized_markdown, content_hash,
			       blocks_json::text AS blocks_json, source_refs_json::text AS source_refs_json,
			       warnings_json::text AS warnings_json, created_at
			FROM creation_source_document
			WHERE id = CAST(:id AS uuid)
			""";

	private final DatabaseClient db;

	public SourceDocumentRepository(DatabaseClient db) {
		this.db = db;
	}

	/** 首次插入；同键已存在时返回 false（由调用方走重放/冲突路径）。 */
	public Mono<Boolean> insert(SourceDocument document) {
		return db.sql("""
				INSERT INTO creation_source_document (
				    id, owner_account_id, draft_id, request_id, request_hash, kind, schema_version,
				    title, raw_text, normalized_markdown, content_hash, blocks_json, source_refs_json,
				    warnings_json)
				VALUES (
				    CAST(:id AS uuid), :ownerAccountId, CAST(:draftId AS uuid), :requestId, :requestHash,
				    :kind, :schemaVersion, :title, :rawText, :normalizedMarkdown, :contentHash,
				    CAST(:blocksJson AS jsonb), CAST(:sourceRefsJson AS jsonb), CAST(:warningsJson AS jsonb))
				ON CONFLICT (owner_account_id, request_id) DO NOTHING
				""").bind("id", document.id().toString()).bind("ownerAccountId", document.ownerAccountId())
				.bind("draftId", document.draftId().toString()).bind("requestId", document.requestId())
				.bind("requestHash", document.requestHash()).bind("kind", document.kind())
				.bind("schemaVersion", document.schemaVersion()).bind("title", document.title())
				.bind("rawText", document.rawText()).bind("normalizedMarkdown", document.normalizedMarkdown())
				.bind("contentHash", document.contentHash()).bind("blocksJson", writeJson(document.blocks()))
				.bind("sourceRefsJson", writeJson(document.sourceRefs()))
				.bind("warningsJson", writeJson(document.warnings())).fetch().rowsUpdated().map(count -> count > 0);
	}

	public Mono<SourceDocument> findById(UUID id) {
		return db.sql(SELECT_BY_ID).bind("id", id.toString()).map(SourceDocumentRepository::map).one();
	}

	/** 按 (owner, requestId) 读取已有操作记录（重放路径）。 */
	public Mono<SourceDocument> findByOwnerAndRequestId(String ownerAccountId, String requestId) {
		return db.sql("""
				SELECT id::text, owner_account_id, draft_id::text AS draft_id, request_id, request_hash,
				       kind, schema_version, title, raw_text, normalized_markdown, content_hash,
				       blocks_json::text AS blocks_json, source_refs_json::text AS source_refs_json,
				       warnings_json::text AS warnings_json, created_at
				FROM creation_source_document
				WHERE owner_account_id = :owner AND request_id = :requestId
				""").bind("owner", ownerAccountId).bind("requestId", requestId).map(SourceDocumentRepository::map)
				.one();
	}

	private static SourceDocument map(Readable row) {
		List<SourceDocument.Block> blocks = readJson(row.get("blocks_json", String.class)).stream()
				.map(item -> new SourceDocument.Block(text(item.get("id")), text(item.get("kind")),
						((Number) item.get("position")).intValue(), ((Number) item.get("startCodePoint")).intValue(),
						((Number) item.get("endCodePoint")).intValue(), text(item.get("text")),
						text(item.get("textHash"))))
				.toList();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> sourceRefs = (List<Map<String, Object>>) (List<?>) readJson(
				row.get("source_refs_json", String.class));
		return new SourceDocument(UUID.fromString(row.get("id", String.class)),
				row.get("owner_account_id", String.class), UUID.fromString(row.get("draft_id", String.class)),
				row.get("request_id", String.class), row.get("request_hash", String.class),
				row.get("kind", String.class), row.get("schema_version", Integer.class), row.get("title", String.class),
				row.get("raw_text", String.class), row.get("normalized_markdown", String.class),
				row.get("content_hash", String.class), blocks, sourceRefs,
				readJson(row.get("warnings_json", String.class)).stream()
						.map(item -> item == null ? null : item.toString()).toList(),
				toInstant(row.get("created_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}

	private static List<Map<String, Object>> readJson(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
			});
		} catch (Exception error) {
			throw new IllegalStateException("creation_source_document JSON 列损坏", error);
		}
	}

	private static String writeJson(Object value) {
		try {
			return MAPPER.writeValueAsString(value == null ? List.of() : value);
		} catch (Exception error) {
			throw new IllegalStateException("creation_source_document JSON 序列化失败", error);
		}
	}
}
