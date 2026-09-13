package com.grassland.intelligence.creationstudio.plan;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Clock;
import java.time.Duration;
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

/**
 * 任务书 #101 C101-05（API101-12）：按当前可用协议的费用估算——只读既有路由与价目， 不
 * prepareMediaExecution、不落 AI run、不冻结资金（TC101-025）。
 *
 * <p>
 * 本卡（C101-07 之前）旧协议只支持 prompt-only：请求 {@code reference-image} 一律
 * STUDIO_REFERENCE_UNSUPPORTED，不假报原生参考支持。平台价目按 {@link ImageGenerationConfig} 单价
 * × 实际图片调用数计入 platformBudgetCents；BYOK 外部费用不冒充「完全免费」（warnings 明示由供应商计费）。估算失败不伪造
 * 0 元——路由拒绝按依赖不可用返回。
 */
@Service
public class VisualQuoteService {

	static final Duration QUOTE_TTL = Duration.ofSeconds(120);
	private static final Set<String> CONSISTENCY_MODES = Set.of("reference-image", "prompt-only");

	private final VisualPlanRepository plans;
	private final VisualPlanService planService;
	private final ByokRoutingService routing;
	private final ImageGenerationConfig imageConfig;
	private final CreationStudioProperties properties;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public VisualQuoteService(VisualPlanRepository plans, VisualPlanService planService, ByokRoutingService routing,
			ImageGenerationConfig imageConfig, CreationStudioProperties properties) {
		this(plans, planService, routing, imageConfig, properties, Clock.systemUTC());
	}

	VisualQuoteService(VisualPlanRepository plans, VisualPlanService planService, ByokRoutingService routing,
			ImageGenerationConfig imageConfig, CreationStudioProperties properties, Clock clock) {
		this.plans = plans;
		this.planService = planService;
		this.routing = routing;
		this.imageConfig = imageConfig;
		this.properties = properties;
		this.clock = clock;
	}

	public record EstimateCommand(UUID requestId, int expectedRevision, List<String> selectedItemIds,
			String consistencyMode, UUID anchorArtifactId) {

		String requestHash() {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("expectedRevision", expectedRevision);
			canonical.put("selectedItemIds", selectedItemIds == null ? List.of() : selectedItemIds);
			canonical.put("consistencyMode", consistencyMode);
			canonical.put("anchorArtifactId", anchorArtifactId == null ? "" : anchorArtifactId.toString());
			return VisualPlanService.hashCanonical(canonical);
		}
	}

	public record QuoteResult(VisualPlanRepository.QuoteRow quote) {
	}

