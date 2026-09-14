package com.grassland.intelligence.creationstudio.plan;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-05（V79）：视觉计划仓储。幂等键 UNIQUE(owner_account_id, request_id)；
 * revision 快照只插入（表上触发器拒绝 UPDATE／DELETE）；推进指针／清确认与 revision 写入 同事务（§7.3.2「计划写
 * revision、推进指针、清确认在一个事务」）。
 */
@Component
public class VisualPlanRepository {

	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public VisualPlanRepository(DatabaseClient db, TransactionalOperator transactions) {
		this.db = db;
		this.transactions = transactions;
	}

	private static final String SELECT = """
			SELECT id::text, owner_account_id, draft_id::text AS draft_id, request_id, request_hash,
			       source_document_id::text AS source_document_id, source_content_hash,
			       base_draft_version, base_content_hash, recipe_id, recipe_version, upstream_commit,
			       input_snapshot_json::text AS input_snapshot_json, prompt_ciphertext, prompt_hash,
			       status, current_revision, confirmed_revision, confirmed_draft_version,
			       confirmed_content_hash, confirmed_at, confirmed_actor, run_id::text AS run_id,
			       error_code, created_at, updated_at
			FROM creation_visual_plan
			""";

	/** 首次占位；同键已存在返回 0（重放／冲突由 service 比较 request_hash）。 */
	public Mono<Boolean> insertPlaceholder(VisualPlan.PlanRow row) {
		return db.sql("""
				INSERT INTO creation_visual_plan (
				    id, owner_account_id, draft_id, request_id, request_hash, source_document_id,
				    source_content_hash, base_draft_version, base_content_hash, recipe_id, recipe_version,
				    upstream_commit, input_snapshot_json, status, created_at, updated_at)
				VALUES (
				    CAST(:id AS uuid), :ownerAccountId, CAST(:draftId AS uuid), :requestId, :requestHash,
				    CAST(:sourceDocumentId AS uuid), :sourceContentHash, :baseDraftVersion, :baseContentHash,
				    :recipeId, :recipeVersion, :upstreamCommit, CAST(:inputSnapshotJson AS jsonb),
				    'preparing', :now, :now)
				ON CONFLICT (owner_account_id, request_id) DO NOTHING
				""").bind("id", row.id().toString()).bind("ownerAccountId", row.ownerAccountId())
				.bind("draftId", row.draftId().toString()).bind("requestId", row.requestId())
				.bind("requestHash", row.requestHash()).bind("sourceDocumentId", row.sourceDocumentId().toString())
				.bind("sourceContentHash", row.sourceContentHash()).bind("baseDraftVersion", row.baseDraftVersion())
				.bind("baseContentHash", row.baseContentHash()).bind("recipeId", row.recipeId())
				.bind("recipeVersion", row.recipeVersion()).bind("upstreamCommit", row.upstreamCommit())
				.bind("inputSnapshotJson", row.inputSnapshotJson() == null ? "{}" : row.inputSnapshotJson())
				.bind("now", row.createdAt()).fetch().rowsUpdated().map(count -> count > 0);
	}

	/** 模型调用前持久化 runId（onPrepared 契约：失败不调用模型）。 */
	public Mono<Integer> attachRun(UUID id, UUID runId) {
		return db
				.sql("UPDATE creation_visual_plan SET run_id = CAST(:runId AS uuid), updated_at = now() "
						+ "WHERE id = CAST(:id AS uuid) AND status = 'preparing'")
				.bind("runId", runId.toString()).bind("id", id.toString()).fetch().rowsUpdated().map(Long::intValue);
	}

	public record RevisionRow(UUID planId, int revision, String documentJson, String documentHash,
			OffsetDateTime createdAt) {
	}

	public Mono<RevisionRow> findRevision(UUID planId, int revision) {
		return db.sql("""
				SELECT plan_id::text AS plan_id, revision, document_json::text AS document_json,
				       document_hash, created_at
				FROM creation_visual_plan_revision WHERE plan_id = CAST(:planId AS uuid) AND revision = :revision
				""").bind("planId", planId.toString()).bind("revision", revision)
				.map(row -> new RevisionRow(planId, revision, row.get("document_json", String.class),
						row.get("document_hash", String.class), row.get("created_at", OffsetDateTime.class)))
				.one();
	}

