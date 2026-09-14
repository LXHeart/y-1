package com.grassland.intelligence.creationstudio.visual;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-09：视觉成品（artifact）持久层。 不可变行：attempt 唯一，同 attempt 重复登记读回既有行；
 * 原图与交付图分开（D10）。
 */
@Component
public class VisualArtifactRepository {

	private final DatabaseClient db;

	public VisualArtifactRepository(DatabaseClient db) {
		this.db = db;
	}

	private static final String COLS = "id, owner_account_id, draft_id, plan_id, plan_revision, item_id, attempt_id,"
			+ " run_id, original_media_id, delivery_media_id, target_aspect, width, height, content_hash,"
			+ " anchor_artifact_id, created_at";

	/** 插入（attempt 幂等）：冲突读回既有行。 */
	public Mono<VisualArtifact> insertOrGet(VisualArtifact row) {
		DatabaseClient.GenericExecuteSpec spec = db.sql("""
				INSERT INTO creation_visual_artifact (id, owner_account_id, draft_id, plan_id, plan_revision,
				    item_id, attempt_id, run_id, original_media_id, delivery_media_id, target_aspect, width,
				    height, content_hash, anchor_artifact_id)
				VALUES (CAST(:id AS uuid), :owner, CAST(:draft AS uuid), CAST(:plan AS uuid), :planRevision,
				    :itemId, CAST(:attempt AS uuid), CAST(:run AS uuid), CAST(:original AS uuid),
				    CAST(:delivery AS uuid), :aspect, :width, :height, CAST(:hash AS char(64)),
				    CAST(:anchor AS uuid))
				ON CONFLICT (attempt_id) DO NOTHING
				""").bind("id", row.id().toString()).bind("owner", row.ownerAccountId())
				.bind("draft", row.draftId().toString()).bind("plan", row.planId().toString())
				.bind("planRevision", row.planRevision()).bind("itemId", row.itemId())
				.bind("attempt", row.attemptId().toString()).bind("original", row.originalMediaId().toString())
				.bind("delivery", row.deliveryMediaId().toString()).bind("aspect", row.targetAspect())
				.bind("width", row.width()).bind("height", row.height()).bind("hash", row.contentHash());
		spec = row.anchorArtifactId() == null
				? spec.bindNull("anchor", String.class)
				: spec.bind("anchor", row.anchorArtifactId().toString());
		spec = row.runId() == null ? spec.bindNull("run", String.class) : spec.bind("run", row.runId().toString());
		return spec.fetch().rowsUpdated().onErrorResume(error -> Mono.just(0L)).then(findByAttempt(row.attemptId()));
	}

	public Mono<VisualArtifact> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM creation_visual_artifact WHERE id = CAST(:p AS uuid)")
				.bind("p", id.toString()).map(VisualArtifactRepository::map).one();
	}

	public Mono<VisualArtifact> findByIdAndOwner(UUID id, String ownerAccountId) {
		return db
				.sql("SELECT " + COLS + " FROM creation_visual_artifact"
						+ " WHERE id = CAST(:p AS uuid) AND owner_account_id = :owner")
				.bind("p", id.toString()).bind("owner", ownerAccountId).map(VisualArtifactRepository::map).one();
	}

	public Mono<VisualArtifact> findByAttempt(UUID attemptId) {
		return db.sql("SELECT " + COLS + " FROM creation_visual_artifact WHERE attempt_id = CAST(:p AS uuid)")
				.bind("p", attemptId.toString()).map(VisualArtifactRepository::map).one();
	}

	public Flux<VisualArtifact> findByOperation(UUID operationId) {
		return db.sql("SELECT " + COLS + " FROM creation_visual_artifact"
				+ " WHERE attempt_id IN (SELECT id FROM creation_visual_item WHERE operation_id = CAST(:p AS uuid))")
				.bind("p", operationId.toString()).map(VisualArtifactRepository::map).all();
	}

	public Flux<VisualArtifact> findByOwnerPlanItem(String ownerAccountId, UUID draftId, UUID planId, String itemId) {
		return db
				.sql("SELECT " + COLS + " FROM creation_visual_artifact"
						+ " WHERE owner_account_id = :owner AND draft_id = CAST(:draft AS uuid)"
						+ " AND plan_id = CAST(:plan AS uuid) AND item_id = :item ORDER BY created_at")
				.bind("owner", ownerAccountId).bind("draft", draftId.toString()).bind("plan", planId.toString())
				.bind("item", itemId).map(VisualArtifactRepository::map).all();
	}

	/** 到期候选（清理扫描）：created_at 早于 cutoff 且未被任何 artifact 作为锚引用、未被草稿采用。 */
	public Flux<VisualArtifact> findExpirableCandidates(OffsetDateTime cutoff, int limit) {
		return db
				.sql("SELECT " + COLS + " FROM creation_visual_artifact candidate" + EXPIRABLE
						+ " ORDER BY candidate.created_at LIMIT :limit")
				.bind("cutoff", cutoff).bind("limit", limit).map(VisualArtifactRepository::map).all();
	}

	private static final String EXPIRABLE = " WHERE candidate.created_at < :cutoff"
			+ " AND NOT EXISTS (SELECT 1 FROM creation_visual_artifact anchor WHERE anchor.anchor_artifact_id=candidate.id)"
			+ " AND NOT EXISTS (SELECT 1 FROM creation_visual_item item WHERE item.anchor_artifact_id=candidate.id AND item.state NOT IN ('failed','cancelled'))"
			+ " AND NOT EXISTS (SELECT 1 FROM creation_draft draft WHERE draft.result_asset_ids::text LIKE '%\"' || candidate.delivery_media_id::text || '\"%'"
			+ " OR draft.workspace_json::text LIKE '%' || candidate.delivery_media_id::text || '%')"
			+ " AND NOT EXISTS (SELECT 1 FROM creation_draft_version version WHERE version.workspace_json::text LIKE '%' || candidate.delivery_media_id::text || '%')";

	public Mono<Boolean> isExpirable(UUID id, OffsetDateTime cutoff) {
		return db.sql("SELECT 1 FROM creation_visual_artifact candidate" + EXPIRABLE + " AND candidate.id=:id")
				.bind("id", id).bind("cutoff", cutoff).map(row -> true).one().defaultIfEmpty(false);
	}

	public Mono<Boolean> delete(UUID id) {
		return db.sql("DELETE FROM creation_visual_artifact WHERE id = CAST(:p AS uuid)").bind("p", id.toString())
				.fetch().rowsUpdated().map(count -> count != null && count > 0);
	}

	private static VisualArtifact map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		return new VisualArtifact(row.get("id", UUID.class), row.get("owner_account_id", String.class),
				row.get("draft_id", UUID.class), row.get("plan_id", UUID.class),
				row.get("plan_revision", Integer.class), row.get("item_id", String.class),
				row.get("attempt_id", UUID.class), row.get("run_id", UUID.class),
				row.get("original_media_id", UUID.class), row.get("delivery_media_id", UUID.class),
				row.get("target_aspect", String.class), row.get("width", Integer.class),
				row.get("height", Integer.class), row.get("content_hash", String.class),
				row.get("anchor_artifact_id", UUID.class), row.get("created_at", OffsetDateTime.class));
	}
}