	public Mono<QuoteResult> estimate(Caller caller, UUID planId, EstimateCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		String hash = command.requestHash();
		return plans.findQuoteByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
				.flatMap(existing -> {
					if (!existing.requestHash().equals(hash)) {
						return Mono.error(
								new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同请求"));
					}
					return Mono.just(new QuoteResult(existing));
				}).switchIfEmpty(Mono.defer(() -> validateAndQuote(caller, planId, command, hash)));
	}

	private Mono<QuoteResult> validateAndQuote(Caller caller, UUID planId, EstimateCommand command, String hash) {
		if (command.consistencyMode() == null || !CONSISTENCY_MODES.contains(command.consistencyMode())) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "consistencyMode 不合法"));
		}
		if ("reference-image".equals(command.consistencyMode())) {
			// 本卡只有旧协议：通用参考未开放前不假报支持（C101-07 起按能力目录判定）。
			return Mono.error(new IntelligenceException(409, "STUDIO_REFERENCE_UNSUPPORTED",
					"当前图片协议不支持通用参考，请改用 prompt-only 并重新估算"));
		}
		if (command.anchorArtifactId() != null) {
			// anchor 指向同计划已成功封面 artifact；artifact 表在 C101-09 落地，当前一律不存在。
			return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "封面锚点不存在"));
		}
		if (command.selectedItemIds() == null || command.selectedItemIds().isEmpty()) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selectedItemIds 不能为空"));
		}
		if (new LinkedHashSet<>(command.selectedItemIds()).size() != command.selectedItemIds().size()) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selectedItemIds 不允许重复"));
		}
		return planService.loadOwnedRow(planId, caller).flatMap(plan -> {
			if (!"ready".equals(plan.status())) {
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划未就绪，不能估算"));
			}
			if (plan.currentRevision() != command.expectedRevision()) {
				return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划版本已变化，请刷新后重试"));
			}
			return plans.findRevision(plan.id(), plan.currentRevision()).flatMap(revision -> {
				Set<String> knownIds = itemIdsOf(revision.documentJson());
				for (String itemId : command.selectedItemIds()) {
					if (!knownIds.contains(itemId)) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "所选项目不在当前计划版本内"));
					}
				}
				return quote(caller, plan, command, hash);
			});
		});
	}

	private Mono<QuoteResult> quote(Caller caller, VisualPlan.PlanRow plan, EstimateCommand command, String hash) {
		String organizationId = organizationOf(caller);
		return routing.resolveProvider(organizationId, caller.accountId(), "image_generation", true)
				.flatMap(provider -> {
					if (!provider.isByok() && !provider.isPlatform()) {
						// 决策 G：控制面无行不再回落 env；估算不伪造 0 元（TC101-025）。
						return Mono.error(denied(provider.denialReason()));
					}
					int imageCalls = command.selectedItemIds().size();
					boolean byok = provider.isByok();
					String billingSource = byok
							? (provider.byokOrganizationId() != null ? "org-byok" : "personal-byok")
							: "platform";
					int platformBudgetCents = byok ? 0 : imageConfig.unitPriceCents() * imageCalls;
					List<String> warnings = new ArrayList<>();
					if (byok) {
						warnings.add("外部模型费用由供应商侧计费，平台不代扣（非免费）");
					}
					String fingerprint = configurationFingerprint(provider);
					Map<String, Object> quote = new LinkedHashMap<>();
					quote.put("plan", Map.of("id", plan.id().toString(), "revision", plan.currentRevision()));
					quote.put("selectedItemIds", List.copyOf(command.selectedItemIds()));
					quote.put("imageCalls", imageCalls);
					quote.put("consistencyMode", command.consistencyMode());
					quote.put("anchorArtifactId", null);
					quote.put("userCredits", 0);
					quote.put("platformBudgetCents", platformBudgetCents);
					quote.put("billingSource", billingSource);
					quote.put("pricingVersion", imageConfig.pricingVersion());
					quote.put("configurationFingerprint", fingerprint);
					quote.put("expiresAt",
							clock.instant().plus(QUOTE_TTL).atOffset(ZoneOffset.UTC).toInstant().toString());
					quote.put("warnings", warnings);
					var row = new VisualPlanRepository.QuoteRow(UUID.randomUUID(), caller.accountId(), plan.id(),
							plan.currentRevision(), command.requestId().toString(), hash, quote,
							clock.instant().atOffset(ZoneOffset.UTC),
							clock.instant().plus(QUOTE_TTL).atOffset(ZoneOffset.UTC));
					return plans.insertQuote(row).flatMap(inserted -> inserted
							? Mono.just(new QuoteResult(row))
							: plans.findQuoteByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
									.map(QuoteResult::new));
				});
	}

	/** 商家组织维度走组织配置（与独立生图 D9 同口径：活动身份组织非空即商家视角）。 */
	private static String organizationOf(Caller caller) {
		return caller.organizationId();
	}

	/** 执行配置指纹（§6.5 估算：含目标尺寸、引用方式、价目与执行配置；任务创建时再核对）。 */
	private String configurationFingerprint(ProviderResolution provider) {
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("provider", provider.provider());
		canonical.put("model", provider.model());
		canonical.put("platformModelVersion", provider.platformModelVersion());
		canonical.put("credentialVersion", provider.credentialVersion() == null ? 0 : provider.credentialVersion());
		canonical.put("pricingVersion", imageConfig.pricingVersion());
		canonical.put("protocol", "legacy-generation");
		return PlanJson.sha256(PlanJson.json(canonical));
	}

	private static Set<String> itemIdsOf(String documentJson) {
		Map<String, Object> document = PlanJson.readJson(documentJson);
		Set<String> ids = new LinkedHashSet<>();
		if (document.get("items") instanceof List<?> items) {
			for (Object item : items) {
				if (item instanceof Map<?, ?> itemMap && itemMap.get("itemId") instanceof String itemId) {
					ids.add(itemId);
				}
			}
		}
		return ids;
	}

	private static IntelligenceException denied(String reason) {
		// §6.9：模型缺失／路由不可用 → 503 明确缺项，估算不伪造 0 元（TC101-025）。
		String detail = switch (reason == null ? "" : reason) {
			case "no_platform_model" -> "平台未配置图片生成模型，请到治理台配置后再估算";
			case "own_key_missing" -> "该能力未配置自有模型密钥，无法估算";
			default -> "图片生成路由不可用";
		};
		return new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE", detail);
	}
}