	/**
	 * 首个 revision 落库 + 状态推进（同一事务）：INSERT revision 1 与 UPDATE plan→ready 原子完成，
	 * 不产生「ready 但无 revision」或「revision 落库但仍 preparing」的半更新计划。
	 */
	/**
	 * 首个 revision 落库 + 状态推进（同一事务）：INSERT revision 1 与 UPDATE plan→ready 原子完成； CAS
	 * 未命中时在事务内抛错回滚 INSERT，不产生「有 revision 但仍 preparing」的半更新计划。
	 */
	public Mono<Integer> completeReady(UUID id, UUID runId, String documentJson, String documentHash,
			String promptCiphertext, String promptHash) {
		return db
				.sql("INSERT INTO creation_visual_plan_revision (plan_id, revision, document_json, document_hash) "
						+ "VALUES (CAST(:id AS uuid), 1, CAST(:documentJson AS jsonb), :documentHash)")
				.bind("id", id.toString()).bind("documentJson", documentJson).bind("documentHash",
						documentHash)
				.then().then(
						db.sql("""
								UPDATE creation_visual_plan
								SET status = 'ready', current_revision = 1,
								    run_id = COALESCE(CAST(:runId AS uuid), run_id),
								    prompt_ciphertext = COALESCE(prompt_ciphertext, :promptCiphertext), prompt_hash = COALESCE(prompt_hash, :promptHash),
								    error_code = NULL, updated_at = now()
								WHERE id = CAST(:id AS uuid) AND status = 'preparing'
								""")
								.bind("id", id.toString()).bind("runId", runId == null ? null : runId.toString())
								.bind("promptCiphertext", promptCiphertext).bind("promptHash", promptHash).fetch()
								.rowsUpdated())
				.flatMap(count -> count > 0
						? Mono.just(count.intValue())
						: Mono.error(new IllegalStateException("plan preparing CAS missed")))
				.as(transactions::transactional);
	}

	/** 模型输出违约（确定性失败：保留 run 但状态 failed，不可确认／估算）。 */
	public Mono<Integer> completeInvalid(UUID id, UUID runId, String errorCode) {
		var spec = db.sql("""
				UPDATE creation_visual_plan
				SET status = 'failed', error_code = :errorCode,
				    run_id = COALESCE(CAST(:runId AS uuid), run_id), updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'preparing'
				""").bind("id", id.toString()).bind("errorCode", errorCode);
		var bound = runId == null ? spec.bindNull("runId", String.class) : spec.bind("runId", runId.toString());
		return bound.fetch().rowsUpdated().map(Long::intValue);
	}

	public Mono<Integer> completeFailed(UUID id, UUID runId, String errorCode) {
		var spec = db.sql("""
				UPDATE creation_visual_plan
				SET status = CASE WHEN run_id IS NOT NULL THEN 'unknown' ELSE 'failed' END,
				    error_code = :errorCode,
				    run_id = COALESCE(CAST(:runId AS uuid), run_id), updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'preparing'
				""").bind("id", id.toString()).bind("errorCode", errorCode);
		var bound = runId == null ? spec.bindNull("runId", String.class) : spec.bind("runId", runId.toString());
		return bound.fetch().rowsUpdated().map(Long::intValue);
	}

	/**
	 * PATCH（§6.5 计划编辑）：追加不可变 revision 快照 + 推进 current_revision + 清确认，一个事务； CAS
	 * 未命中（并发推进／状态漂移）时事务内抛错回滚 INSERT，不留孤儿 revision 行。
	 */
	public Mono<Integer> appendRevision(UUID id, int expectedCurrent, int nextRevision, String documentJson,
			String documentHash) {
		return db.sql("""
				INSERT INTO creation_visual_plan_revision (plan_id, revision, document_json, document_hash)
				VALUES (CAST(:id AS uuid), :nextRevision, CAST(:documentJson AS jsonb), :documentHash)
				""").bind("id", id.toString()).bind("nextRevision", nextRevision).bind("documentJson", documentJson)
				.bind("documentHash", documentHash).then()
				.then(db.sql("""
						UPDATE creation_visual_plan
						SET current_revision = :nextRevision, confirmed_revision = NULL,
						    confirmed_draft_version = NULL, confirmed_content_hash = NULL,
						    confirmed_at = NULL, confirmed_actor = NULL, updated_at = now()
						WHERE id = CAST(:id AS uuid)
						  AND status = 'ready' AND current_revision = :expectedCurrent
						""").bind("id", id.toString()).bind("nextRevision", nextRevision)
						.bind("expectedCurrent", expectedCurrent).fetch().rowsUpdated())
				.flatMap(count -> count > 0
						? Mono.just(count.intValue())
						: Mono.error(new IllegalStateException("plan revision CAS missed")))
				.as(transactions::transactional);
	}

