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
 * 分阶段清理持久层（任务书 #103 C103-09 / §7.2 / §7.4；归属统一见 #104 C104-01）。
 *
 * <p>
 * 资源按 kind 登记为「批次 SQL（:a 账号、:n 批上限）+ 残留计数 SQL」成对定义，顺序即依赖序（子先父后）。 作用域一律经
 * {@link PersonalDataErasureScope} 的父链判定（#104 D01）：组织/他人父对象保留、本人个人与本人孤儿清理、
 * 多父链个人账号不一致属冲突 （不删除，由 {@link #conflictsByKind} 阻止 verify）。
 * 计费事实表（ai_run、video_generation_job）只脱敏个人载荷， 金额/模型/用量/经济键保留（D10）。
 */
@Component
public class PersonalDataErasureRepository {

	/** 清理资源 kind：批次语句与残留核对成对，顺序=依赖序。 */
	record EraseKind(String kind, String batchSql, String residueSql) {
	}

	/** §7.4 表族清单（子先父后；mask 类只清个人载荷；作用域片段见 {@link PersonalDataErasureScope}）。 */
	static final List<EraseKind> KINDS = List.of(
			kind("wechat_media_mapping",
					"DELETE FROM creation_wechat_media_mapping WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_media_mapping t WHERE t.owner_account_id = :a AND NOT "
							+ PersonalDataErasureScope.wechatSyncRetained("t.sync_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_media_mapping t WHERE t.owner_account_id = :a AND NOT "
							+ PersonalDataErasureScope.wechatSyncRetained("t.sync_id")),
			// sync 双父链（draft/export）：任一组织/他人链保留为组织内容证据（§7.1 wechat 族）。
			kind("wechat_draft_sync",
					"DELETE FROM creation_wechat_draft_sync WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_draft_sync t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.exportDraftParentPersonal("t.export_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_draft_sync t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.exportDraftParentPersonal("t.export_id")),
			kind("wechat_account",
					"DELETE FROM creation_wechat_account WHERE ctid IN (SELECT ctid"
							+ " FROM creation_wechat_account WHERE owner_account_id = :a LIMIT :n)",
					"SELECT count(*) FROM creation_wechat_account WHERE owner_account_id = :a"),
			// 双父链（draft/storyboard）：任一组织链保护（分镜被组织工作区引用同样保护）；个人链由 conflictsByKind 核对（§7.1）。
			kind("canvas_agent_plan",
					"DELETE FROM creation_canvas_agent_plan WHERE ctid IN (SELECT ctid"
							+ " FROM creation_canvas_agent_plan t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_canvas_agent_plan t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id")),
			// 组织父草稿的画布归组织规则；父已删的孤儿画布仍含本人私有内容 → 清理。
			kind("canvas_document",
					"DELETE FROM creation_canvas_document WHERE ctid IN (SELECT ctid"
							+ " FROM creation_canvas_document t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_canvas_document t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id")),
			kind("storyboard_workspace",
					"DELETE FROM video_storyboard_workspace WHERE ctid IN (SELECT ctid"
							+ " FROM video_storyboard_workspace t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.storyboardParentPersonal("t.storyboard_id") + " LIMIT :n)",
					"SELECT count(*) FROM video_storyboard_workspace t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.storyboardParentPersonal("t.storyboard_id")),
			// 谱系三链（自身/父/根）：检查所有存在根，任一组织/他人/组织引用即保留（§7.1）。
			kind("storyboard_variant", "DELETE FROM video_storyboard_variant WHERE ctid IN (SELECT ctid"
					+ " FROM video_storyboard_variant t WHERE t.account_id = :a AND "
					+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id") + " AND "
					+ PersonalDataErasureScope.storyboardDeletableForChildren("t.parent_storyboard_id") + " AND "
					+ PersonalDataErasureScope.storyboardDeletableForChildren("t.root_storyboard_id") + " LIMIT :n)",
					"SELECT count(*) FROM video_storyboard_variant t WHERE t.account_id = :a AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id") + " AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.parent_storyboard_id")
							+ " AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.root_storyboard_id")),
			kind("shot_take",
					"DELETE FROM video_shot_take WHERE ctid IN (SELECT ctid FROM video_shot_take"
							+ " WHERE shot_id IN (SELECT sh.id FROM video_shot sh JOIN video_storyboard s"
							+ " ON s.id = sh.storyboard_id WHERE s.account_id = :a AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ") LIMIT :n)",
					"SELECT count(*) FROM video_shot_take WHERE shot_id IN (SELECT sh.id FROM video_shot sh"
							+ " JOIN video_storyboard s ON s.id = sh.storyboard_id WHERE s.account_id = :a"
							+ " AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ")"),
			kind("shot_audio",
					"DELETE FROM video_shot_audio WHERE ctid IN (SELECT ctid FROM video_shot_audio"
							+ " WHERE shot_id IN (SELECT sh.id FROM video_shot sh JOIN video_storyboard s"
							+ " ON s.id = sh.storyboard_id WHERE s.account_id = :a AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ") LIMIT :n)",
					"SELECT count(*) FROM video_shot_audio WHERE shot_id IN (SELECT sh.id FROM video_shot sh"
							+ " JOIN video_storyboard s ON s.id = sh.storyboard_id WHERE s.account_id = :a"
							+ " AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ")"),
			kind("shot_media_source",
					"DELETE FROM video_shot_media_source WHERE ctid IN (SELECT ctid"
							+ " FROM video_shot_media_source WHERE storyboard_id IN (SELECT s.id"
							+ " FROM video_storyboard s WHERE s.account_id = :a AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ") LIMIT :n)",
					"SELECT count(*) FROM video_shot_media_source WHERE storyboard_id IN (SELECT s.id"
							+ " FROM video_storyboard s WHERE s.account_id = :a AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ")"),
			kind("video_production_task",
					"DELETE FROM video_production_task WHERE ctid IN (SELECT ctid FROM video_production_task t"
							+ " WHERE t.account_id = :a AND t.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id") + " LIMIT :n)",
					"SELECT count(*) FROM video_production_task t WHERE t.account_id = :a"
							+ " AND t.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardDeletableForChildren("t.storyboard_id")),
			kind("video_shot",
					"DELETE FROM video_shot WHERE ctid IN (SELECT ctid FROM video_shot WHERE storyboard_id IN"
							+ " (SELECT s.id FROM video_storyboard s WHERE s.account_id = :a"
							+ " AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ") LIMIT :n)",
					"SELECT count(*) FROM video_shot WHERE storyboard_id IN (SELECT s.id FROM video_storyboard s"
							+ " WHERE s.account_id = :a AND s.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("s.id") + ")"),
			// 分镜被组织工作区引用（个人分镜挂组织草稿）→ 保留，不破坏保留项目关联（§7.1）。
			kind("video_storyboard",
					"DELETE FROM video_storyboard WHERE ctid IN (SELECT ctid FROM video_storyboard t"
							+ " WHERE t.account_id = :a AND t.organization_id IS NULL AND "
							+ PersonalDataErasureScope.storyboardNotOrgReferenced("t.id") + " LIMIT :n)",
					"SELECT count(*) FROM video_storyboard t WHERE t.account_id = :a AND t.organization_id IS NULL"
							+ " AND " + PersonalDataErasureScope.storyboardNotOrgReferenced("t.id")),
			kind("visual_item",
					"DELETE FROM creation_visual_item WHERE ctid IN (SELECT ctid FROM creation_visual_item"
							+ " WHERE operation_id IN (SELECT op.id FROM card_series_operation op WHERE "
							+ PersonalDataErasureScope.cardOperationPersonal("op") + ") LIMIT :n)",
					"SELECT count(*) FROM creation_visual_item WHERE operation_id IN (SELECT op.id"
							+ " FROM card_series_operation op WHERE "
							+ PersonalDataErasureScope.cardOperationPersonal("op") + ")"),
			// v1 无 draft/plan 由 owner 证明可清；v2 双父链任一组织/他人即保留（§7.1）。
			kind("card_series_operation",
					"DELETE FROM card_series_operation WHERE ctid IN (SELECT ctid FROM card_series_operation t WHERE "
							+ PersonalDataErasureScope.cardOperationPersonal("t") + " LIMIT :n)",
					"SELECT count(*) FROM card_series_operation t WHERE "
							+ PersonalDataErasureScope.cardOperationPersonal("t")),
			kind("visual_artifact",
					"DELETE FROM creation_visual_artifact WHERE ctid IN (SELECT ctid FROM creation_visual_artifact t"
							+ " WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.planDraftParentPersonal("t.plan_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_visual_artifact t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " AND "
							+ PersonalDataErasureScope.planDraftParentPersonal("t.plan_id")),
			// quote 的 owner 不能替代父 scope，但 owner 本人 + plan 已删的孤儿仍清（D01 明确本人孤儿）。
			kind("visual_quote",
					"DELETE FROM creation_visual_quote WHERE ctid IN (SELECT ctid FROM creation_visual_quote t"
							+ " WHERE t.owner_account_id = :a AND NOT EXISTS (SELECT 1 FROM creation_visual_plan p"
							+ " WHERE p.id = t.plan_id AND NOT (" + PersonalDataErasureScope.visualPlanPersonal("p")
							+ ")) LIMIT :n)",
					"SELECT count(*) FROM creation_visual_quote t WHERE t.owner_account_id = :a"
							+ " AND NOT EXISTS (SELECT 1 FROM creation_visual_plan p WHERE p.id = t.plan_id AND NOT ("
							+ PersonalDataErasureScope.visualPlanPersonal("p") + "))"),
			// kind 全集见 Scope.STUDIO_APPLY_KINDS；未知 kind 不删，由 conflictsByKind 阻止 verify。
			kind("studio_apply",
					"DELETE FROM creation_studio_apply WHERE ctid IN (SELECT ctid FROM creation_studio_apply t WHERE "
							+ PersonalDataErasureScope.studioApplyPersonal("t") + " LIMIT :n)",
					"SELECT count(*) FROM creation_studio_apply t WHERE "
							+ PersonalDataErasureScope.studioApplyPersonal("t")),
			kind("visual_plan_revision",
					"DELETE FROM creation_visual_plan_revision WHERE ctid IN (SELECT ctid"
							+ " FROM creation_visual_plan_revision WHERE plan_id IN (SELECT p.id"
							+ " FROM creation_visual_plan p WHERE " + PersonalDataErasureScope.visualPlanPersonal("p")
							+ ") LIMIT :n)",
					"SELECT count(*) FROM creation_visual_plan_revision WHERE plan_id IN (SELECT p.id"
							+ " FROM creation_visual_plan p WHERE " + PersonalDataErasureScope.visualPlanPersonal("p")
							+ ")"),
			kind("visual_plan",
					"DELETE FROM creation_visual_plan WHERE ctid IN (SELECT ctid FROM creation_visual_plan t WHERE "
							+ PersonalDataErasureScope.visualPlanPersonal("t") + " LIMIT :n)",
					"SELECT count(*) FROM creation_visual_plan t WHERE "
							+ PersonalDataErasureScope.visualPlanPersonal("t")),
			kind("text_proposal",
					"DELETE FROM creation_text_proposal WHERE ctid IN (SELECT ctid FROM creation_text_proposal t"
							+ " WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_text_proposal t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id")),
			kind("source_document",
					"DELETE FROM creation_source_document WHERE ctid IN (SELECT ctid"
							+ " FROM creation_source_document t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_source_document t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id")),
			kind("creation_export",
					"DELETE FROM creation_export WHERE ctid IN (SELECT ctid FROM creation_export t"
							+ " WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id") + " LIMIT :n)",
					"SELECT count(*) FROM creation_export t WHERE t.owner_account_id = :a AND "
							+ PersonalDataErasureScope.draftParentPersonal("t.draft_id")),
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
			// 媒体只标记 deleting（行保留供 GC/对象删除）；物理对象与配额释放归 C103-10。 被组织 artifact/分镜来源/保留
			// sync 引用的个人媒体不标 deleting（#104 §7.1：保留引用先于物删，组织产物可读）。
			kind("media_mark_deleting",
					"UPDATE media_reference SET status = 'deleting', updated_at = now() WHERE ctid IN (SELECT ctid"
							+ " FROM media_reference WHERE owner_account_id = :a AND organization_id IS NULL"
							+ " AND status NOT IN ('deleting', 'deleted') AND NOT ("
							+ PersonalDataErasureScope.mediaOrgReferenceBlocked("media_reference.id") + ") LIMIT :n)",
					"SELECT count(*) FROM media_reference WHERE owner_account_id = :a"
							+ " AND organization_id IS NULL AND status NOT IN ('deleting', 'deleted') AND NOT ("
							+ PersonalDataErasureScope.mediaOrgReferenceBlocked("media_reference.id") + ")"));

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
						+ " FROM personal_data_erasure_manifest m"
						+ " WHERE state IN ('planned', 'db_cleaning', 'objects_pending')"
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
						+ " FROM personal_data_erasure_step WHERE manifest_id = :m ORDER BY " + stepOrder())
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
				   AND (next_retry_at IS NULL OR next_retry_at <= now())
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
				       claim_token = NULL, claimed_until = NULL, next_retry_at = NULL, updated_at = now()
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

	/**
	 * 先保存对象 key 再删父行（§7.4）：媒体/上传暂存/公众号派生/导出产物/公众号 token 缓存入册（幂等）。 登记选择与行 scope 同源
	 * （#104 §7.2/D01）：被组织 artifact/分镜来源/保留 sync 引用的个人媒体、组织 sync 的派生
	 * key、组织草稿导出不登记物删。
	 */
	public Mono<Long> registerObjects(UUID manifestId, String accountId) {
		return db
				.sql("""
						INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind, retention_reason)
						SELECT :m, md5(object_key), object_key, 'media_object', 'scope_verified' FROM media_reference
						 WHERE owner_account_id = :a AND organization_id IS NULL AND status <> 'deleted'
						 AND NOT (%s)
						ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
						"""
						.formatted(PersonalDataErasureScope.mediaOrgReferenceBlocked("media_reference.id")))
				.bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated().then(db
						.sql("""
								INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind, retention_reason)
								SELECT :m, md5(upload_key), upload_key, 'upload_staging', 'scope_verified' FROM media_reference
								 WHERE owner_account_id = :a AND organization_id IS NULL
								   AND upload_key IS NOT NULL AND status <> 'deleted'
								   AND NOT (%s)
								ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
								"""
								.formatted(PersonalDataErasureScope.mediaOrgReferenceBlocked("media_reference.id")))
						.bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated())
				.then(db.sql(
						"""
								INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind, retention_reason)
								SELECT :m, md5(derived_object_key), derived_object_key, 'wechat_derived', 'scope_verified'
								  FROM creation_wechat_media_mapping WHERE owner_account_id = :a
								   AND derived_object_key IS NOT NULL AND NOT %s
								ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
								"""
								.formatted(PersonalDataErasureScope
										.wechatSyncRetained("creation_wechat_media_mapping.sync_id")))
						.bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated())
				.then(db.sql(
						"""
								INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind, retention_reason)
								SELECT :m, md5(manifest_json->>'objectKey'), manifest_json->>'objectKey', 'export_artifact', 'scope_verified'
								  FROM creation_export e WHERE owner_account_id = :a
								   AND manifest_json->>'objectKey' IS NOT NULL AND %s
								ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
								"""
								.formatted(PersonalDataErasureScope.draftParentPersonal("e.draft_id")))
						.bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated())
				.then(db.sql(
						"""
								INSERT INTO personal_data_erasure_object(manifest_id, object_key_hash, object_key, kind, retention_reason)
								SELECT :m, md5(id::text || ':v' || version), id::text || ':v' || version, 'wechat_token_cache', 'scope_verified'
								  FROM creation_wechat_account WHERE owner_account_id = :a
								ON CONFLICT (manifest_id, object_key_hash) DO NOTHING
								""")
						.bind("m", manifestId).bind("a", accountId).fetch().rowsUpdated());
	}

	// ---------- 对象物删（C103-10；#104 C104-02 重验/护栏）----------

	/**
	 * 待物删对象条目。 {@code provenance} 为登记时写入的 {@code retention_reason=scope_verified}
	 * 标记—— 只有无该标记的条目才属于 旧（修复前）登记：父行缺失时不能证明个人归属，不得物删（#104 §7.2「不信任旧登记即等于可删」）。
	 */
	public record ErasureObject(String objectKeyHash, String objectKey, String kind, String provenance) {
	}

	/** 待物删对象（pending 优先于超限 failed；有界批量）。 */
	public Flux<ErasureObject> findPendingObjects(UUID manifestId, int limit) {
		return db.sql("""
				SELECT object_key_hash, object_key, kind, retention_reason FROM personal_data_erasure_object
				 WHERE manifest_id = :m AND state = 'pending'
				 ORDER BY kind, object_key_hash LIMIT :n
				""").bind("m", manifestId).bind("n", Math.max(1, limit))
				.map((r) -> new ErasureObject(r.get("object_key_hash", String.class), r.get("object_key", String.class),
						r.get("kind", String.class), r.get("retention_reason", String.class)))
				.all();
	}

	/** 物删完成：清空原 key 只留 hash（§7.2）。 */
	public Mono<Long> markObjectDeleted(UUID manifestId, String objectKeyHash) {
		return db.sql("""
				UPDATE personal_data_erasure_object SET state = 'deleted', object_key = NULL
				 WHERE manifest_id = :m AND object_key_hash = :h AND state IN ('pending', 'failed')
				""").bind("m", manifestId).bind("h", objectKeyHash).fetch().rowsUpdated();
	}

	/** 保留对象（租约/共享引用）：记 reason 供回执说明。 */
	public Mono<Long> markObjectRetained(UUID manifestId, String objectKeyHash, String reason) {
		return db.sql("""
				UPDATE personal_data_erasure_object SET state = 'retained', retention_reason = :reason
				 WHERE manifest_id = :m AND object_key_hash = :h AND state IN ('pending', 'failed')
				""").bind("reason", reason).bind("m", manifestId).bind("h", objectKeyHash).fetch().rowsUpdated();
	}

	/**
	 * 失败并保留字节（#104 §7.2 旧清理部分执行）：不释放配额、不物删，记有界脱敏诊断； manifest 因 failed>0 不得
	 * completed， 通用 GC 由注销引用护栏继续阻删。
	 */
	public Mono<Long> markObjectFailed(UUID manifestId, String objectKeyHash, String reason) {
		return db.sql("""
				UPDATE personal_data_erasure_object SET state = 'failed', retention_reason = :reason
				 WHERE manifest_id = :m AND object_key_hash = :h AND state IN ('pending', 'failed')
				""").bind("reason", reason).bind("m", manifestId).bind("h", objectKeyHash).fetch().rowsUpdated();
	}

	/** 物删失败：attempts+1；超限转 failed（不再自动重试），未超限保持 pending。 */
	public Mono<Long> bumpObjectAttempt(UUID manifestId, String objectKeyHash, int maxAttempts) {
		return db.sql("""
				UPDATE personal_data_erasure_object
				   SET attempts = attempts + 1,
				       state = CASE WHEN attempts + 1 >= :maxAttempts THEN 'failed' ELSE 'pending' END
				 WHERE manifest_id = :m AND object_key_hash = :h AND state IN ('pending', 'failed')
				""").bind("maxAttempts", Math.max(1, maxAttempts)).bind("m", manifestId).bind("h", objectKeyHash)
				.fetch().rowsUpdated();
	}

	/** 媒体行 id by object_key（配额释放/删除审计用；行已删返回 empty）。 */
	public Mono<UUID> mediaIdByObjectKey(String objectKey) {
		return db.sql("SELECT id FROM media_reference WHERE object_key = :k").bind("k", objectKey)
				.map((r) -> r.get("id", UUID.class)).one();
	}

	/** 媒体行 id by upload_key（暂存对象保留核对用；行已删返回 empty）。 */
	public Mono<UUID> mediaIdByUploadKey(String uploadKey) {
		return db.sql("SELECT id FROM media_reference WHERE upload_key = :k").bind("k", uploadKey)
				.map((r) -> r.get("id", UUID.class)).one();
	}

	/** 旧清理部分执行证据：行已 deleting 且配额已释放（新顺序为「先删字节后释放」，该组合只应来自旧流程）。 */
	public Mono<Boolean> mediaQuotaReleasedWhileDeleting(UUID mediaId) {
		return db
				.sql("SELECT (status = 'deleting' AND quota_released) AS partial FROM media_reference"
						+ " WHERE id = :id")
				.bind("id", mediaId).map((r) -> Boolean.TRUE.equals(r.get("partial", Boolean.class))).one()
				.defaultIfEmpty(false);
	}

	/**
	 * 派生 key 仍被保留 sync 的映射引用（旧 manifest 重验）。对象 drain 总在 DB 清理阶段之后——个人映射已删，
	 * 仍存在的映射行即组织/他人保留 sync 的证据，无需账号上下文。
	 */
	public Mono<Boolean> derivedKeyRetainedBySync(String derivedKey) {
		return db
				.sql("SELECT (count(*) > 0) AS retained FROM creation_wechat_media_mapping m"
						+ " WHERE m.derived_object_key = :k")
				.bind("k", derivedKey).map((r) -> Boolean.TRUE.equals(r.get("retained", Boolean.class))).one()
				.defaultIfEmpty(false);
	}

	/** 导出 key 仍被保留（组织/他人）导出行引用（旧 manifest 重验；个人导出行已在 DB 阶段删除）。 */
	public Mono<Boolean> exportKeyRetained(String exportKey) {
		return db
				.sql("SELECT (count(*) > 0) AS retained FROM creation_export e"
						+ " WHERE e.manifest_json->>'objectKey' = :k")
				.bind("k", exportKey).map((r) -> Boolean.TRUE.equals(r.get("retained", Boolean.class))).one()
				.defaultIfEmpty(false);
	}

	/**
	 * 媒体保留原因（优先级：KYB/证据租约 > 共享素材挂载 > 组织视觉引用 > 组织分镜来源 > 保留 sync 映射）。 对象 drain 总在 DB
	 * 清理阶段之后——仍存在的 artifact/分镜来源/映射引用即组织/他人范围（#104 §7.1 media 行）。 无保留原因返回 empty。
	 */
	public Mono<String> mediaRetentionReason(UUID mediaId) {
		return db.sql("""
				SELECT reason FROM (
				  SELECT 'kyb_evidence_lease' AS reason, 1 AS prio
				   WHERE EXISTS (SELECT 1 FROM media_kyb_retention r WHERE r.media_reference_id = :id
				                 AND r.released_at IS NULL
				                 AND (r.lease_until > now() OR r.retained_until > now()))
				  UNION ALL
				  SELECT 'shared_content_asset', 2
				   WHERE EXISTS (SELECT 1 FROM content_asset a WHERE a.media_reference_id = :id
				                 AND a.library_type <> 'personal')
				  UNION ALL
				  SELECT 'organization_visual_artifact', 3
				   WHERE EXISTS (SELECT 1 FROM creation_visual_artifact va
				                 WHERE va.original_media_id = :id OR va.delivery_media_id = :id)
				  UNION ALL
				  SELECT 'organization_shot_source', 4
				   WHERE EXISTS (SELECT 1 FROM video_shot_media_source ms WHERE ms.media_id = :id)
				  UNION ALL
				  SELECT 'organization_wechat_mapping', 5
				   WHERE EXISTS (SELECT 1 FROM creation_wechat_media_mapping m WHERE m.media_ref_id = :id)
				) reasons ORDER BY prio LIMIT 1
				""").bind("id", mediaId).map((r) -> r.get("reason", String.class)).one();
	}

	public Mono<Long> countObjects(UUID manifestId, String... states) {
		String list = String.join(",", java.util.Arrays.stream(states).map((s) -> "'" + s + "'").toList());
		return db
				.sql("SELECT count(*)::bigint AS c FROM personal_data_erasure_object WHERE manifest_id = :m"
						+ " AND state IN (" + list + ")")
				.bind("m", manifestId).map((r) -> nullSafe(r.get("c", Long.class))).one().defaultIfEmpty(0L);
	}

	// ---------- 残留与冲突核对 ----------

	/** verify 用：逐 kind 残留计数（返回 kind→残留行数；仅统计，不写）。 */
	public Mono<Map<String, Long>> residueByKind(String accountId) {
		Map<String, Long> residue = new LinkedHashMap<>();
		return Flux.fromIterable(KINDS)
				.concatMap((erase) -> db.sql(erase.residueSql()).bind("a", accountId)
						.map((r) -> nullSafe(r.get("count", Long.class))).one().defaultIfEmpty(0L)
						.doOnNext((count) -> residue.put(erase.kind(), count)))
				.then(Mono.just(residue));
	}

	/**
	 * 归属冲突核对（#104 D01）：多父链个人账号不一致或未知 studio_apply kind 的行不删除且必须阻止 verify。 返回
	 * kind→冲突行数 （仅统计有冲突的 kind，空 Map=无冲突）。
	 */
	public Mono<Map<String, Long>> conflictsByKind(String accountId) {
		record ConflictCheck(String kind, String sql) {
		}
		List<ConflictCheck> checks = List.of(new ConflictCheck("canvas_agent_plan",
				"SELECT count(*) FROM creation_canvas_agent_plan t WHERE t.account_id = :a AND "
						+ PersonalDataErasureScope.draftStoryboardChainConflict("t", "draft_id", "storyboard_id")),
				new ConflictCheck("storyboard_workspace",
						"SELECT count(*) FROM video_storyboard_workspace t WHERE t.account_id = :a AND "
								+ PersonalDataErasureScope.draftStoryboardChainConflict("t", "draft_id",
										"storyboard_id")),
				new ConflictCheck("storyboard_variant",
						"SELECT count(*) FROM video_storyboard_variant t WHERE t.account_id = :a AND "
								+ PersonalDataErasureScope.variantLineageConflict("t")),
				new ConflictCheck("card_series_operation",
						"SELECT count(*) FROM card_series_operation t WHERE t.owner_account_id = :a AND "
								+ PersonalDataErasureScope.dualDraftChainConflict("t", "draft_id", "plan_id")),
				new ConflictCheck("visual_artifact",
						"SELECT count(*) FROM creation_visual_artifact t WHERE t.owner_account_id = :a AND "
								+ PersonalDataErasureScope.dualDraftChainConflict("t", "draft_id", "plan_id")),
				new ConflictCheck("wechat_draft_sync",
						"SELECT count(*) FROM creation_wechat_draft_sync t WHERE t.owner_account_id = :a AND "
								+ PersonalDataErasureScope.draftExportChainConflict("t")),
				new ConflictCheck("studio_apply", "SELECT count(*) FROM creation_studio_apply t WHERE "
						+ PersonalDataErasureScope.studioApplyUnknownKind("t")));
		Map<String, Long> conflicts = new LinkedHashMap<>();
		return Flux.fromIterable(checks)
				.concatMap((check) -> db.sql(check.sql()).bind("a", accountId)
						.map((r) -> nullSafe(r.get("count", Long.class))).one().defaultIfEmpty(0L)
						.filter((count) -> count > 0).doOnNext((count) -> conflicts.put(check.kind(), count)))
				.then(Mono.just(conflicts));
	}

	private static String stepOrder() {
		StringBuilder order = new StringBuilder("CASE resource_kind");
		for (int i = 0; i < KINDS.size(); i++) {
			order.append(" WHEN '").append(KINDS.get(i).kind()).append("' THEN ").append(i);
		}
		return order.append(" ELSE ").append(KINDS.size()).append(" END").toString();
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
