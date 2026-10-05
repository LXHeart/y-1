package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * C107F3-10（任务书 107-fix-3 §6.4 MOD-002 / RULE-010）：可信再生成上下文。
 *
 * <p>
 * 「按最新分析重新生成」不能信浏览器自报，也不允许把全库相同 sha 的分析当权限证明—— resolve 只沿 <b>本人 project 的
 * hypit.agent 分析 job 引用链</b>取证据：最近（updated_at DESC/id DESC）一条 checkpoint 携带
 * referenceAnalysisId 的 analyze job → 该可信 job 引用的 reference.analyze 命令 result →
 * 复核 analysisId/mediaHash/status 与来源 asset（ready + sha 匹配）。任一环不成立按
 * {@code hypit_state_conflict}（409）前置拒绝，不建新 job、不调模型（§5.2/RULE-010）。
 *
 * <p>
 * W18 在命令首次受理事务内冻结 {@link ReferenceContext}（含结构化 analysis，非 markdown 摘要）进
 * checkpoint（≤256KiB 由调用方校验）；W19 将快照转为首项 planner 观察交 W20，作者不能用空观察执行
 * 新模式。错误文案携带稳定关键词（「没有可信的完整参考分析」「参考素材已变化」），供 §8 UI-03 映射。
 */
@Service
public class HypitAuthorContextService {

	/** §7.2：referenceContext 快照 version 恒 1。 */
	private static final int SNAPSHOT_VERSION = 1;

	private final DatabaseClient db;

	public HypitAuthorContextService(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * MOD-002 冻结签名（§6.4）：嵌套 records 落在本服务；analysis 为既有结构化类型。 toMap/fromMap 提供
	 * checkpoint JSON 的对称序列化（UUID 落字符串、秒为有限数）。
	 */
	public record ReferenceContext(int version, UUID analysisJobId, String analysisId, String mediaHash,
			List<UUID> assetIds, long baseRevision, HypitReferenceAnalysis analysis) {

		public Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("version", version);
			map.put("analysisJobId", analysisJobId.toString());
			map.put("analysisId", analysisId);
			map.put("mediaHash", mediaHash);
			map.put("assetIds", assetIds.stream().map(UUID::toString).toList());
			map.put("baseRevision", baseRevision);
			map.put("analysis", analysis);
			return map;
		}

		/** checkpoint JSON → 强类型快照（analysis 走 W21 fromMap 宽松重建）。 */
		public static ReferenceContext fromMap(Map<String, Object> map) {
			List<UUID> assets = new ArrayList<>();
			Object rawAssets = map.get("assetIds");
			if (rawAssets instanceof List<?> list) {
				for (Object item : list) {
					assets.add(UUID.fromString(String.valueOf(item)));
				}
			}
			Object rawAnalysis = map.get("analysis");
			return new ReferenceContext(
					map.get("version") instanceof Number number ? number.intValue() : SNAPSHOT_VERSION,
					UUID.fromString(String.valueOf(map.get("analysisJobId"))), String.valueOf(map.get("analysisId")),
					String.valueOf(map.get("mediaHash")), assets, HypitJson.longValue(map.get("baseRevision"), 0L),
					HypitReferenceAnalysisService.fromMap(
							rawAnalysis instanceof Map<?, ?> analysis ? HypitJson.mapValue(analysis) : Map.of()));
		}
	}

	/**
	 * 解析本人工程当前可信完整分析（RULE-010）：job 引用链 → 命令 result → asset 复核。 baseRevision
	 * 由调用方（W18）先行与 head 相等校验，此处原样冻结进快照。
	 */
	public Mono<ReferenceContext> resolve(String accountId, UUID projectId, long baseRevision) {
		return latestAnalysisJob(accountId, projectId).flatMap(job -> contextOf(job, projectId, baseRevision));
	}

	/** 最近携带 referenceAnalysisId 的本人 analyze job（updated_at DESC/id DESC）。 */
	private Mono<JobReference> latestAnalysisJob(String accountId, UUID projectId) {
		return db.sql("""
				SELECT id::text AS id, checkpoint_json::text AS checkpoint
				FROM hypit_job
				WHERE account_id = :account AND project_id = CAST(:project AS uuid)
				  AND kind = 'hypit.agent' AND checkpoint_json->>'intent' = 'analyze'
				  AND checkpoint_json->>'referenceAnalysisId' IS NOT NULL
				ORDER BY updated_at DESC, id DESC LIMIT 1
				""").bind("account", accountId).bind("project", projectId.toString())
				.map((row, meta) -> new JobReference(UUID.fromString(row.get("id", String.class)),
						HypitJson.read(row.get("checkpoint", String.class))))
				.one().switchIfEmpty(Mono.error(noTrustedAnalysis()));
	}

	private record JobReference(UUID jobId, Map<String, Object> checkpoint) {
	}

	/**
	 * 引用链复核：命令 result 的 analysisId/status/mediaHash 与 job 引用一致 + 来源 asset ready。
	 */
	private Mono<ReferenceContext> contextOf(JobReference job, UUID projectId, long baseRevision) {
		String analysisId = String.valueOf(job.checkpoint().get("referenceAnalysisId"));
		return db.sql("""
				SELECT result_json::text AS result FROM hypit_command
				WHERE action = 'reference.analyze' AND result_json->>'analysisId' = :analysisId
				ORDER BY created_at DESC LIMIT 1
				""").bind("analysisId", analysisId).map((row, meta) -> row.get("result", String.class)).one()
				.switchIfEmpty(Mono.error(noTrustedAnalysis())).map(result -> HypitJson.read(result))
				.flatMap(result -> {
					HypitReferenceAnalysis analysis = HypitReferenceAnalysisService.fromMap(result);
					if (analysis.status() != HypitReferenceAnalysis.Status.SUCCEEDED) {
						return Mono.<HypitReferenceAnalysis>error(
								new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
										"最近参考分析未 SUCCEEDED（当前 " + analysis.status() + "），没有可信的完整参考分析，请先完成参考视频分析"));
					}
					return Mono.just(analysis);
				})
				.flatMap(analysis -> sourceAssetOf(projectId, analysis.mediaHash())
						.map(assetId -> new ReferenceContext(SNAPSHOT_VERSION, job.jobId(), analysis.analysisId(),
								analysis.mediaHash(), List.of(assetId), baseRevision, analysis)));
	}

	/** 来源素材复核（RULE-010）：本工程内 sha 匹配且 ready；否则按来源变化拒绝。 */
	private Mono<UUID> sourceAssetOf(UUID projectId, String mediaHash) {
		return db.sql("""
				SELECT id::text AS id FROM hypit_asset
				WHERE project_id = CAST(:project AS uuid) AND sha256 = :sha AND status = 'ready'
				ORDER BY created_at DESC LIMIT 1
				""").bind("project", projectId.toString()).bind("sha", mediaHash)
				.map((row, meta) -> UUID.fromString(row.get("id", String.class))).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
						"参考素材已变化（sha 不匹配或非 ready），请重新分析")));
	}

	private static IntelligenceException noTrustedAnalysis() {
		return new IntelligenceException(HttpStatus.CONFLICT.value(), "hypit_state_conflict",
				"本工程没有可信的完整参考分析，请先完成参考视频分析");
	}
}
