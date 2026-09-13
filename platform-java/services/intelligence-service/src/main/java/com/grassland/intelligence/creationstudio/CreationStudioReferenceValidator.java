package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101：草稿 workspace.inputs.studio 引用的归属校验（不能只校验 UUID 形状）。
 *
 * <p>
 * C101-04 起开放 sourceDocumentId、recipe 与 lastProposalId；C101-05 起开放 visualPlan
 * （本人 + 当前草稿 + revision 不超当前指针；不信任客户端版本引用）；activeVisualJobId 在
 * 负责卡（10）实现前一律拒绝写入。studio 引用仅限文章创作（capability=article；缺省视为 article
 * 默认，与读侧回填一致——视频／朋友圈／点评草稿不得注入 studio）。
 */
@Component
public class CreationStudioReferenceValidator {

	private final SourceDocumentRepository sources;
	private final com.grassland.intelligence.creationstudio.text.TextProposalRepository proposals;
	private final com.grassland.intelligence.creationstudio.plan.VisualPlanRepository visualPlans;
	private final com.grassland.intelligence.cardseries.CardSeriesOperationRepository operations;

	public CreationStudioReferenceValidator(SourceDocumentRepository sources,
			com.grassland.intelligence.creationstudio.text.TextProposalRepository proposals,
			com.grassland.intelligence.creationstudio.plan.VisualPlanRepository visualPlans,
			com.grassland.intelligence.cardseries.CardSeriesOperationRepository operations) {
		this.sources = sources;
		this.proposals = proposals;
		this.visualPlans = visualPlans;
		this.operations = operations;
	}

	public Mono<Void> validateWorkspace(Map<String, Object> workspace, Caller caller, UUID draftId) {
		Object inputsRaw = workspace == null ? null : workspace.get("inputs");
		if (!(inputsRaw instanceof Map<?, ?> inputs)) {
			return Mono.empty();
		}
		Object studioRaw = inputs.get("studio");
		if (studioRaw == null) {
			return Mono.empty();
		}
		if (!(studioRaw instanceof Map<?, ?> studio)) {
			throw invalid("workspace.inputs.studio 必须是对象");
		}
		Object capability = workspace.get("capability");
		if (capability != null && !"article".equals(capability)) {
			throw invalid("仅文章创作支持 studio 引用");
		}
		validateRecipe(studio.get("recipe"));
		rejectFutureRefs(studio);
		Object jobIdRaw = studio.get("activeVisualJobId");
		Object sourceId = studio.get("sourceDocumentId");
		Object proposalId = studio.get("lastProposalId");
		Object visualPlan = studio.get("visualPlan");
		Mono<Void> jobCheck = !(jobIdRaw instanceof String jobIdText) || jobIdText.isBlank()
				? Mono.empty()
				: checkVisualJob(jobIdText, caller, draftId);
		Mono<Void> sourceCheck = !(sourceId instanceof String sourceText) || sourceText.isBlank()
				? Mono.empty()
				: checkSource(sourceText, caller, draftId);
		Mono<Void> proposalCheck = !(proposalId instanceof String proposalText) || proposalText.isBlank()
				? Mono.empty()
				: checkProposal(proposalText, caller, draftId);
		Mono<Void> planCheck = visualPlan == null ? Mono.empty() : checkVisualPlan(visualPlan, caller, draftId);
		return jobCheck.then(sourceCheck).then(proposalCheck).then(planCheck);
	}

	/** C101-10：activeVisualJobId——本人 + 当前草稿的 v2 视觉任务（状态由读侧按 ID 读取）。 */
	private Mono<Void> checkVisualJob(String jobIdText, Caller caller, UUID draftId) {
		UUID jobId;
		try {
			jobId = UUID.fromString(jobIdText);
		} catch (Exception error) {
			throw invalid("workspace.inputs.studio.activeVisualJobId 必须是 UUID");
		}
		return operations.findVisualJob(jobId, caller.accountId()).switchIfEmpty(Mono.error(invalid("视觉任务不存在")))
				.flatMap(job -> draftId != null && draftId.toString().equals(job.draftId().toString())
						? Mono.empty()
						: Mono.error(invalid("视觉任务不属于当前草稿")))
				.then();
	}

