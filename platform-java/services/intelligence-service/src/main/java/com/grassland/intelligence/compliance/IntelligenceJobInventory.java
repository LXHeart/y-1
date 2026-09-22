package com.grassland.intelligence.compliance;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 活动任务清单（任务书 #103 C103-08 / §7.3 / BR-15）：按 kind 统计「不能只数 running」的活动事实。
 *
 * <p>
 * 活动判定覆盖：排队/待派发/已提交/处理中、未知供应商结果（unknown 提交/回包）、 dispatch/settlement
 * 未完成、费用未结（补偿/结算 pending 或 failed 未核销债务）、 持有未核实 run 的计划。ready/applied/clarify
 * 等静态内容不阻塞。只读——check 不自动取消任务。
 */
@Component
public class IntelligenceJobInventory {

	/** 与查询列序一致的 kind 名（按位取值，避免依赖驱动元数据接口差异）。 */
	private static final String[] KNOWN_KINDS = {"ai_run", "video_generation_job", "speech_transcription",
			"ai_credit_compensation", "ai_credit_usage_settlement", "card_series_operation", "creation_visual_item",
			"creation_text_proposal", "creation_visual_plan", "creation_canvas_agent_plan", "video_production_task",
			"video_shot_take", "video_shot_audio", "creation_export", "creation_wechat_draft_sync",
			"creation_wechat_media_mapping", "content_asset_embedding",
			// 任务书 #105B C105B-05：dh 活动（BR-15 同口径——非终态/清理未收口/unknown 不当空闲）。
			"dh_session", "dh_operation", "dh_invocation"};

	private final DatabaseClient db;

	public IntelligenceJobInventory(DatabaseClient db) {
		this.db = db;
	}

	public Mono<Map<String, Long>> countByKind(String accountId) {
		return db.sql("""
				SELECT
				  (SELECT COUNT(*) FROM ai_run WHERE account_id = :a AND status = 'running')::bigint,
				  (SELECT COUNT(*) FROM video_generation_job WHERE account_id = :a
				     AND status IN ('preparing','queued','submitted','processing','unknown'))::bigint,
				  (SELECT COUNT(*) FROM speech_transcription WHERE owner_account_id = :a
				     AND status = 'processing')::bigint,
				  (SELECT COUNT(*) FROM ai_credit_compensation WHERE account_id = :a
				     AND status IN ('pending','failed'))::bigint,
				  (SELECT COUNT(*) FROM ai_credit_usage_settlement WHERE account_id = :a
				     AND status IN ('pending','failed'))::bigint,
				  (SELECT COUNT(*) FROM card_series_operation o WHERE o.owner_account_id = :a
				     AND (o.status NOT IN ('succeeded','failed','cancelled')
				          OR COALESCE(o.dispatch_state,'') NOT IN ('','completed')
				          OR COALESCE(o.settlement_state,'') NOT IN ('','completed','not_required')))::bigint,
				  (SELECT COUNT(*) FROM creation_visual_item i
				     JOIN card_series_operation o ON o.id = i.operation_id
				     WHERE o.owner_account_id = :a
				       AND i.state NOT IN ('succeeded','failed','cancelled','not_required'))::bigint,
				  (SELECT COUNT(*) FROM creation_text_proposal WHERE owner_account_id = :a
				     AND status IN ('preparing','unknown'))::bigint,
				  (SELECT COUNT(*) FROM creation_visual_plan WHERE owner_account_id = :a
				     AND status IN ('preparing','unknown'))::bigint,
				  (SELECT COUNT(*) FROM creation_canvas_agent_plan WHERE account_id = :a
				     AND status IN ('preparing','running'))::bigint,
				  (SELECT COUNT(*) FROM video_production_task WHERE account_id = :a
				     AND phase NOT IN ('succeeded','failed','cancelled'))::bigint,
				  (SELECT COUNT(*) FROM video_shot_take t JOIN video_shot sh ON sh.id = t.shot_id
				     JOIN video_storyboard s ON s.id = sh.storyboard_id
				     WHERE s.account_id = :a
				       AND t.status IN ('queued','submitted','processing'))::bigint,
				  (SELECT COUNT(*) FROM video_shot_audio au JOIN video_shot sh ON sh.id = au.shot_id
				     JOIN video_storyboard s ON s.id = sh.storyboard_id
				     WHERE s.account_id = :a
				       AND au.status IN ('queued','submitted','processing'))::bigint,
				  (SELECT COUNT(*) FROM creation_export WHERE owner_account_id = :a
				     AND state = 'building')::bigint,
				  (SELECT COUNT(*) FROM creation_wechat_draft_sync WHERE owner_account_id = :a
				     AND (state IN ('preparing','uploading','submitting','verifying','unknown')
				          OR dispatch_state <> 'completed'))::bigint,
				  (SELECT COUNT(*) FROM creation_wechat_media_mapping WHERE owner_account_id = :a
				     AND state IN ('pending','uploading','failed'))::bigint,
				  (SELECT COUNT(*) FROM content_asset_embedding e JOIN content_asset a ON a.id = e.asset_id
				     WHERE a.owner_account_id = :a AND e.status IN ('pending','processing'))::bigint,
				  (SELECT COUNT(*) FROM dh_session WHERE owner_account_id = :a
				     AND (state NOT IN ('ended','failed') OR cleanup_pending))::bigint,
				  (SELECT COUNT(*) FROM dh_operation WHERE owner_account_id = :a
				     AND state IN ('pending','running','unknown'))::bigint,
				  (SELECT COUNT(*) FROM dh_invocation WHERE owner_account_id = :a
				     AND (state IN ('reserved','preparing','prepared','dispatched','unknown')
				          OR settlement_state IN ('pending','failed')))::bigint
				""").bind("a", accountId).map((row, meta) -> {
			Map<String, Long> counts = new LinkedHashMap<>();
			for (int index = 0; index < KNOWN_KINDS.length; index++) {
				Long value = row.get(index, Long.class);
				if (value != null && value > 0) {
					counts.put(KNOWN_KINDS[index], value);
				}
			}
			return counts;
		}).one().defaultIfEmpty(Map.of());
	}
}