	/** 确认（§6.5 计划确认）：只确认当前 revision，记录确认时版本／hash／actor。 */
	public Mono<Integer> confirm(UUID id, int expectedRevision, int draftVersion, String contentHash, String actor) {
		return db.sql("""
				UPDATE creation_visual_plan
				SET confirmed_revision = :expectedRevision, confirmed_draft_version = :draftVersion,
				    confirmed_content_hash = :contentHash, confirmed_at = now(), confirmed_actor = :actor,
				    updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'ready' AND current_revision = :expectedRevision
				""").bind("id", id.toString()).bind("expectedRevision", expectedRevision)
				.bind("draftVersion", draftVersion).bind("contentHash", contentHash).bind("actor", actor).fetch()
				.rowsUpdated().map(Long::intValue);
	}

	public Mono<VisualPlan.PlanRow> findById(UUID id) {
		return db.sql(SELECT + " WHERE id = CAST(:id AS uuid)").bind("id", id.toString()).map(VisualPlanRepository::map)
				.one();
	}

	public Mono<VisualPlan.PlanRow> findByOwnerAndRequestId(String ownerAccountId, String requestId) {
		return db.sql(SELECT + " WHERE owner_account_id = :owner AND request_id = :requestId")
				.bind("owner", ownerAccountId).bind("requestId", requestId).map(VisualPlanRepository::map).one();
	}

	public Mono<VisualPlan.PlanRow> lockById(UUID id) {
		return db.sql(SELECT + " WHERE id = CAST(:id AS uuid) FOR UPDATE").bind("id", id.toString())
				.map(VisualPlanRepository::map).one();
	}

	public record StudioApply(String requestHash, UUID resourceId, int appliedVersion, Map<String, Object> result) {
	}
	public Mono<StudioApply> findStudioApply(String owner, String kind, String requestId) {
		return db.sql(
				"SELECT request_hash, resource_id, applied_draft_version, result_json::text AS result FROM creation_studio_apply"
						+ " WHERE owner_account_id=:owner AND kind=:kind AND request_id=:request")
				.bind("owner", owner).bind("kind", kind).bind("request", requestId)
				.map(row -> new StudioApply(row.get("request_hash", String.class), row.get("resource_id", UUID.class),
						row.get("applied_draft_version", Integer.class),
						PlanJson.readJson(row.get("result", String.class))))
				.one();
	}

	public Mono<Boolean> recordStudioApply(String owner, String kind, String requestId, String hash, UUID resourceId,
			int version) {
		return recordStudioApply(owner, kind, requestId, hash, resourceId, version, Map.of());
	}

	public Mono<Boolean> recordStudioApply(String owner, String kind, String requestId, String hash, UUID resourceId,
			int version, Map<String, Object> result) {
		return db.sql(
				"INSERT INTO creation_studio_apply(id,owner_account_id,kind,request_id,request_hash,resource_id,applied_draft_version,result_json)"
						+ " VALUES (:id,:owner,:kind,:request,:hash,:resource,:version,CAST(:result AS jsonb)) ON CONFLICT (owner_account_id,kind,request_id) DO NOTHING")
				.bind("id", UUID.randomUUID()).bind("owner", owner).bind("kind", kind).bind("request", requestId)
				.bind("hash", hash).bind("resource", resourceId).bind("version", version)
				.bind("result", PlanJson.json(result)).fetch().rowsUpdated().map(count -> count > 0);
	}

	public Mono<Void> expirePreparing(UUID id) {
		return db
				.sql("UPDATE creation_visual_plan SET status=CASE WHEN run_id IS NULL THEN 'failed' ELSE 'unknown' END,"
						+ " error_code='STUDIO_UNKNOWN_OUTCOME',updated_at=now() WHERE id=:id AND status='preparing'"
						+ " AND updated_at < now() - interval '150 seconds'")
				.bind("id", id).then();
	}

	public Mono<Void> capturePrompt(UUID id, UUID runId, String ciphertext, String hash) {
		return db.sql(
				"UPDATE creation_visual_plan SET run_id=:run,prompt_ciphertext=:cipher,prompt_hash=:hash WHERE id=:id AND status='preparing'")
				.bind("id", id).bind("run", runId).bind("cipher", ciphertext).bind("hash", hash).then();
	}

