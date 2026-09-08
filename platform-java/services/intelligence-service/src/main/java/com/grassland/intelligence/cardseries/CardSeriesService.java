package com.grassland.intelligence.cardseries;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.FrozenImageGenerationConfigResolver;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import com.grassland.intelligence.articleimage.TaskImageGenerationService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationcontext.CreationBriefInput;
import com.grassland.intelligence.creationcontext.GraphicTaskCreationContext;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.media.MediaOwner;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.articleimage.GeneratedImageStore;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 系列图卡生成编排（任务书 #54）：两段式——①卡片计划（文本执行环，CARD_SERIES_PLAN 积分， SSE 帧）；②逐卡生图（每卡独立预算闸
 * image_generation cents 口径 + 部分成功语义， PublicAssetBatchGeneration 先例）+ 整系列
 * lineage kind=card_series。
 *
 * <p>
 * 计划不落库：用户编辑后随 generate 请求回传。AI内容中心改造-02：任务模式经
 * {@link GraphicTaskCreationContext#bind} 冻结快照执行（计划走 executeTraced、生图走
 * {@link TaskImageGenerationService#generateForBoundContextTraced}，purpose 仍为 CARD_SERIES）；
 * 带 {@code requestId} 的生成建立 owner+requestId 唯一的操作记录（T21：同请求回读、异请求 409、
 * 占位后方可执行）。
 */
@Service
public class CardSeriesService {

	private static final Logger log = LoggerFactory.getLogger(CardSeriesService.class);
	static final Duration GENERATION_TIMEOUT = Duration.ofSeconds(180);
	static final int MAX_CARDS = 9;
	static final String PERMANENT_KEY_PREFIX = "media/card_series/";
	private static final java.util.Set<String> SIZES = java.util.Set.of("1024x1024", "1024x1792", "1792x1024");

	private final FrozenTextExecutionService frozenText;
	private final com.grassland.intelligence.articleimage.IndependentImageGenerationService imageGeneration;
	private final TaskImageGenerationService taskImageGeneration;
	private final GraphicTaskCreationContext creationContexts;
	private final CardSeriesOperationRepository operations;
	private final CreationGenerationRecorder lineage;
	private final MediaReferenceRepository mediaRefs;
	private final GeneratedImageStore generatedStore;
	private final ObjectProvider<ObjectStorageAdapter> storageProvider;
	private final ObjectMapper mapper = new ObjectMapper()
			.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

	public CardSeriesService(FrozenTextExecutionService frozenText,
			com.grassland.intelligence.articleimage.IndependentImageGenerationService imageGeneration,
			TaskImageGenerationService taskImageGeneration, GraphicTaskCreationContext creationContexts,
			CardSeriesOperationRepository operations,
			CreationGenerationRecorder lineage, MediaReferenceRepository mediaRefs, GeneratedImageStore generatedStore,
			ObjectProvider<ObjectStorageAdapter> storageProvider) {
		this.frozenText = frozenText;
		this.imageGeneration = imageGeneration;
		this.taskImageGeneration = taskImageGeneration;
		this.creationContexts = creationContexts;
		this.operations = operations;
		this.lineage = lineage;
		this.mediaRefs = mediaRefs;
		this.generatedStore = generatedStore;
		this.storageProvider = storageProvider;
	}

	// ------------------------------------------------------------------
	// ① 卡片计划（SSE）
	// ------------------------------------------------------------------

	/**
	 * 拆卡计划：独立模式经 {@link FrozenTextExecutionService#executeIndependent} 单环执行；任务模式先
	 * {@link GraphicTaskCreationContext#bind} 校验快照归属/平台（错配在模型调用前 409），再走
	 * {@code executeTraced} 消费冻结配置。两模式都注入 Brief（用户事实与补充要求）。
	 */
	public Mono<Flux<String>> planStream(PlanInput input, String accountId, String organizationId,
			ServerWebExchange exchange) {
		com.grassland.intelligence.ai.ChatMessage system = com.grassland.intelligence.ai.ChatMessage
				.system(CardSeriesPrompts.systemPlan(input));
		com.grassland.intelligence.ai.ChatMessage user = CreationBriefInput.append(
				com.grassland.intelligence.ai.ChatMessage.user(CardSeriesPrompts.userPlan(input)), input.brief());
		Mono<Traced<CardSeriesPlan>> executed = input.contextSnapshotId() != null
				? creationContexts.bind(input.contextSnapshotId(), accountId, input.platform())
						.flatMap(binding -> frozenText.executeTraced(exchange, input.contextSnapshotId(),
								List.of(system, binding.promptContext(), user),
								8192, CreditFeature.CARD_SERIES_PLAN, completion -> parsePlan(completion.content())))
				: frozenText.executeIndependent(exchange, List.of(system, user),
						// 2026-09-02 画面描述结构化改版后单卡输出 ~200 字；思考型模型的 reasoning
						// tokens 同占此预算（4096 时实测 JSON 尾部截断 → 解析 502），提到 8192。
						8192, CreditFeature.CARD_SERIES_PLAN, GENERATION_TIMEOUT,
						completion -> parsePlan(completion.content()));
		return executed
				.map(trace -> Flux.concat(Mono.just(progressFrame()), Mono.just(planResultFrame(trace.value()))));
	}

	// ------------------------------------------------------------------
	// ② 逐卡生成（JSON，部分成功）
	// ------------------------------------------------------------------

	public Mono<BatchResponse> generate(GenerateInput input, String accountId, String organizationId) {
		List<CardOutcome> outcomes = new ArrayList<>();
		// 系列一致性锚：首卡 revised_prompt 注入后续卡（D 决策；concatMap 串行下安全）
		String[] styleAnchor = new String[]{null};
		Mono<GraphicTaskCreationContext.Binding> binding = input.contextSnapshotId() == null
				? Mono.just(new GraphicTaskCreationContext.Binding(null, null))
				: creationContexts.bind(input.contextSnapshotId(), accountId, input.platform());
		return binding.flatMap(bound -> Flux.range(0, input.cards().size())
				.concatMap(index -> generateCard(input, index, accountId, organizationId, styleAnchor, bound)
						.doOnNext(outcomes::add))
				.then(recordLineage(input, outcomes, accountId, organizationId))
				// outcomes 在流完成后才齐全——defer 到订阅期取值（eager-assembly 陷阱）
				.then(Mono.fromSupplier(() -> new BatchResponse(List.copyOf(outcomes)))));
	}

	/**
	 * 带操作记录的生成（T21）：owner+requestId 唯一占位。同 requestId 同摘要回读已记录结果；
	 * 摘要变化 409；占位后仍在执行/待确认的运行返回 409 待确认（先经查询端点核实，用户显式
	 * 重做应使用新的 requestId，避免重复计费）。
	 */
	public Mono<BatchResponse> generateRecorded(GenerateInput input, String accountId, String organizationId,
			String requestId) {
		if (requestId == null || requestId.isBlank()) {
			return generate(input, accountId, organizationId);
		}
		String normalized = requestId.trim();
		if (normalized.length() > 128) {
			return Mono.error(new IntelligenceException(400, "requestId 过长"));
		}
		String digest = CardSeriesOperationRepository.digestOf(canonicalPayload(input));
		return operations.claim(accountId, normalized, digest, input.contextSnapshotId()).flatMap(claim -> {
			CardSeriesOperationRepository.OperationRow row = claim.row();
			if (!claim.inserted()) {
				if (!digest.equals(row.requestDigest())) {
					return Mono.error(new IntelligenceException(409, "CARD_OPERATION_CONFLICT",
							"相同请求 ID 的图卡操作参数已变化"));
				}
				if (CardSeriesOperationRepository.STATUS_SUCCEEDED.equals(row.status())
						|| CardSeriesOperationRepository.STATUS_FAILED.equals(row.status())) {
					return Mono.just(replay(row));
				}
				return Mono.error(new IntelligenceException(409, "CARD_OPERATION_PENDING",
						"该图卡操作仍在执行或结果待确认，请先查询操作状态"));
			}
			return generate(input, accountId, organizationId)
					.flatMap(batch -> operations.succeed(row.id(), writeBatch(batch)).thenReturn(batch))
					.onErrorResume(error -> operations.fail(row.id(), "CARD_GENERATION_ERROR",
							error.getMessage() == null ? "生成失败，请稍后重试" : error.getMessage())
							.then(Mono.error(error)));
		});
	}

	private String writeBatch(BatchResponse batch) {
		try {
			return mapper.writeValueAsString(Map.of("cards", batch.cards()));
		} catch (Exception error) {
			throw new IllegalStateException("图卡操作结果序列化失败", error);
		}
	}

	private BatchResponse replay(CardSeriesOperationRepository.OperationRow row) {
		JsonNode cards = row.result().path("cards");
		if (!cards.isMissingNode() && cards.isArray()) {
			try {
				return new BatchResponse(mapper.convertValue(cards,
						mapper.getTypeFactory().constructCollectionType(List.class, CardOutcome.class)));
			} catch (IllegalArgumentException error) {
				log.warn("图卡操作 {} 结果回读失败", row.id(), error);
			}
		}
		return new BatchResponse(List.of());
	}

	private Map<String, Object> canonicalPayload(GenerateInput input) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("platform", input.platform());
		payload.put("cards", input.cards());
		payload.put("styleText", input.styleText());
		payload.put("layoutText", input.layoutText());
		payload.put("paletteText", input.paletteText());
		payload.put("size", input.size());
		payload.put("styleAnchor", input.styleAnchor());
		payload.put("contextSnapshotId", input.contextSnapshotId() == null ? null : input.contextSnapshotId().toString());
		return payload;
	}

	private Mono<CardOutcome> generateCard(GenerateInput input, int index, String accountId, String organizationId,
			String[] styleAnchor, GraphicTaskCreationContext.Binding binding) {
            CardSeriesService.CardPlan card = input.cards().get(index);
            int position = card.position() == null ? index + 1 : card.position();
            String role = card.role() == null ? (position == 1 ? "cover" : "content") : card.role();
		// 显式风格锚（单卡重试）优先；否则用运行期首卡锚（首卡自身为 null）
		String anchor = input.styleAnchor() != null ? input.styleAnchor() : styleAnchor[0];
            String prompt = CardSeriesPrompts.cardPrompt(input, card, position, role, anchor);
		ArticleImageService.GenerateCommand command = new ArticleImageService.GenerateCommand(prompt, input.size(),
			List.of());
		Mono<CardImageTrace> traced = binding.snapshot() == null
				? imageGeneration.generate(command, accountId, organizationId, MediaPurpose.CARD_SERIES)
						.map(done -> new CardImageTrace(done.response(), done.aiRunId(), done.provider(), done.model()))
				: taskImageGeneration.generateForBoundContextTraced(command, binding.snapshot(),
						binding.promptContext(), MediaPurpose.CARD_SERIES)
						.map(done -> new CardImageTrace(done.response(), done.aiRunId(), done.provider(), done.model()));
		return traced.map(done -> {
                if ("cover".equals(role) && done.response().revisedPrompt() != null) {
                    styleAnchor[0] = done.response().revisedPrompt();
                }
                return CardOutcome.success(position - 1, card.cardId(), role, card.title(), done.response(),
                        done.aiRunId(), done.provider(), done.model());
            }).onErrorResume(error -> {
                log.warn("系列图卡第 {} 张生成失败", index + 1, error);
                return Mono.just(CardOutcome.failure(position - 1, card.cardId(), role, card.title(), publicReason(error)));
		});
	}

	/**
	 * 整系列 lineage（kind=card_series，一行；resultMediaIds=成功卡）。组装期只捕获引用，取值 defer 到订阅期。
	 */
	private Mono<Void> recordLineage(GenerateInput input, List<CardOutcome> outcomes, String accountId,
			String organizationId) {
		return Mono.defer(() -> {
			UUID firstRunId = outcomes.stream().filter(CardOutcome::ok).findFirst().map(CardOutcome::aiRunId)
					.orElse(null);
			List<UUID> mediaIds = outcomes.stream().filter(CardOutcome::ok)
					.map(outcome -> UUID.fromString(outcome.mediaId())).toList();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("okCount", outcomes.stream().filter(CardOutcome::ok).count());
			List<Map<String, Object>> cards = new ArrayList<>();
			for (CardOutcome outcome : outcomes) {
				Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", outcome.index());
                if (outcome.cardId() != null) item.put("cardId", outcome.cardId());
                if (outcome.role() != null) item.put("role", outcome.role());
				item.put("title", outcome.title());
				item.put("ok", outcome.ok());
				if (outcome.ok()) {
					item.put("url", outcome.imageUrl());
					item.put("revisedPrompt", outcome.revisedPrompt());
				} else {
					item.put("errorReason", outcome.errorReason());
				}
				cards.add(item);
			}
			result.put("cards", cards);
			Map<String, Object> inputSummary = new LinkedHashMap<>();
			inputSummary.put("platform", input.platform());
			inputSummary.put("cardCount", input.cards().size());
			inputSummary.put("styleText", input.styleText());
			inputSummary.put("layoutText", input.layoutText());
			inputSummary.put("paletteText", input.paletteText());
			inputSummary.put("size", input.size());
			String promptText = "系列图卡：" + input.styleText() + "；卡片：" + outcomes.stream()
					.map(outcome -> outcome.index() + "." + outcome.title()).reduce((a, b) -> a + " / " + b).orElse("");
			CardOutcome first = outcomes.isEmpty() ? null : outcomes.get(0);
			CreationGeneration.Mode mode = input.contextSnapshotId() == null
					? CreationGeneration.Mode.INDEPENDENT : CreationGeneration.Mode.TASK;
			return lineage.record(new CreationGenerationRecorder.Command(CreationGeneration.Kind.CARD_SERIES,
					mode, input.contextSnapshotId(), firstRunId, CreationGeneration.Resolution.PLATFORM,
					first == null ? "unknown" : first.provider(), first == null ? null : first.model(), null, null,
					promptText, inputSummary, List.of(), result, mediaIds, accountId, organizationId)).then()
					.onErrorResume(error -> {
						// lineage 是 advisory：失败不吞生成结果（强一致写失败记日志）
						log.error("系列图卡 lineage 落库失败 owner={}", accountId, error);
						return Mono.empty();
					});
		});
	}

	/** 操作查询（T21）：只回状态与记录结果，不触发任何生成。 */
	public Mono<Map<String, Object>> findOperation(String accountId, String requestId) {
		if (requestId == null || requestId.isBlank()) {
			return Mono.error(new IntelligenceException(400, "requestId 不能为空"));
		}
		return operations.find(accountId, requestId.trim()).map(row -> {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("requestId", requestId.trim());
			body.put("status", row.status());
			if (row.errorCode() != null) body.put("errorCode", row.errorCode());
			if (row.errorMessage() != null) body.put("errorMessage", row.errorMessage());
			JsonNode cards = row.result().path("cards");
			if (cards.isArray()) {
				// JsonNode 直接进响应体会被编码成节点描述 Map——显式转 List 保持 JSON 形态。
				body.put("cards", mapper.convertValue(cards,
						mapper.getTypeFactory().constructCollectionType(List.class, Map.class)));
			} else {
				body.put("cards", List.of());
			}
			body.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
			return body;
		});
	}

	/** 独立/任务两条执行路径的统一结果视图。 */
	private record CardImageTrace(GeneratedImageResponse response, UUID aiRunId, String provider, String model) {
	}

	// ------------------------------------------------------------------
	// ③ 单卡持久化（TTL → 永久）
	// ------------------------------------------------------------------

	/**
	 * 把 30min TTL 的生成卡复制到永久 key {@code media/card_series/<原卡id>} 并建永久 media 行 （无
	 * expires_at）。幂等：按 object_key 查重直接返回既有行。素材库注册由前端复用既有
	 * {@code POST /api/content-assets}（个人库，IDOR 守卫/索引/事件链完整）。
	 */
	public Mono<PersistResponse> persist(String cardId, String accountId) {
		UUID id;
		try {
			id = UUID.fromString(cardId);
		} catch (IllegalArgumentException error) {
			return Mono.error(new IntelligenceException(404, "图片不存在或已过期"));
		}
		String objectKey = PERMANENT_KEY_PREFIX + id;
		// 幂等命中也要校验归属——永久行 owner 必须是调用者（不泄漏他人 mediaId）
		return mediaRefs.findByObjectKey(objectKey).filter(existing -> accountId.equals(existing.ownerAccountId()))
				.map(existing -> new PersistResponse(existing.id().toString()))
				.switchIfEmpty(Mono.defer(() -> claimAndPersist(id, accountId, objectKey)));
	}

	private Mono<PersistResponse> claimAndPersist(UUID id, String accountId, String objectKey) {
		// 归属校验：TTL 行 owner 必须是调用者（404 不泄漏存在性）
		return mediaRefs.findById(id).filter(ref -> accountId.equals(ref.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "图片不存在或已过期")))
				.flatMap(ttl -> generatedStore.find(id.toString())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "图片不存在或已过期"))).flatMap(stored -> {
							ObjectStorageAdapter storage = storageProvider.getIfAvailable();
							if (storage == null) {
								return Mono.error(new IntelligenceException(503, "持久化需要启用对象存储"));
							}
							MediaReference permanent = new MediaReference(UUID.randomUUID(), ttl.ownerAccountId(),
									ttl.organizationId(), MediaPurpose.CARD_SERIES.db(), null, null, objectKey,
									"image/png", stored.bytes().length,
									com.grassland.intelligence.media.MediaChecksums.sha256(stored.bytes()), "generated",
									com.grassland.intelligence.media.MediaStatus.ACTIVE, null, null, null);
							return Mono.fromRunnable(() -> storage.putObject(objectKey, stored.bytes(), "image/png"))
									.subscribeOn(Schedulers.boundedElastic()).then(mediaRefs.insert(permanent))
									.map(saved -> new PersistResponse(saved.id().toString()))
									// 并发双击兜底：插入竞态时按 object_key 收敛到同一条
									.onErrorResume(error -> mediaRefs.findByObjectKey(objectKey)
											.map(existing -> new PersistResponse(existing.id().toString())));
						}));
	}

	// ------------------------------------------------------------------
	// 解析与帧
	// ------------------------------------------------------------------

	/** 解析拆卡计划：剥 code fence → JSON → cards 必填且条数与请求一致。 */
	CardSeriesPlan parsePlan(String content) {
		String stripped = stripCodeFence(content == null ? "" : content).trim();
		JsonNode node;
		try {
			node = mapper.readTree(stripped);
		} catch (Exception error) {
			throw new IntelligenceException(502, "卡片计划服务返回了无法解析的内容");
		}
		JsonNode cardsNode = node.path("cards");
		if (!cardsNode.isArray() || cardsNode.isEmpty()) {
			throw new IntelligenceException(502, "卡片计划服务返回了空结果");
		}
		List<CardPlan> cards = new ArrayList<>();
		for (JsonNode item : cardsNode) {
			String title = optionalText(item.get("title"));
			if (title == null) {
				throw new IntelligenceException(502, "卡片计划缺少标题");
			}
			List<String> bullets = new ArrayList<>();
			JsonNode bulletsNode = item.path("bullets");
			if (bulletsNode.isArray()) {
				for (JsonNode bullet : bulletsNode) {
					String text = optionalText(bullet);
					if (text != null) {
						bullets.add(text);
					}
				}
			}
            int position = cards.size();
            cards.add(new CardPlan(UUID.randomUUID().toString(), position + 1, position == 0 ? "cover" : "content", title,
                    List.copyOf(bullets), optionalText(item.get("illustration")), optionalText(item.get("caption"))));
		}
		return new CardSeriesPlan(List.copyOf(cards));
	}

	private static String progressFrame() {
		return "{\"type\":\"progress\",\"message\":\"正在拆解卡片计划…\"}";
	}

	private String planResultFrame(CardSeriesPlan plan) {
		Map<String, Object> frame = new LinkedHashMap<>();
		frame.put("type", "result");
		List<Map<String, Object>> cards = new ArrayList<>();
		for (CardPlan card : plan.cards()) {
			Map<String, Object> item = new LinkedHashMap<>();
			if (card.cardId() != null) item.put("cardId", card.cardId());
			if (card.position() != null) item.put("position", card.position());
			if (card.role() != null) item.put("role", card.role());
			item.put("title", card.title());
			item.put("bullets", card.bullets());
			item.put("illustration", card.illustration() == null ? "" : card.illustration());
			item.put("caption", card.caption() == null ? "" : card.caption());
			cards.add(item);
		}
		frame.put("cards", cards);
		try {
			return mapper.writeValueAsString(frame);
		} catch (Exception error) {
			throw new IntelligenceException(502, "卡片计划服务返回了无法解析的内容");
		}
	}

	private static String stripCodeFence(String text) {
		int start = text.indexOf("```");
		if (start < 0) {
			return text;
		}
		int newline = text.indexOf('\n', start);
		int contentStart = newline < 0 ? start + 3 : newline + 1;
		int end = text.lastIndexOf("```");
		if (end <= contentStart) {
			return text;
		}
		return text.substring(contentStart, end);
	}

	private static String optionalText(JsonNode node) {
		if (node == null || node.isNull() || !node.isTextual()) {
			return null;
		}
		String text = node.asText().trim();
		return text.isEmpty() ? null : text;
	}

	private static String errorMessage(Throwable error) {
		return error.getMessage() == null ? "card generation failed" : error.getMessage();
	}

	private static String publicReason(Throwable error) {
		if (error instanceof IntelligenceException intelligence) {
			return intelligence.getMessage();
		}
		return "生成失败，请稍后重试";
	}

	// ------------------------------------------------------------------
	// 输入/输出契约
	// ------------------------------------------------------------------

	/**
	 * 计划请求（2026-08-30 修订：制作方式取消，图卡并入小红书图文流）——拆卡对象是**已生成的长图文内容**，
	 * 模板描述词由前端常量组装传入（后端模板无关，PRD「模板按能力配置」原则）。
	 * {@code brief} 为统一创作简报（CreationBriefInput 校验）；{@code contextSnapshotId} 非空即任务模式。
	 */
	public record PlanInput(String platform, String content, int cardCount, String styleText, String layoutText,
			String paletteText, Map<String, Object> brief, UUID contextSnapshotId) {
		public PlanInput {
			content = content == null ? "" : content.trim();
			if (content.isEmpty() || content.length() > 8000) {
				throw new IntelligenceException(400, "待拆解内容需为 1-8000 字");
			}
			if (cardCount < 1 || cardCount > MAX_CARDS) {
				throw new IntelligenceException(400, "卡片数量需在 1-" + MAX_CARDS + " 之间");
			}
			styleText = requireDescriptor(styleText, "视觉风格");
			layoutText = requireDescriptor(layoutText, "画面布局");
			paletteText = normalizeDescriptor(paletteText);
			brief = CreationBriefInput.validate(brief);
			if (contextSnapshotId != null && (platform == null || platform.isBlank())) {
				throw new IntelligenceException(400, "任务模式必须指定平台以校验快照");
			}
		}
	}

	public record CardPlan(String cardId, Integer position, String role, String title, List<String> bullets,
			String illustration, String caption) {
		public CardPlan(String title, List<String> bullets, String illustration, String caption) {
			this(null, null, null, title, bullets, illustration, caption);
		}

		public CardPlan {
			cardId = cardId == null || cardId.isBlank() ? null : cardId.trim();
			if (cardId != null && cardId.length() > 64) throw new IntelligenceException(400, "卡片 ID 过长");
			if (position != null && (position < 1 || position > MAX_CARDS)) {
				throw new IntelligenceException(400, "卡片位置无效");
			}
			role = role == null || role.isBlank() ? null : role.trim();
			if (role != null && !role.equals("cover") && !role.equals("content") && !role.equals("summary")) {
				throw new IntelligenceException(400, "卡片角色无效");
			}
			title = title == null ? "" : title.trim();
			if (title.isEmpty() || title.length() > 100) {
				throw new IntelligenceException(400, "卡片标题需为 1-100 字");
			}
			bullets = bullets == null ? List.of() : List.copyOf(bullets);
			illustration = illustration == null ? "" : illustration.trim();
			caption = caption == null ? "" : caption.trim();
		}
	}

	public record GenerateInput(String platform, List<CardPlan> cards, String styleText, String layoutText,
			String paletteText, String size, String styleAnchor, UUID contextSnapshotId) {
		public GenerateInput(String platform, List<CardPlan> cards, String styleText, String layoutText,
				String paletteText, String size, String styleAnchor) {
			this(platform, cards, styleText, layoutText, paletteText, size, styleAnchor, null);
		}

		public GenerateInput {
			if (cards == null || cards.isEmpty() || cards.size() > MAX_CARDS) {
				throw new IntelligenceException(400, "卡片数量需在 1-" + MAX_CARDS + " 之间");
			}
			if (contextSnapshotId != null && (platform == null || platform.isBlank())) {
				throw new IntelligenceException(400, "任务模式必须指定平台以校验快照");
			}
			styleText = requireDescriptor(styleText, "视觉风格");
			layoutText = requireDescriptor(layoutText, "画面布局");
			paletteText = normalizeDescriptor(paletteText);
			size = (size == null || size.isBlank()) ? "1024x1792" : size.trim();
			if (!SIZES.contains(size)) {
				throw new IntelligenceException(400, "不支持的图片尺寸");
			}
			styleAnchor = styleAnchor == null || styleAnchor.isBlank() ? null : styleAnchor.trim();
			if (styleAnchor != null && styleAnchor.length() > 400) {
				styleAnchor = styleAnchor.substring(0, 400);
			}
			cards = List.copyOf(cards);
			var ids = new java.util.HashSet<String>();
			var positions = new java.util.HashSet<Integer>();
			for (CardPlan card : cards) {
				if (card.cardId() != null && (card.position() == null || card.role() == null)) throw new IntelligenceException(400, "卡片身份需同时包含 ID、位置与角色");
				if (card.cardId() != null && !ids.add(card.cardId())) throw new IntelligenceException(400, "卡片 ID 重复");
				if (card.position() != null && !positions.add(card.position())) throw new IntelligenceException(400, "卡片位置重复");
			}
		}
	}

	public record CardOutcome(int index, String cardId, String role, String title, boolean ok, String imageUrl,
				String revisedPrompt, String errorReason, UUID aiRunId, String provider, String model) {

		static CardOutcome success(int index, String cardId, String role, String title, GeneratedImageResponse response,
				UUID aiRunId,
				String provider, String model) {
			return new CardOutcome(index, cardId, role, title, true, response.imageUrl(), response.revisedPrompt(), null,
					aiRunId, provider, model);
		}

		static CardOutcome failure(int index, String cardId, String role, String title, String errorReason) {
			return new CardOutcome(index, cardId, role, title, false, null, null, errorReason, null, null, null);
		}

		String mediaId() {
			return imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
		}
	}

	public record BatchResponse(List<CardOutcome> cards) {
	}

	public record PersistResponse(String mediaId) {
	}

	public record CardSeriesPlan(List<CardPlan> cards) {
	}

	private static String requireDescriptor(String value, String label) {
		String normalized = normalizeDescriptor(value);
		if (normalized == null) {
			throw new IntelligenceException(400, label + "描述不能为空");
		}
		return normalized;
	}

	private static String normalizeDescriptor(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
	}
}
