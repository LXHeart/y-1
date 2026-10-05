package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.agent.HypitClonePlan.PlanStep;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.job.HypitCommandRepository;
import com.grassland.intelligence.hypit.project.HypitJson;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 克隆方案服务（任务书 #107-2 C107-16）：分析 → 方案 → 显式确认后走既有
 * build.submit（绝不因方案自动重发收费请求）。方案持久进 C04 幂等命令 （action=clone.plan），材料缺口如实
 * waiting_input；生成范围以 analysis 的 systemId 锚点为准，证据可回溯。
 */
@Service
public class HypitClonePlanService {

	private final HypitCommandRepository commands;
	private final HypitBuildService builds;

	public HypitClonePlanService(HypitCommandRepository commands, HypitBuildService builds,
			org.springframework.r2dbc.core.DatabaseClient db) {
		this.commands = commands;
		this.builds = builds;
		this.db = db;
	}

	public Mono<HypitClonePlan> save(UUID projectId, UUID requestId, HypitReferenceAnalysis analysis,
			List<PlanStep> steps, List<HypitClonePlan.MaterialGap> gaps) {
		HypitClonePlan.assertAnalysisUsable(analysis);
		HypitClonePlan plan = HypitClonePlan.derive("ra-" + analysis.analysisId().replace("ra-", ""),
				analysis.mediaHash(), analysis, steps, gaps);
		List<String> unbound = HypitClonePlan.unboundSteps(plan, analysis);
		if (!unbound.isEmpty()) {
			return Mono.error(new IntelligenceException(422, "hypit_clone_plan_unbound",
					"步骤锚点不在分析系统内：" + String.join(",", unbound)));
		}
		return persist(projectId, requestId, plan).thenReturn(plan);
	}

	/**
	 * C107F2-37（缺陷 N 接线）：分析成功后由服务端按证据确定性派生方案——每个持续系统 一步（锚点=firstSeenSeconds），分析
	 * gaps 原样转为材料缺口（有缺口即 WAITING_INPUT， 如实呈现）。此前 save() 只有 HTTP PUT
	 * 入口而前端从不调用、generate() 无调用方， clone_plan 行在任何活链路都不产生，方案面板 GET 恒 404（真实浏览器贯通实录）。
	 * 步骤锚点全部来自分析系统集，unboundSteps 校验恒通过。C107F3-10 起为旧重载（随机 requestId、无来源元数据）。
	 */
	public Mono<HypitClonePlan> deriveAndSave(UUID projectId, HypitReferenceAnalysis analysis) {
		return deriveSteps(analysis)
				.flatMap(steps -> save(projectId, UUID.randomUUID(), analysis, steps.steps(), steps.gaps()));
	}

	/**
	 * C107F3-10（§6.4 冻结签名 / RULE-012）：新模式作者写回成功后以<b>稳定 requestId</b> 持久 clone.plan
	 * ——W19 传
	 * {@code UUID.nameUUIDFromBytes((jobId+":clone-plan").getBytes(UTF_8))}，重试/崩溃恢复命中同一
	 * command 行幂等回放，不增重复行；result
	 * 增可选来源元数据（sourceAnalysisId/sourceMediaHash/sourceJobId/
	 * baseRevision/resultRevision，§7.1，均服务器生成）。派生步骤与旧重载同一证据语义。
	 */
	public Mono<HypitClonePlan> deriveAndSave(UUID projectId, UUID requestId,
			HypitAuthorContextService.ReferenceContext ctx, UUID sourceJobId, long resultRevision) {
		return Mono.defer(() -> deriveSteps(ctx.analysis()).flatMap(steps -> {
			HypitReferenceAnalysis analysis = ctx.analysis();
			HypitClonePlan.assertAnalysisUsable(analysis);
			HypitClonePlan plan = HypitClonePlan.derive("ra-" + analysis.analysisId().replace("ra-", ""),
					analysis.mediaHash(), analysis, steps.steps(), steps.gaps());
			List<String> unbound = HypitClonePlan.unboundSteps(plan, analysis);
			if (!unbound.isEmpty()) {
				return Mono.error(new IntelligenceException(422, "hypit_clone_plan_unbound",
						"步骤锚点不在分析系统内：" + String.join(",", unbound)));
			}
			return persist(projectId, requestId, plan, sourceMetadata(ctx, sourceJobId, resultRevision))
					.thenReturn(plan);
		}));
	}