	private Mono<Void> checkSource(String sourceText, Caller caller, UUID draftId) {
		UUID documentId;
		try {
			documentId = UUID.fromString(sourceText);
		} catch (IllegalArgumentException error) {
			throw invalid("workspace.inputs.studio.sourceDocumentId 必须是 UUID");
		}
		if (draftId == null) {
			throw invalid("来源引用必须绑定当前草稿");
		}
		return sources.findById(documentId).filter(document -> caller.accountId().equals(document.ownerAccountId()))
				.filter(document -> draftId.equals(document.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源引用不属于当前草稿"))).then();
	}

	/** C101-04：lastProposalId 归属校验（本人 + 当前草稿）。 */
	private Mono<Void> checkProposal(String proposalText, Caller caller, UUID draftId) {
		UUID proposalId;
		try {
			proposalId = UUID.fromString(proposalText);
		} catch (IllegalArgumentException error) {
			throw invalid("workspace.inputs.studio.lastProposalId 必须是 UUID");
		}
		if (draftId == null) {
			throw invalid("建议引用必须绑定当前草稿");
		}
		return proposals.findById(proposalId).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.filter(row -> draftId.equals(row.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "建议引用不属于当前草稿"))).then();
	}

	/** C101-05：visualPlan 引用归属校验（PlanRef：本人 + 当前草稿 + revision 不超当前指针）。 */
	private Mono<Void> checkVisualPlan(Object visualPlan, Caller caller, UUID draftId) {
		if (!(visualPlan instanceof Map<?, ?> planMap)) {
			throw invalid("workspace.inputs.studio.visualPlan 必须是对象");
		}
		Object id = planMap.get("id");
		Object revision = planMap.get("revision");
		if (!(id instanceof String idText)) {
			throw invalid("workspace.inputs.studio.visualPlan.id 必须是 UUID");
		}
		UUID planId;
		try {
			planId = UUID.fromString(idText);
		} catch (IllegalArgumentException error) {
			throw invalid("workspace.inputs.studio.visualPlan.id 必须是 UUID");
		}
		if (!(revision instanceof Integer revisionValue) || revisionValue < 1) {
			throw invalid("workspace.inputs.studio.visualPlan.revision 必须是正整数");
		}
		if (draftId == null) {
			throw invalid("计划引用必须绑定当前草稿");
		}
		return visualPlans.findById(planId).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.filter(row -> draftId.equals(row.draftId())).filter(row -> revisionValue <= row.currentRevision())
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划引用不属于当前草稿"))).then();
	}

	/** recipe 是静态目录引用（C101-01 契约），不查库；已知 id + 精确 version 才可写。 */
	private static void validateRecipe(Object recipe) {
		if (recipe == null) {
			return;
		}
		if (!(recipe instanceof Map<?, ?> recipeMap)) {
			throw invalid("workspace.inputs.studio.recipe 必须是对象");
		}
		Object id = recipeMap.get("id");
		Object version = recipeMap.get("version");
		if (!(id instanceof String idText) || CreationRecipeCatalog.byId(idText) == null) {
			throw invalid("workspace.inputs.studio.recipe.id 未知");
		}
		if (!(version instanceof String versionText)
				|| !versionText.equals(CreationRecipeCatalog.byId(idText).version())) {
			throw invalid("workspace.inputs.studio.recipe.version 不匹配");
		}
	}

	private static void rejectFutureRefs(Map<?, ?> studio) {
		// C101-10 起开放 activeVisualJobId（归属在 checkVisualJob 校验）；无前置拒绝项。
	}

	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "STUDIO_INVALID_INPUT", message);
	}
}
