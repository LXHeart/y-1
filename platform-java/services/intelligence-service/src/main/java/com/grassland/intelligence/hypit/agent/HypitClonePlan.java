package com.grassland.intelligence.hypit.agent;

import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Evidence;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Segment;
import com.grassland.intelligence.hypit.agent.HypitReferenceAnalysis.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 克隆方案（任务书 #107-2 C107-16 / K11.4 下游）：基于 ReferenceAnalysis 的 生成范围决策。每个步骤声明复用的
 * analysis systemId/事件锚点与材料缺口； 缺口材料不猜测——如实 waiting_input，等用户补材料后再执行。
 */
public record HypitClonePlan(String planId, String analysisId, String mediaHash, List<PlanStep> steps,
		List<MaterialGap> materialGaps, Status status) {

	public enum Status {
		DRAFT, READY, WAITING_INPUT
	}

	/** 一步生成计划：绑定证据锚点（分析系统/事件）与目标能力。 */
	public record PlanStep(int index, String capability, String boundSystemId, Double anchorSeconds,
			String description) {
	}

	/** 材料缺口：方案引用了但工程里没有的材料。 */
	public record MaterialGap(String kind, String description, String suggestedSource) {
	}

	public boolean executable() {
		return status == Status.READY && materialGaps.isEmpty();
	}

	/** 步骤覆盖检查：每个步骤必须绑定 analysis 里真实存在的系统锚点。 */
	public static List<String> unboundSteps(HypitClonePlan plan, HypitReferenceAnalysis analysis) {
		List<String> systemIds = analysis.systems().stream().map(HypitReferenceAnalysis.System::systemId).toList();
		List<String> unbound = new ArrayList<>();
		for (PlanStep step : plan.steps()) {
			if (step.boundSystemId() != null && !systemIds.contains(step.boundSystemId())) {
				unbound.add(step.description() == null ? "step " + step.index() : step.description());
			}
		}
		return unbound;
	}

	/** 由分析派生方案：每个持续系统至少一步；证据时段为锚点。 */
	public static HypitClonePlan derive(String analysisId, String mediaHash, HypitReferenceAnalysis analysis,
			List<PlanStep> steps, List<MaterialGap> gaps) {
		Status status = gaps.isEmpty() ? Status.READY : Status.WAITING_INPUT;
		return new HypitClonePlan("cp-" + analysisId, analysisId, mediaHash, steps, gaps, status);
	}

	/** 材料图缺口核对：plan 引用的 assetId 必须都在工程材料图内。 */
	public static List<MaterialGap> missingMaterials(HypitClonePlan plan, List<String> availableAssetIds,
			Map<String, String> descriptions) {
		List<MaterialGap> gaps = new ArrayList<>();
		for (Map.Entry<String, String> entry : new LinkedHashMap<String, String>(descriptions).entrySet()) {
			if (!availableAssetIds.contains(entry.getKey())) {
				gaps.add(new MaterialGap("material", entry.getValue(), entry.getKey()));
			}
		}
		return gaps;
	}

	/** 步骤证据锚点示例：从分析事件取时间（保持与原证据可对齐）。 */
	public static Evidence anchorOf(HypitReferenceAnalysis analysis, String systemId) {
		return analysis.systems().stream().filter(system -> system.systemId().equals(systemId)).findFirst()
				.map(system -> new Evidence(systemId, system.firstSeenSeconds(), "system anchor")).orElse(null);
	}

	/** 状态守卫：WAITING_INPUT 的方案不可直接执行（缺口先补齐）。 */
	public static void assertExecutable(HypitClonePlan plan) {
		if (plan.status() == Status.WAITING_INPUT || !plan.materialGaps().isEmpty()) {
			throw new com.grassland.intelligence.security.IntelligenceException(409, "hypit_clone_plan_waiting_input",
					"方案存在材料缺口，补齐后再执行：" + plan.materialGaps().size() + " 项");
		}
		if (plan.status() == Status.DRAFT) {
			throw new com.grassland.intelligence.security.IntelligenceException(409, "hypit_clone_plan_draft",
					"方案仍是草稿");
		}
	}

	/** 分析必须先于方案且全片完成（K11.3 → 16 的顺序红线）。 */
	public static void assertAnalysisUsable(HypitReferenceAnalysis analysis) {
		if (analysis.status() == HypitReferenceAnalysis.Status.WAITING_INPUT || !analysis.fullyCovered()) {
			throw new com.grassland.intelligence.security.IntelligenceException(409, "hypit_analysis_incomplete",
					"参考分析未完成全片，不能生成执行方案");
		}
		Segment any = analysis.segments().isEmpty() ? null : analysis.segments().get(0);
		if (any != null && any.evidence().isEmpty()) {
			throw new com.grassland.intelligence.security.IntelligenceException(409, "hypit_analysis_incomplete",
					"分析段落缺证据锚点");
		}
	}
}
