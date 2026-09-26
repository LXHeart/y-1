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

	/** 显式确认执行：走 09 的持久 Build 提交（同 requestId 幂等），不新建收费通道。 */
	public Mono<HypitBuildService.SubmitView> execute(String accountId, UUID projectId, UUID requestId, UUID planId,
			UUID grantId, HypitClonePlan plan) {
		HypitClonePlan.assertExecutable(plan);
		return builds.submit(accountId, projectId, requestId, planId, grantId, "clone-plan " + plan.planId());
	}

	private Mono<Void> persist(UUID projectId, UUID requestId, HypitClonePlan plan) {
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
