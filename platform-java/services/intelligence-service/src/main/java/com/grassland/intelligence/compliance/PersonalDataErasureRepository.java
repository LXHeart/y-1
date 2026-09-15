package com.grassland.intelligence.compliance;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 分阶段清理持久层（任务书 #103 C103-09 / §7.2 / §7.4）。
 *
 * <p>
 * 资源按 kind 登记为「批次 SQL（:a 账号、:n 批上限）+ 残留计数 SQL」成对定义，顺序即依赖序（子先父后）。 scope 一律从
 * owner/父关系解析（§7.4：organization_id 非空的组织内容、组织 BYOK、他人资产不清理）；孤儿行含本人私有内容仍计入 （NOT
 * EXISTS 只排除「父存在且属组织」的行，父已删的孤儿照删）。 计费事实表（ai_run、video_generation_job）只脱敏个人载荷，
 * 金额/模型/用量/经济键保留（D10）。
 */
@Component
public class PersonalDataErasureRepository {

	/** 清理资源 kind：批次语句与残留核对成对，顺序=依赖序。 */
	record EraseKind(String kind, String batchSql, String residueSql) {
	}

	/** §7.4 表族清单（子先父后；mask 类只清个人载荷）。 */
	static final List<EraseKind> KINDS = List.of(
			kind("wechat_media_mapping",
					"DELETE FROM creation_wechat_media_mapping WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_media_mapping WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_media_mapping WHERE owner_account_id = :a"),
			kind("wechat_draft_sync",
					"DELETE FROM creation_wechat_draft_sync WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_draft_sync WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_draft_sync WHERE owner_account_id = :a"),
			kind("wechat_account",
					"DELETE FROM creation_wechat_account WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_account WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_account WHERE owner_account_id = :a"),
			kind("canvas_agent_plan",
					"DELETE FROM creation_canvas_agent_plan WHERE ctid IN (SELECT ctid"
							+ " FROM creation_canvas_agent_plan WHERE account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_canvas_agent_plan WHERE account_id = :a"),
			// 组织父草稿的画布归组织规则；父已删的孤儿画布仍含本人私有内容 → 清理。
			kind("canvas_document",
					"DELETE FROM creation_canvas_document WHERE ctid IN (SELECT ctid"
							+ " FROM creation_canvas_document WHERE account_id = :a AND NOT EXISTS ("
							+ " SELECT 1 FROM creation_draft d WHERE d.id = creation_canvas_document.draft_id"
							+ " AND d.organization_id IS NOT NULL) LIMIT :n)",
					"SELECT count(*) FROM creation_canvas_document WHERE account_id = :a AND NOT EXISTS ("
							+ " SELECT 1 FROM creation_draft d WHERE d.id = creation_canvas_document.draft_id"
							+ " AND d.organization_id IS NOT NULL)"),
			kind("storyboard_workspace",
					"DELETE FROM video_storyboard_workspace WHERE ctid IN (SELECT ctid"
							+ " FROM video_storyboard_workspace WHERE account_id = :a LIMIT :n)",
					"SELECT count(*) FROM video_storyboard_workspace WHERE account_id = :a"),
			kind("storyboard_variant",
					"DELETE FROM video_storyboard_variant WHERE ctid IN (SELECT ctid"
							+ " FROM video_storyboard_variant WHERE account_id = :a LIMIT :n)",
					"SELECT count(*) FROM video_storyboard_variant WHERE account_id = :a"),
			kind("shot_take",
					"DELETE FROM video_shot_take WHERE ctid IN (SELECT ctid FROM video_shot_take"
							+ " WHERE shot_id IN (SELECT sh.id FROM video_shot sh JOIN video_storyboard s"
							+ " ON s.id = sh.storyboard_id WHERE s.account_id = :a AND s.organization_id IS NULL)"
							+ " LIMIT :n)",
					"SELECT count(*) FROM video_shot_take WHERE shot_id IN (SELECT sh.id FROM video_shot sh"
							+ " JOIN video_storyboard s ON s.id = sh.storyboard_id WHERE s.account_id = :a"
							+ " AND s.organization_id IS NULL)"),
			kind("shot_audio",
					"DELETE FROM video_shot_audio WHERE ctid IN (SELECT ctid FROM video_shot_audio"
							+ " WHERE shot_id IN (SELECT sh.id FROM video_shot sh JOIN video_storyboard s"
							+ " ON s.id = sh.storyboard_id WHERE s.account_id = :a AND s.organization_id IS NULL)"
							+ " LIMIT :n)",
					"SELECT count(*) FROM video_shot_audio WHERE shot_id IN (SELECT sh.id FROM video_shot sh"
							+ " JOIN video_storyboard s ON s.id = sh.storyboard_id WHERE s.account_id = :a"
							+ " AND s.organization_id IS NULL)"),
			kind("shot_media_source", "DELETE FROM video_shot_media_source WHERE ctid IN (SELECT ctid"
					+ " FROM video_shot_media_source WHERE storyboard_id IN (SELECT id"
					+ " FROM video_storyboard WHERE account_id = :a AND organization_id IS NULL)" + " LIMIT :n)",
					"SELECT count(*) FROM video_shot_media_source WHERE storyboard_id IN (SELECT id"
							+ " FROM video_storyboard WHERE account_id = :a AND organization_id IS NULL)"),
			kind("video_production_task",
					"DELETE FROM video_production_task WHERE ctid IN (SELECT ctid FROM video_production_task"
							+ " WHERE account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM video_production_task WHERE account_id = :a"
							+ " AND organization_id IS NULL"),
			kind("video_shot",
					"DELETE FROM video_shot WHERE ctid IN (SELECT ctid FROM video_shot WHERE storyboard_id IN"
							+ " (SELECT id FROM video_storyboard WHERE account_id = :a"
							+ " AND organization_id IS NULL) LIMIT :n)",
					"SELECT count(*) FROM video_shot WHERE storyboard_id IN (SELECT id FROM video_storyboard"
							+ " WHERE account_id = :a AND organization_id IS NULL)"),
			kind("video_storyboard",
					"DELETE FROM video_storyboard WHERE ctid IN (SELECT ctid FROM video_storyboard"
							+ " WHERE account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM video_storyboard WHERE account_id = :a AND organization_id IS NULL"),
			kind("visual_item",
					"DELETE FROM creation_visual_item WHERE ctid IN (SELECT ctid FROM creation_visual_item"
							+ " WHERE operation_id IN (SELECT id FROM card_series_operation"
							+ " WHERE owner_account_id = :a) LIMIT :n)",
					"SELECT count(*) FROM creation_visual_item WHERE operation_id IN (SELECT id"
							+ " FROM card_series_operation WHERE owner_account_id = :a)"),
			kind("card_series_operation",
					"DELETE FROM card_series_operation WHERE ctid IN (SELECT ctid FROM card_series_operation"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM card_series_operation WHERE owner_account_id = :a"),
			kind("visual_artifact",
					"DELETE FROM creation_visual_artifact WHERE ctid IN (SELECT ctid FROM creation_visual_artifact"
							+ " WHERE owner_account_id = :a AND NOT EXISTS (SELECT 1 FROM creation_draft d"
							+ " WHERE d.id = creation_visual_artifact.draft_id"
							+ " AND d.organization_id IS NOT NULL) LIMIT :n)",
					"SELECT count(*) FROM creation_visual_artifact WHERE owner_account_id = :a AND NOT EXISTS ("
							+ " SELECT 1 FROM creation_draft d WHERE d.id = creation_visual_artifact.draft_id"
							+ " AND d.organization_id IS NOT NULL)"),
			kind("visual_quote",
					"DELETE FROM creation_visual_quote WHERE ctid IN (SELECT ctid FROM creation_visual_quote"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_visual_quote WHERE owner_account_id = :a"),
			kind("studio_apply",
					"DELETE FROM creation_studio_apply WHERE ctid IN (SELECT ctid FROM creation_studio_apply"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_studio_apply WHERE owner_account_id = :a"),
			kind("visual_plan_revision",
					"DELETE FROM creation_visual_plan_revision WHERE ctid IN (SELECT ctid"
							+ " FROM creation_visual_plan_revision WHERE plan_id IN (SELECT id"
							+ " FROM creation_visual_plan WHERE owner_account_id = :a) LIMIT :n)",
					"SELECT count(*) FROM creation_visual_plan_revision WHERE plan_id IN (SELECT id"
							+ " FROM creation_visual_plan WHERE owner_account_id = :a)"),
			kind("visual_plan",
					"DELETE FROM creation_visual_plan WHERE ctid IN (SELECT ctid FROM creation_visual_plan"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_visual_plan WHERE owner_account_id = :a"),
			kind("text_proposal",
					"DELETE FROM creation_text_proposal WHERE ctid IN (SELECT ctid FROM creation_text_proposal"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_text_proposal WHERE owner_account_id = :a"),
			kind("source_document",
					"DELETE FROM creation_source_document WHERE ctid IN (SELECT ctid"
							+ " FROM creation_source_document WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_source_document WHERE owner_account_id = :a"),
			kind("creation_export",
					"DELETE FROM creation_export WHERE ctid IN (SELECT ctid FROM creation_export"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_export WHERE owner_account_id = :a"),
			kind("draft_version",
					"DELETE FROM creation_draft_version WHERE ctid IN (SELECT ctid FROM creation_draft_version"
							+ " WHERE draft_id IN (SELECT id FROM creation_draft WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL) LIMIT :n)",
					"SELECT count(*) FROM creation_draft_version WHERE draft_id IN (SELECT id FROM creation_draft"
							+ " WHERE owner_account_id = :a AND organization_id IS NULL)"),
			kind("creation_draft",
					"DELETE FROM creation_draft WHERE ctid IN (SELECT ctid FROM creation_draft"
							+ " WHERE owner_account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM creation_draft WHERE owner_account_id = :a" + " AND organization_id IS NULL"),
			kind("asset_embedding",
					"DELETE FROM content_asset_embedding WHERE ctid IN (SELECT ctid FROM content_asset_embedding"
							+ " WHERE asset_id IN (SELECT id FROM content_asset WHERE owner_account_id = :a"
							+ " AND library_type = 'personal') LIMIT :n)",
					"SELECT count(*) FROM content_asset_embedding WHERE asset_id IN (SELECT id FROM content_asset"
							+ " WHERE owner_account_id = :a AND library_type = 'personal')"),
			// 他人授予本账号的授权撤销（§7.4：shared 内容保留，但本人持有的 grant 撤销）。
			kind("asset_grant_received",
					"DELETE FROM content_asset_grant WHERE ctid IN (SELECT ctid FROM content_asset_grant"
							+ " WHERE grantee_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM content_asset_grant WHERE grantee_account_id = :a"),
			kind("asset_grant",
					"DELETE FROM content_asset_grant WHERE ctid IN (SELECT ctid FROM content_asset_grant"
							+ " WHERE asset_id IN (SELECT id FROM content_asset WHERE owner_account_id = :a"
							+ " AND library_type = 'personal') LIMIT :n)",
					"SELECT count(*) FROM content_asset_grant WHERE asset_id IN (SELECT id FROM content_asset"
							+ " WHERE owner_account_id = :a AND library_type = 'personal')"),
			kind("asset_version",
					"DELETE FROM content_asset_version WHERE ctid IN (SELECT ctid FROM content_asset_version"
							+ " WHERE asset_id IN (SELECT id FROM content_asset WHERE owner_account_id = :a"
							+ " AND library_type = 'personal') LIMIT :n)",
					"SELECT count(*) FROM content_asset_version WHERE asset_id IN (SELECT id FROM content_asset"
							+ " WHERE owner_account_id = :a AND library_type = 'personal')"),
			kind("content_asset",
					"DELETE FROM content_asset WHERE ctid IN (SELECT ctid FROM content_asset"
							+ " WHERE owner_account_id = :a AND library_type = 'personal' LIMIT :n)",
					"SELECT count(*) FROM content_asset WHERE owner_account_id = :a"
							+ " AND library_type = 'personal'"),
			kind("speech_transcription",
					"DELETE FROM speech_transcription WHERE ctid IN (SELECT ctid FROM speech_transcription"
							+ " WHERE owner_account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM speech_transcription WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL"),
			kind("content_fingerprint",
					"DELETE FROM content_fingerprint WHERE ctid IN (SELECT ctid FROM content_fingerprint"
							+ " WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM content_fingerprint WHERE owner_account_id = :a"),
			kind("creation_generation",
					"DELETE FROM creation_generation WHERE ctid IN (SELECT ctid FROM creation_generation"
							+ " WHERE owner_account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM creation_generation WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL"),
			kind("creation_context_snapshot",
					"DELETE FROM creation_context_snapshot WHERE ctid IN (SELECT ctid"
							+ " FROM creation_context_snapshot WHERE account_id = :a"
							+ " AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM creation_context_snapshot WHERE account_id = :a"
							+ " AND organization_id IS NULL"),
			kind("style_preferences",
					"DELETE FROM intelligence_style_preferences WHERE ctid IN (SELECT ctid"
							+ " FROM intelligence_style_preferences WHERE account_id = :a LIMIT :n)",
					"SELECT count(*) FROM intelligence_style_preferences WHERE account_id = :a"),
			kind("provider_preference",
					"DELETE FROM ai_provider_preference WHERE ctid IN (SELECT ctid FROM ai_provider_preference"
							+ " WHERE account_id = :a LIMIT :n)",
					"SELECT count(*) FROM ai_provider_preference WHERE account_id = :a"),
			// 只删个人 BYOK；组织 BYOK 不随创建者注销删除（ADR-D17）。
			kind("provider_key",
					"DELETE FROM ai_provider_key WHERE ctid IN (SELECT ctid FROM ai_provider_key"
							+ " WHERE owner_account_id = :a AND organization_id IS NULL LIMIT :n)",
					"SELECT count(*) FROM ai_provider_key WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL"),
			// 计费事实脱敏：金额/模型/用量/经济键保留，只清个人错误文本。
			kind("ai_run_mask",
					"UPDATE ai_run SET failure_reason = NULL WHERE ctid IN (SELECT ctid FROM ai_run"
							+ " WHERE account_id = :a AND failure_reason IS NOT NULL LIMIT :n)",
					"SELECT count(*) FROM ai_run WHERE account_id = :a AND failure_reason IS NOT NULL"),
			kind("video_job_mask",
					"UPDATE video_generation_job SET input_payload = '{}'::jsonb, result_url = NULL,"
							+ " error_message = NULL WHERE ctid IN (SELECT ctid FROM video_generation_job"
							+ " WHERE account_id = :a AND organization_id IS NULL AND (input_payload <> '{}'::jsonb"
							+ " OR result_url IS NOT NULL OR error_message IS NOT NULL) LIMIT :n)",
					"SELECT count(*) FROM video_generation_job WHERE account_id = :a"
							+ " AND organization_id IS NULL AND (input_payload <> '{}'::jsonb"
							+ " OR result_url IS NOT NULL OR error_message IS NOT NULL)"),
			// 媒体只标记 deleting（行保留供 GC/对象删除）；物理对象与配额释放归 C103-10。
			kind("media_mark_deleting",
					"UPDATE media_reference SET status = 'deleting', updated_at = now() WHERE ctid IN (SELECT ctid"
							+ " FROM media_reference WHERE owner_account_id = :a AND organization_id IS NULL"
							+ " AND status NOT IN ('deleting', 'deleted') LIMIT :n)",
					"SELECT count(*) FROM media_reference WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL AND status NOT IN ('deleting', 'deleted')"));

	static final int DEFAULT_BATCH_SIZE = 200;
	static final int MAX_BATCH_SIZE = 1000;

	public record Manifest(UUID id, UUID closureRequestId, String accountId, String state, Instant verifiedAt) {
	}

	public record Step(String manifestId, String resourceKind, String state, long deletedCount, int attempts) {
	}

	private final DatabaseClient db;

	public PersonalDataErasureRepository(DatabaseClient db) {
		this.db = db;
	}

	// ---------- manifest ----------

	public Mono<Manifest> findManifestByRequest(UUID closureRequestId) {
		return db
				.sql("SELECT id, closure_request_id, account_id, state, verified_at"
						+ " FROM personal_data_erasure_manifest WHERE closure_request_id = :r")
				.bind("r", closureRequestId).map(PersonalDataErasureRepository::mapManifest).one();
	}

	public Mono<Manifest> findManifestById(UUID id) {
		return db
				.sql("SELECT id, closure_request_id, account_id, state, verified_at"
						+ " FROM personal_data_erasure_manifest WHERE id = :id")
				.bind("id", id).map(PersonalDataErasureRepository::mapManifest).one();
	}

	public Mono<Manifest> insertManifest(UUID id, UUID closureRequestId, String accountId) {
		return db.sql("""
				INSERT INTO personal_data_erasure_manifest(id, closure_request_id, account_id)
				VALUES (:id, :req, :a)
				ON CONFLICT (closure_request_id) DO NOTHING
				RETURNING id, closure_request_id, account_id, state, verified_at
				""").bind("id", id).bind("req", closureRequestId).bind("a", accountId)
				.map(PersonalDataErasureRepository::mapManifest).one();
	}

	public Mono<Long> setManifestState(UUID id, String state) {
		return db.sql("""
				UPDATE personal_data_erasure_manifest
				   SET state = :state,
				       verified_at = CASE WHEN :state = 'completed' THEN now() ELSE verified_at END,
				       counts = CASE WHEN :state = 'completed' THEN
				           (SELECT jsonb_object_agg(resource_kind, deleted_count) FROM personal_data_erasure_step
				            WHERE manifest_id = :id) ELSE counts END
				 WHERE id = :id
				""").bind("state", state).bind("id", id).fetch().rowsUpdated();
	}

	/**
	 * worker 待处理清单（planned/db_cleaning）。跳过仍有活动步骤租约的 manifest： 端点同步 drain
	 * 在飞时由端点负责；端点中断后租约到期（默认 60s），worker 才接管续跑。
	 */
	public Flux<Manifest> findActiveManifests(int limit) {
		return db
				.sql("SELECT id, closure_request_id, account_id, state, verified_at"
						+ " FROM personal_data_erasure_manifest m WHERE state IN ('planned', 'db_cleaning')"
						+ " AND NOT EXISTS (SELECT 1 FROM personal_data_erasure_step s WHERE s.manifest_id = m.id"
						+ " AND s.claimed_until > now()) ORDER BY created_at LIMIT :n")
				.bind("n", Math.max(1, limit)).map(PersonalDataErasureRepository::mapManifest).all();
	}

	// ---------- 步骤 ----------

	/** 建全部 kind 步骤行（幂等；批次默认 200、上限 1000 由调用方钳制）。 */
	public Mono<Void> insertSteps(UUID manifestId, int batchSize) {
		int size = Math.max(1, Math.min(batchSize, MAX_BATCH_SIZE));
		StringBuilder sql = new StringBuilder(
				"INSERT INTO personal_data_erasure_step" + "(manifest_id, resource_kind, batch_size) VALUES ");
		List<Object> args = new ArrayList<>();
		for (int i = 0; i < KINDS.size(); i++) {
			sql.append(i == 0 ? "(:m0, :k0, :b0)" : ", (:m" + i + ", :k" + i + ", :b" + i + ")");
		}
		sql.append(" ON CONFLICT (manifest_id, resource_kind) DO NOTHING");
		var spec = db.sql(sql.toString());
		for (int i = 0; i < KINDS.size(); i++) {
			spec = spec.bind("m" + i, manifestId).bind("k" + i, KINDS.get(i).kind()).bind("b" + i, size);
		}
		return spec.then();
	}

	public Flux<Step> findSteps(UUID manifestId) {
		return db
				.sql("SELECT manifest_id::text, resource_kind, state, deleted_count, attempts"
						+ " FROM personal_data_erasure_step WHERE manifest_id = :m")
				.bind("m", manifestId)
				.map((r) -> new Step(r.get("manifest_id", String.class), r.get("resource_kind", String.class),
						r.get("state", String.class), nullSafe(r.get("deleted_count", Long.class)),
						nullSafe(r.get("attempts", Integer.class))))
				.all();
	}

	/** 领取步骤（租约内独占；重试到点的 retry_wait 可再领；超过最大尝试 → needs_review）。 */
	public Mono<Step> claimStep(UUID manifestId, String resourceKind, UUID claimToken, Duration lease,
			int maxAttempts) {
		return db.sql("""
				UPDATE personal_data_erasure_step
				   SET state = CASE WHEN attempts + 1 >= :maxAttempts AND state <> 'running' THEN 'needs_review'
				                    ELSE 'running' END,
				       claim_token = :t, claimed_until = now() + (:leaseSeconds * interval '1 second'),
				       attempts = attempts + 1, updated_at = now()
				 WHERE manifest_id = :m AND resource_kind = :k
				   AND state IN ('pending', 'running', 'retry_wait')
				   AND (claim_token IS NULL OR claimed_until IS NULL OR claimed_until < now())
				RETURNING manifest_id::text, resource_kind, state, deleted_count, attempts
				""").bind("m", manifestId).bind("k", resourceKind).bind("t", claimToken)
				.bind("leaseSeconds", lease.toSeconds()).bind("maxAttempts", Math.max(1, maxAttempts))
				.map((r) -> new Step(r.get("manifest_id", String.class), r.get("resource_kind", String.class),
						r.get("state", String.class), nullSafe(r.get("deleted_count", Long.class)),
						nullSafe(r.get("attempts", Integer.class))))
				.one();
	}

	/** 执行一个批次（有界 DELETE/UPDATE）。 */
	public Mono<Long> runBatch(String resourceKind, String accountId, int limit) {
		EraseKind erase = kindOf(resourceKind);
		return db.sql(erase.batchSql()).bind("a", accountId).bind("n", Math.max(1, limit)).fetch().rowsUpdated();
	}

	/** 同事务推进步骤（claim 归还 + 计数累计 + 完成态；租约被接管时 0 行——批次幂等可安全重跑）。 */
	public Mono<Long> advanceStep(UUID manifestId, String resourceKind, UUID claimToken, long affected, boolean done) {
		return db.sql("""
				UPDATE personal_data_erasure_step
				   SET deleted_count = deleted_count + :affected,
				       state = CASE WHEN :done THEN 'succeeded' ELSE 'running' END,
				       claim_token = NULL, claimed_until = NULL, updated_at = now()
				 WHERE manifest_id = :m AND resource_kind = :k AND claim_token = :t
				""").bind("affected", affected).bind("done", done).bind("m", manifestId).bind("k", resourceKind)
				.bind("t", claimToken).fetch().rowsUpdated();
	}

	public Mono<Void> failStep(UUID manifestId, String resourceKind, UUID claimToken, String errorCode,
			Duration backoff) {
		return db.sql("""
				UPDATE personal_data_erasure_step
				   SET state = 'retry_wait', last_error_code = :err,
				       next_retry_at = now() + (:backoffSeconds * interval '1 second'),
				       claim_token = NULL, claimed_until = NULL, updated_at = now()
				 WHERE manifest_id = :m AND resource_kind = :k AND claim_token = :t
				""").bind("err", truncate(errorCode)).bind("backoffSeconds", Math.max(1, backoff.toSeconds()))
				.bind("m", manifestId).bind("k", resourceKind).bind("t", claimToken).then();
	}

	public Mono<Long> countFailedSteps(UUID manifestId) {
		return db
				.sql("SELECT count(*)::bigint AS c FROM personal_data_erasure_step WHERE manifest_id = :m"
						+ " AND state IN ('retry_wait', 'needs_review')")
				.bind("m", manifestId).map((r) -> nullSafe(r.get("c", Long.class))).one().defaultIfEmpty(0L);
	}

	// ---------- 对象登记 ----------

	/** 先保存对象 key 再删父行（§7.4）：媒体对象/上传暂存/公众号派生对象全部入册（幂等）。 */
	public Mono<Long> registerObjects(UUID manifestId, String accountId) {
		return db.sql("""
				INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind)
				SELECT :m, md5(object_key), object_key, 'media_object' FROM media_reference
				 WHERE owner_account_id = :a AND organization_id IS NULL AND status <> 'deleted'
				ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
				""").bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated().then(db.sql("""
				INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind)
				SELECT :m, md5(upload_key), upload_key, 'upload_staging' FROM media_reference
				 WHERE owner_account_id = :a AND organization_id IS NULL
				   AND upload_key IS NOT NULL AND status <> 'deleted'
				ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
				""").bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated()).then(db.sql("""
				INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind)
				SELECT :m, md5(derived_object_key), derived_object_key, 'wechat_derived'
				  FROM creation_wechat_media_mapping WHERE owner_account_id = :a
				   AND derived_object_key IS NOT NULL
				ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
				""").bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated());
	}

	public Mono<Long> countObjects(UUID manifestId, String... states) {
		String list = String.join(",", java.util.Arrays.stream(states).map((s) -> "'" + s + "'").toList());
		return db
				.sql("SELECT count(*)::bigint AS c FROM personal_data_erasure_object WHERE manifest_id = :m"
						+ " AND state IN (" + list + ")")
				.bind("m", manifestId).map((r) -> nullSafe(r.get("c", Long.class))).one().defaultIfEmpty(0L);
	}

	// ---------- 残留核对 ----------

	/** verify 用：逐 kind 残留计数（返回 kind→残留行数；仅统计，不写）。 */
	public Mono<Map<String, Long>> residueByKind(String accountId) {
		Map<String, Long> residue = new LinkedHashMap<>();
		return Flux.fromIterable(KINDS)
				.concatMap((erase) -> db.sql(erase.residueSql()).bind("a", accountId)
						.map((r) -> nullSafe(r.get("count", Long.class))).one().defaultIfEmpty(0L)
						.doOnNext((count) -> residue.put(erase.kind(), count)))
				.then(Mono.just(residue));
	}

	static EraseKind kindOf(String resourceKind) {
		return KINDS.stream().filter((k) -> k.kind().equals(resourceKind)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown erasure kind: " + resourceKind));
	}

	private static EraseKind kind(String name, String batchSql, String residueSql) {
		return new EraseKind(name, batchSql, residueSql);
	}

	private static Manifest mapManifest(io.r2dbc.spi.Readable r) {
		return new Manifest(r.get("id", UUID.class), r.get("closure_request_id", UUID.class),
				r.get("account_id", String.class), r.get("state", String.class),
				toInstant(r.get("verified_at", OffsetDateTime.class)));
	}

	private static long nullSafe(Long value) {
		return value == null ? 0L : value;
	}

	private static int nullSafe(Integer value) {
		return value == null ? 0 : value;
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static String truncate(String value) {
		return value != null && value.length() > 64 ? value.substring(0, 64) : value;
	}
}
