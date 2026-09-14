package com.grassland.intelligence.creationstudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-04：studio 上下文读取与 baseContentHash（§6.5）。
 *
 * baseContentHash 是「当前正文 LF
 * 版本、articleTitle、contentMode、questionText/questionRef、
 * platform/contentForm、影响生成的 Brief 字段」的规范化 JSON 摘要；不含导航 title、发布描述、 下载 URL、UI
 * 主题或保存时间。正文只统一 CRLF/CR 为 LF，不做 NFKC／全半角转换。
 */
@Service
public class CreationStudioContextService {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CreationDraftService drafts;
	private final com.grassland.intelligence.creationcontext.GraphicTaskCreationContext taskContexts;
	private final com.grassland.intelligence.ai.run.FrozenTextExecutionService text;
	private final com.grassland.intelligence.ai.byok.ByokRoutingService routing;
	private final com.grassland.intelligence.creationcontext.FrozenAiConfigResolver frozenAi;
	private final com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver frozenImages;
	private final com.grassland.intelligence.articleimage.ImageGenerationConfig imagePricing;

	public CreationStudioContextService(CreationDraftService drafts,
			com.grassland.intelligence.creationcontext.GraphicTaskCreationContext taskContexts,
			com.grassland.intelligence.ai.run.FrozenTextExecutionService text,
			com.grassland.intelligence.ai.byok.ByokRoutingService routing,
			com.grassland.intelligence.creationcontext.FrozenAiConfigResolver frozenAi,
			com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver frozenImages,
			com.grassland.intelligence.articleimage.ImageGenerationConfig imagePricing) {
		this.drafts = drafts;
		this.taskContexts = taskContexts;
		this.text = text;
		this.routing = routing;
		this.frozenAi = frozenAi;
		this.frozenImages = frozenImages;
		this.imagePricing = imagePricing;
	}

	public record DraftContext(CreationDraft draft, String baseContentHash, String lfContent) {
	}

	public Mono<DraftContext> loadOwnedDraftContext(String draftId, Caller caller) {
		return drafts.loadOwned(draftId, caller.accountId()).map(draft -> {
			requireStudioScope(draft);
			String lfContent = normalizeLf(draft.content());
			return new DraftContext(draft, computeBaseContentHash(draft), lfContent);
		});
	}

	public static void requireStudioScope(CreationDraft draft) {
		Object capability = draft.workspace() == null ? null : draft.workspace().get("capability");
		if ((capability != null && !"article".equals(capability)) || !"graphic".equals(draft.contentForm())
				|| !java.util.Set.of("xiaohongshu", "douyin", "wechat-official", "zhihu").contains(draft.platform())) {
			throw invalid("该草稿不支持图文创作工作流");
		}
	}

	public Mono<java.util.Optional<com.grassland.intelligence.creationcontext.GraphicTaskCreationContext.Binding>> taskBinding(
			CreationDraft draft, String accountId) {
		Map<?, ?> inputs = draft.workspace() != null && draft.workspace().get("inputs") instanceof Map<?, ?> value
				? value
				: Map.of();
		Object rawId = inputs.get("contextSnapshotId");
		boolean task = draft.sourceType() == com.grassland.intelligence.creationassistant.DraftSourceType.TASK;
		if (!task && draft.taskId() == null && draft.taskVersion() == null && rawId == null)
			return Mono.just(java.util.Optional.empty());
		if (!task || draft.taskId() == null || draft.taskVersion() == null || !(rawId instanceof String id))
			return Mono.error(invalid("任务创作必须携带一致的任务来源与冻结快照"));
		java.util.UUID snapshotId;
		try {
			snapshotId = java.util.UUID.fromString(id);
		} catch (Exception error) {
			return Mono.error(invalid("任务快照引用无效"));
		}
		return taskContexts.bind(snapshotId, accountId, draft.platform()).map(binding -> {
			var snapshot = binding.snapshot();
			if (!draft.taskId().equals(snapshot.taskId()) || draft.taskVersion() != snapshot.taskVersion()
					|| (draft.organizationId() != null && !draft.organizationId().equals(snapshot.organizationId()))
					|| (draft.storeId() != null
							&& !draft.storeId().equals(String.valueOf(snapshot.taskSnapshot().get("storeId")))))
				throw invalid("草稿来源与冻结任务不一致");
			return java.util.Optional.of(binding);
		});
	}

