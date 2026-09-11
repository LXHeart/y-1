package com.grassland.intelligence.creationcanvas;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 独立画布文档存取（任务书 #100 C100-09 / V73 / API-08/09）。
 *
 * <p>写路径两条：{@link #insert}（首建，draft_id 唯一冲突返回 0 行）与 {@link #casUpdate}
 * （revision 匹配才 +1 落库）。画布写不触碰 creation_draft（正文/版本不受布局影响，§7.3）。
 */
@Component
public class CreationCanvasRepository {

	private final DatabaseClient db;

	public CreationCanvasRepository(DatabaseClient db) {
		this.db = db;
	}

	public record CanvasRow(UUID id, UUID draftId, String accountId, int schemaVersion, long revision,
			String documentJson, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	private static final String COLS = "id::text, draft_id::text, account_id, schema_version, revision, "
			+ "document::text, created_at, updated_at";

	public Mono<CanvasRow> insert(UUID draftId, String accountId, String documentJson) {
		return insert(draftId, accountId, 1, documentJson);
	}

	public Mono<CanvasRow> insert(UUID draftId, String accountId, int schemaVersion, String documentJson) {
		String id = UUID.randomUUID().toString();
		return db.sql("INSERT INTO creation_canvas_document(id, draft_id, account_id, schema_version, revision, "
						+ "document) VALUES (CAST(:id AS uuid), CAST(:draft AS uuid), :account, "
						+ ":schemaVersion, 1, CAST(:document AS jsonb)) "
						+ "ON CONFLICT (draft_id) DO NOTHING")
				.bind("id", id).bind("draft", draftId.toString()).bind("account", accountId)
				.bind("schemaVersion", schemaVersion)
				.bind("document", documentJson)
				.fetch().rowsUpdated()
				.flatMap(inserted -> inserted == 0 ? Mono.empty() : findByDraftId(draftId));
	}

	/** CAS 保存：revision 匹配才落库并 +1；不匹配返回空（调用方复读裁决 409 冲突详情）。 */
	public Mono<CanvasRow> casUpdate(UUID draftId, long expectedRevision, String documentJson) {
		return db.sql("UPDATE creation_canvas_document SET revision=revision+1, "
						+ "schema_version=:schemaVersion, "
						+ "document=CAST(:document AS jsonb), updated_at=now() "
						+ "WHERE draft_id=CAST(:draft AS uuid) AND revision=:expected "
						+ "RETURNING " + COLS)
				.bind("draft", draftId.toString()).bind("expected", expectedRevision)
				.bind("schemaVersion", 1)
				.bind("document", documentJson)
				.map(CreationCanvasRepository::map)
				.one();
	}

	public Mono<CanvasRow> findByDraftId(UUID draftId) {
		return db.sql("SELECT " + COLS + " FROM creation_canvas_document WHERE draft_id=CAST(:id AS uuid)")
				.bind("id", draftId.toString())
				.map(CreationCanvasRepository::map)
				.one();
	}

	/**
	 * 跨项目 canonical 防线（§6.3）：shot/take 引用存在但归属其他分镜 → true。
	 * 不存在（历史失效引用）→ false，由读侧以不可用态呈现（§7.3）。
	 */
	public Mono<Boolean> belongsToOtherStoryboard(String kind, String refId, String storyboardId) {
		String sql = "shot".equals(kind)
				? "SELECT COUNT(*) FROM video_shot WHERE id=CAST(:ref AS uuid) "
						+ "AND storyboard_id <> CAST(:storyboard AS uuid)"
				: "SELECT COUNT(*) FROM video_shot_take t JOIN video_shot s ON s.id = t.shot_id "
						+ "WHERE t.id=CAST(:ref AS uuid) AND s.storyboard_id <> CAST(:storyboard AS uuid)";
		return db.sql(sql).bind("ref", refId).bind("storyboard", storyboardId)
				.map((row, meta) -> row.get(0, Long.class) != null && row.get(0, Long.class) > 0)
				.one();
	}

	/** 批量探测（结构校验后逐条往返，上限受节点数钳制）：任一引用跨项目 → true。 */
	public Mono<Boolean> anyCrossProjectReference(java.util.List<String[]> kindAndRefIds, String storyboardId) {
		return reactor.core.publisher.Flux.fromIterable(kindAndRefIds)
				.concatMap(pair -> belongsToOtherStoryboard(pair[0], pair[1], storyboardId))
				.any(Boolean::booleanValue)
				.defaultIfEmpty(false);
	}

	private static CanvasRow map(Row row, RowMetadata meta) {
		return new CanvasRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("draft_id", String.class)), row.get("account_id", String.class),
				row.get("schema_version", Integer.class), row.get("revision", Long.class),
				row.get("document", String.class), row.get("created_at", OffsetDateTime.class),
				row.get("updated_at", OffsetDateTime.class));
	}
}