	/** 推荐缺省（§6.5）：是否存在本人确认过的 story 策略计划（体验型内容确认经历）。 */
	public Mono<Boolean> hasConfirmedStoryPlan(String ownerAccountId) {
		return db.sql("""
				SELECT 1 FROM creation_visual_plan
				WHERE owner_account_id = :owner AND confirmed_revision IS NOT NULL
				  AND input_snapshot_json->>'strategy' = 'story'
				LIMIT 1
				""").bind("owner", ownerAccountId).map(row -> true).one().defaultIfEmpty(false);
	}

	// ---- quote ----

	public record QuoteRow(UUID id, String ownerAccountId, UUID planId, int planRevision, String requestId,
			String requestHash, Map<String, Object> quote, OffsetDateTime createdAt, OffsetDateTime expiresAt) {
	}

	public Mono<Boolean> insertQuote(QuoteRow row) {
		return db.sql("""
				INSERT INTO creation_visual_quote (
				    id, owner_account_id, plan_id, plan_revision, request_id, request_hash, quote_json, expires_at)
				VALUES (
				    CAST(:id AS uuid), :ownerAccountId, CAST(:planId AS uuid), :planRevision, :requestId,
				    :requestHash, CAST(:quoteJson AS jsonb), :expiresAt)
				ON CONFLICT (owner_account_id, request_id) DO NOTHING
				""").bind("id", row.id().toString()).bind("ownerAccountId", row.ownerAccountId())
				.bind("planId", row.planId().toString()).bind("planRevision", row.planRevision())
				.bind("requestId", row.requestId()).bind("requestHash", row.requestHash())
				.bind("quoteJson", PlanJson.json(row.quote())).bind("expiresAt", row.expiresAt()).fetch().rowsUpdated()
				.map(count -> count > 0);
	}

	/** C101-10：按 ID 读本人 quote（任务创建时核对快照与有效期）。 */
	public Mono<QuoteRow> findQuoteById(UUID quoteId, String ownerAccountId) {
		return db.sql("""
				SELECT id::text, owner_account_id, plan_id::text AS plan_id, plan_revision, request_id,
				       request_hash, quote_json::text AS quote_json, created_at, expires_at
				FROM creation_visual_quote WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
				""").bind("id", quoteId.toString()).bind("owner", ownerAccountId).map(VisualPlanRepository::mapQuote)
				.one();
	}

	public Mono<QuoteRow> findQuoteByOwnerAndRequestId(String ownerAccountId, String requestId) {
		return db.sql("""
				SELECT id::text, owner_account_id, plan_id::text AS plan_id, plan_revision, request_id,
				       request_hash, quote_json::text AS quote_json, created_at, expires_at
				FROM creation_visual_quote WHERE owner_account_id = :owner AND request_id = :requestId
				""").bind("owner", ownerAccountId).bind("requestId", requestId).map(VisualPlanRepository::mapQuote)
				.one();
	}

	public static VisualPlan.PlanRow map(Readable row) {
		return new VisualPlan.PlanRow(uuid(row.get("id", String.class)), row.get("owner_account_id", String.class),
				uuid(row.get("draft_id", String.class)), row.get("request_id", String.class),
				row.get("request_hash", String.class), uuid(row.get("source_document_id", String.class)),
				row.get("source_content_hash", String.class), row.get("base_draft_version", Integer.class),
				row.get("base_content_hash", String.class), row.get("recipe_id", String.class),
				row.get("recipe_version", String.class), row.get("upstream_commit", String.class),
				row.get("input_snapshot_json", String.class), row.get("prompt_ciphertext", String.class),
				row.get("prompt_hash", String.class), row.get("status", String.class),
				row.get("current_revision", Integer.class), row.get("confirmed_revision", Integer.class),
				row.get("confirmed_draft_version", Integer.class), row.get("confirmed_content_hash", String.class),
				row.get("confirmed_at", OffsetDateTime.class), row.get("confirmed_actor", String.class),
				uuid(row.get("run_id", String.class)), row.get("error_code", String.class),
				row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class));
	}

	private static QuoteRow mapQuote(Readable row) {
		Map<String, Object> quote = PlanJson.readJson(row.get("quote_json", String.class));
		return new QuoteRow(uuid(row.get("id", String.class)), row.get("owner_account_id", String.class),
				uuid(row.get("plan_id", String.class)), row.get("plan_revision", Integer.class),
				row.get("request_id", String.class), row.get("request_hash", String.class), quote,
				row.get("created_at", OffsetDateTime.class), row.get("expires_at", OffsetDateTime.class));
	}

	private static UUID uuid(String value) {
		return value == null ? null : UUID.fromString(value);
	}
}