	public <T> Mono<com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<T>> executeText(
			org.springframework.web.server.ServerWebExchange exchange, Caller caller, DraftContext context,
			java.util.List<com.grassland.intelligence.ai.ChatMessage> messages, int maxTokens,
			com.grassland.intelligence.credits.CreditFeature feature, java.time.Duration timeout,
			java.util.function.BiFunction<java.util.UUID, java.util.List<com.grassland.intelligence.ai.ChatMessage>, Mono<Void>> prepared,
			java.util.function.Function<com.grassland.intelligence.ai.run.TextCompletionResult, T> transform) {
		return taskBinding(context.draft(), caller.accountId()).flatMap(binding -> {
			if (binding.isEmpty())
				return text.executeCaptured(exchange, caller, null, messages, maxTokens, feature, timeout, prepared,
						transform);
			var task = binding.get();
			java.util.List<com.grassland.intelligence.ai.ChatMessage> frozenMessages = new java.util.ArrayList<>();
			frozenMessages.add(task.promptContext());
			frozenMessages.addAll(messages);
			return text.executeCaptured(exchange, caller, task.snapshot().id(), frozenMessages, maxTokens, feature,
					timeout, prepared, transform);
		});
	}

	public record ImageRoute(com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution provider,
			int unitPriceCents, String pricingVersion,
			com.grassland.intelligence.creationcontext.GraphicTaskCreationContext.Binding binding) {
	}

	public Mono<ImageRoute> imageRoute(CreationDraft draft, String accountId, String organizationId) {
		return taskBinding(draft, accountId).flatMap(binding -> {
			if (binding.isEmpty())
				return routing.resolveProvider(organizationId, accountId, "image_generation", true)
						.map(provider -> new ImageRoute(provider, provider.isByok() ? 0 : imagePricing.unitPriceCents(),
								imagePricing.pricingVersion(), null));
			var task = binding.get();
			return frozenAi.resolveImageProvider(task.snapshot(), accountId)
					.map(provider -> new ImageRoute(provider, 0, imagePricing.pricingVersion(), task))
					.switchIfEmpty(frozenImages.resolve(task.snapshot()).map(resolved -> {
						var config = resolved.platform();
						var provider = com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution
								.platform(config.configId(), config.provider(), config.baseUrl(), config.model(),
										config.version(), config.maxConcurrency(), config.credentialEncryptedKey(),
										config.credentialVersion());
						return new ImageRoute(provider, resolved.pricing().unitPriceCents(),
								resolved.pricing().pricingVersion(), task);
					}));
		});
	}

	public static String imageFingerprint(ImageRoute route) {
		var provider = route.provider();
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("provider", provider.provider());
		fields.put("model", provider.model());
		fields.put("origin", provider.baseUrl());
		fields.put("type", provider.type());
		fields.put("platformModelVersion", provider.platformModelVersion());
		fields.put("credentialVersion", provider.credentialVersion());
		fields.put("keyFingerprint", com.grassland.intelligence.creationstudio.plan.PlanJson
				.sha256(String.valueOf(provider.encryptedKey())));
		fields.put("pricingVersion", route.pricingVersion());
		fields.put("unitPriceCents", route.unitPriceCents());
		return com.grassland.intelligence.creationstudio.plan.PlanJson
				.sha256(com.grassland.intelligence.creationstudio.plan.PlanJson.json(fields));
	}

	/** 服务端按当前草稿重算 baseContentHash 与建议基线比对（§6.5：不能信任客户端版本引用）。 */
	public static String computeBaseContentHash(CreationDraft draft) {
		String lfContent = normalizeLf(draft.content());
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("content", lfContent);
		canonical.put("articleTitle", draft.articleTitle());
		canonical.put("contentMode", draft.contentMode() == null ? null : draft.contentMode().db());
		canonical.put("questionText", draft.questionText());
		canonical.put("questionRef", draft.questionRef());
		canonical.put("platform", draft.platform());
		canonical.put("contentForm", draft.contentForm());
		Map<String, Object> workspace = draft.workspace() == null ? Map.of() : draft.workspace();
		Object brief = workspace.get("inputs") instanceof Map<?, ?> inputs ? inputs.get("brief") : null;
		canonical.put("brief", brief == null ? workspace.get("brief") : brief);
		return sha256Json(canonical);
	}

	static String normalizeLf(String text) {
		if (text == null || text.indexOf('\r') < 0) {
			return text == null ? "" : text;
		}
		return text.replace("\r\n", "\n").replace("\r", "\n");
	}

	private static String sha256Json(Map<String, Object> canonical) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(com.grassland.intelligence.creationstudio.plan.PlanJson
					.json(canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("上下文摘要计算失败", error);
		}
	}

	static IntelligenceException invalid(String message) {
		return new IntelligenceException(400, "STUDIO_INVALID_INPUT", message);
	}
}
