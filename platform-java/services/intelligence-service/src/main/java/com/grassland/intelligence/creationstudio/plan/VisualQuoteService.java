package com.grassland.intelligence.creationstudio.plan;

import com.grassland.intelligence.articleimage.ImageProtocolPolicy;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationassistant.CreationResultReferences;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.visual.VisualArtifactRepository;
import com.grassland.intelligence.creationstudio.visual.VisualItemRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class VisualQuoteService {
	static final Duration QUOTE_TTL = Duration.ofSeconds(120);
	private final VisualPlanRepository plans;
	private final VisualPlanService planService;
	private final CreationStudioProperties properties;
	private final CreationStudioContextService contexts;
	private final CreationDraftService drafts;
	private final VisualArtifactRepository artifacts;
	private final VisualItemRepository items;
	private final CreationResultReferences references;

	public VisualQuoteService(VisualPlanRepository plans, VisualPlanService planService,
			CreationStudioProperties properties, CreationStudioContextService contexts, CreationDraftService drafts,
			VisualArtifactRepository artifacts, VisualItemRepository items, CreationResultReferences references) {
		this.plans = plans;
		this.planService = planService;
		this.properties = properties;
		this.contexts = contexts;
		this.drafts = drafts;
		this.artifacts = artifacts;
		this.items = items;
		this.references = references;
	}
	public record EstimateCommand(UUID requestId, int expectedRevision, List<String> selectedItemIds,
			String consistencyMode, UUID anchorArtifactId) {
		String requestHash() {
			Map<String, Object> value = new TreeMap<>();
			value.put("expectedRevision", expectedRevision);
			value.put("selectedItemIds", selectedItemIds);
			value.put("consistencyMode", consistencyMode);
			value.put("anchorArtifactId", anchorArtifactId);
			return PlanJson.sha256(PlanJson.json(value));
		}
	}
	public record QuoteResult(VisualPlanRepository.QuoteRow quote) {
	}

	public Mono<QuoteResult> estimate(Caller caller, UUID planId, EstimateCommand command) {
		return planService.loadOwnedRow(planId, caller)
				.flatMap(owned -> drafts.withStudioDraftLock(owned.draftId().toString(), caller,
						draft -> plans.lockById(planId).flatMap(plan -> plans
								.findQuoteByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
								.flatMap(existing -> replay(existing, planId, command)).switchIfEmpty(Mono.defer(() -> {
									if (!properties.isWritesEnabled())
										return Mono.error(
												new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
									if (!"ready".equals(plan.status())
											|| plan.currentRevision() != command.expectedRevision()
											|| plan.confirmedRevision() == null
											|| plan.confirmedRevision() != plan.currentRevision()
											|| !plan.baseContentHash()
													.equals(CreationStudioContextService.computeBaseContentHash(draft)))
										return Mono.error(
												new IntelligenceException(409, "STUDIO_PLAN_STALE", "请先保存并确认当前计划"));
									return plans.findRevision(planId, plan.currentRevision())
											.flatMap(revision -> validateSelection(caller, plan,
													revision.documentJson(), command))
											.then(contexts.imageRoute(draft, caller.accountId(),
													caller.organizationId()))
											.flatMap(route -> quote(caller, plan, command, route));
								})))));
	}

	private Mono<QuoteResult> replay(VisualPlanRepository.QuoteRow row, UUID planId, EstimateCommand command) {
		if (!row.planId().equals(planId) || !row.requestHash().equals(command.requestHash()))
			return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一请求标识已用于不同估算"));
		return Mono.just(new QuoteResult(row));
	}

	@SuppressWarnings("unchecked")
	private Mono<Void> validateSelection(Caller caller, VisualPlan.PlanRow plan, String json, EstimateCommand command) {
		if (!Set.of("prompt-only", "reference-image").contains(command.consistencyMode())
				|| command.selectedItemIds() == null || command.selectedItemIds().isEmpty()
				|| new LinkedHashSet<>(command.selectedItemIds()).size() != command.selectedItemIds().size())
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "生成范围或一致性方式无效"));
		List<Map<String, Object>> documentItems = (List<Map<String, Object>>) PlanJson.readJson(json).get("items");
		Set<String> known = new LinkedHashSet<>();
		String cover = null;
		for (var item : documentItems) {
			known.add(String.valueOf(item.get("itemId")));
			if ("cover".equals(item.get("role")))
				cover = String.valueOf(item.get("itemId"));
		}
		if (!known.containsAll(command.selectedItemIds()))
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "所选项目不在计划内"));
		for (var item : documentItems) {
			if (!command.selectedItemIds().contains(item.get("itemId"))
					|| !(item.get("inputMediaRef") instanceof Map<?, ?>))
				continue;
			if (!"reference-image".equals(command.consistencyMode()))
				return Mono
						.error(new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED", "计划包含输入参考图，请选择图片参考模式"));
			if (!"cover".equals(item.get("role")) || command.anchorArtifactId() != null)
				return Mono.error(
						new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED", "一次只支持一张参考图，请在输入参考与封面参考之间选择"));
		}
		Mono<Void> inputChecks = reactor.core.publisher.Flux.fromIterable(documentItems)
				.filter(item -> command.selectedItemIds().contains(item.get("itemId"))
						&& item.get("inputMediaRef") instanceof Map<?, ?>)
				.concatMap(item -> references.resolveMedia((Map<?, ?>) item.get("inputMediaRef"), caller)).then();
		if ("reference-image".equals(command.consistencyMode()) && !command.selectedItemIds().contains(cover)
				&& command.anchorArtifactId() == null)
			return Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "请先选择本计划已完成的封面作为参考"));
		if (command.anchorArtifactId() == null)
			return inputChecks;
		final String coverId = cover;
		return inputChecks.then(artifacts.findByIdAndOwner(command.anchorArtifactId(), caller.accountId())
				.filter(artifact -> artifact.planId().equals(plan.id())
						&& artifact.planRevision() == plan.currentRevision() && artifact.itemId().equals(coverId))
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "参考封面不属于当前计划版本")))
				.flatMap(artifact -> items.findById(artifact.attemptId())
						.filter(item -> "succeeded".equals(item.state()))
						.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_ANCHOR_REQUIRED", "封面尚未完成")))
						.then(references.resolveMedia(
								Map.of("refType", "media", "id", artifact.originalMediaId().toString()), caller)))
				.then());
	}

	private Mono<QuoteResult> quote(Caller caller, VisualPlan.PlanRow plan, EstimateCommand command,
			CreationStudioContextService.ImageRoute route) {
		var provider = route.provider();
		if (!provider.isPlatform() && !provider.isByok())
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", "图片生成配置不可用"));
		String protocol = ImageProtocolPolicy.protocolOf(provider.provider(), provider.baseUrl());
		if ("reference-image".equals(command.consistencyMode())
				&& !ImageProtocolPolicy.supportsImageReference(protocol))
			return Mono
					.error(new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED", "当前模型不支持通用图片参考，可选择文字风格约束"));
		List<String> warnings = new ArrayList<>();
		if (provider.isByok())
			warnings.add("外部模型费用由供应商侧计费，平台不代扣");
		Map<String, Object> quote = new LinkedHashMap<>();
		quote.put("plan", Map.of("id", plan.id().toString(), "revision", plan.currentRevision()));
		quote.put("selectedItemIds", List.copyOf(command.selectedItemIds()));
		quote.put("imageCalls", command.selectedItemIds().size());
		quote.put("consistencyMode", command.consistencyMode());
		quote.put("anchorArtifactId",
				command.anchorArtifactId() == null ? null : command.anchorArtifactId().toString());
		quote.put("userCredits", 0);
		quote.put("platformBudgetCents", Math.multiplyExact(route.unitPriceCents(), command.selectedItemIds().size()));
		quote.put("billingSource",
				provider.isByok() ? provider.byokOrganizationId() == null ? "personal-byok" : "org-byok" : "platform");
		quote.put("pricingVersion", route.pricingVersion());
		quote.put("configurationFingerprint", CreationStudioContextService.imageFingerprint(route));
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		quote.put("expiresAt", now.plus(QUOTE_TTL).toInstant().toString());
		quote.put("warnings", warnings);
		var row = new VisualPlanRepository.QuoteRow(UUID.randomUUID(), caller.accountId(), plan.id(),
				plan.currentRevision(), command.requestId().toString(), command.requestHash(), quote, now,
				now.plus(QUOTE_TTL));
		return plans.insertQuote(row)
				.flatMap(inserted -> inserted
						? Mono.just(new QuoteResult(row))
						: plans.findQuoteByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
								.flatMap(existing -> replay(existing, plan.id(), command)));
	}
}