	/** §7.1：新模式方案 result 的来源/revision 元数据（服务器生成，旧路径不携带）。 */
	private static Map<String, Object> sourceMetadata(HypitAuthorContextService.ReferenceContext ctx, UUID sourceJobId,
			long resultRevision) {
		Map<String, Object> source = new HashMap<>();
		source.put("sourceAnalysisId", ctx.analysisId());
		source.put("sourceMediaHash", ctx.mediaHash());
		source.put("sourceJobId", String.valueOf(sourceJobId));
		source.put("baseRevision", ctx.baseRevision());
		source.put("resultRevision", resultRevision);
		return source;
	}

	private record DerivedSteps(List<PlanStep> steps, List<HypitClonePlan.MaterialGap> gaps) {
	}

	/** 证据派生（两重载共享）：每个持续系统一步 + gaps 原样转材料缺口。 */
	private Mono<DerivedSteps> deriveSteps(HypitReferenceAnalysis analysis) {
		return Mono.defer(() -> {
			List<PlanStep> steps = new java.util.ArrayList<>();
			int index = 0;
			for (HypitReferenceAnalysis.System system : analysis.systems()) {
				String label = system.name() == null || system.name().isBlank()
						? "复刻持续系统 " + system.systemId()
						: "复刻：" + system.name();
				steps.add(new PlanStep(index++, "video.generate", system.systemId(), system.firstSeenSeconds(), label));
			}
			if (steps.isEmpty()) {
				steps.add(new PlanStep(0, "video.generate", null, 0d, "全片复刻（分析无持续系统，按整片处理）"));
			}
			List<HypitClonePlan.MaterialGap> gaps = analysis.gaps().stream()
					.map(gap -> new HypitClonePlan.MaterialGap("coverage",
							"区间无证据覆盖：" + gap.startSeconds() + "s-" + gap.endSeconds() + "s（" + gap.reason() + "）",
							null))
					.toList();
			return Mono.just(new DerivedSteps(steps, gaps));
		});
	}

	/** 显式确认执行：走 09 的持久 Build 提交（同 requestId 幂等），不新建收费通道。 */
	public Mono<HypitBuildService.SubmitView> execute(String accountId, UUID projectId, UUID requestId, UUID planId,
			UUID grantId, HypitClonePlan plan) {
		HypitClonePlan.assertExecutable(plan);
		return builds.submit(accountId, projectId, requestId, planId, grantId, "clone-plan " + plan.planId());
	}

	private Mono<Void> persist(UUID projectId, UUID requestId, HypitClonePlan plan) {
		return persist(projectId, requestId, plan, null);
	}

	/** source 非空时（新模式）result 增来源/revision 元数据；空保持旧 result 形状。 */
	private Mono<Void> persist(UUID projectId, UUID requestId, HypitClonePlan plan, Map<String, Object> source) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("projectId", projectId.toString());
		payload.put("planId", plan.planId());
		String payloadJson = HypitJson.write(payload);
		String payloadHash = com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge
				.sha256Hex(payloadJson);
		return commands.insert("system", "clone.plan", requestId, "clone-plan:" + projectId, payloadHash, payloadJson,
				projectId).flatMap(accepted -> {
					if (accepted.existing()) {
						return Mono.empty();
					}
					Map<String, Object> result = new HashMap<>();
					result.put("planId", plan.planId());
					result.put("status", plan.status().name());
					result.put("steps", plan.steps());
					result.put("materialGaps", plan.materialGaps());
					if (source != null) {
						result.putAll(source);
					}
					return commands.saveResult(accepted.row().id(), "succeeded", HypitJson.write(result)).then();
				}).then();
	}

	private final org.springframework.r2dbc.core.DatabaseClient db;

	/** GET 侧：工程最近的 clone.plan 结果（无则 404）。 */
	public Mono<Map<String, Object>> latestPlan(UUID projectId) {
		return db.sql("""
				SELECT result_json::text AS result FROM hypit_command
				WHERE action = 'clone.plan' AND project_id = CAST(:p AS uuid)
				  AND result_json IS NOT NULL
				ORDER BY created_at DESC LIMIT 1
				""").bind("p", projectId.toString()).map((row, meta) -> HypitJson.read(row.get("result", String.class)))
				.one().switchIfEmpty(Mono.error(new IntelligenceException(404, "hypit_not_found", "工程尚无克隆方案")));
	}
}
