package com.grassland.intelligence.creationstudio.plan;

import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_BULLETS;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_BULLET_LENGTH;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_CRITICAL;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_CRITICAL_LENGTH;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_EXPLANATION;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_ILLUSTRATION;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_TITLE;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.allowedRoles;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveAspect;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveItemCount;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveRecipe;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveStrategy;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveStyle;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.selectBlocks;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.styleText;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.layoutText;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.paletteText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog.RecipeDefinition;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.CreationStudioContextService.DraftContext;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService.Outcome;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService.PrepareCommand;
import com.grassland.intelligence.creationstudio.source.SourceDocument;
import com.grassland.intelligence.creationstudio.source.SourceDocumentRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.crypto.EnvelopeEncryption;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：视觉计划「构建」服务——自 {@link VisualPlanService} 按职责原样搬移： prepare
 * 全链（解析冻结→信封加密占位→模型执行→严格解析落档）与幂等重放，以及构建/修订两侧共用的
 * 计划读组装（loadOwnedRow/withDocument/来源装载/写入闸）。逻辑零变更；facade 以委托保持既有 API。
 */
@Service
public class VisualPlanBuildService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);
	private static final int MAX_TOKENS = 8192;

	private final VisualPlanRepository plans;
	private final CreationStudioContextService contexts;
	private final SourceDocumentRepository sources;
	private final CreationStudioProperties properties;
	private final CreationDraftService drafts;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final TransactionalOperator transactions;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public VisualPlanBuildService(VisualPlanRepository plans, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties, CreationDraftService drafts,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, TransactionalOperator transactions) {
		this(plans, contexts, sources, properties, drafts, encryptionProvider, transactions, Clock.systemUTC());
	}

	VisualPlanBuildService(VisualPlanRepository plans, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties, CreationDraftService drafts,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, TransactionalOperator transactions, Clock clock) {
		this.plans = plans;
		this.contexts = contexts;
		this.sources = sources;
		this.properties = properties;
		this.drafts = drafts;
		this.encryptionProvider = encryptionProvider;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** 解析产物：占位与模型调用所需的全部冻结输入。 */
	record Resolved(RecipeDefinition recipe, DraftContext context, SourceDocument document,
			List<SourceDocument.Block> blocks, String strategy, int itemCount, VisualPlan.Style style,
			String targetAspect) {
	}

	// ---- API101-08 prepare ----

	public Mono<Outcome> prepare(ServerWebExchange exchange, Caller caller, PrepareCommand command) {
		String hash = command.requestHash();
		return plans.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
				.flatMap(row -> drafts.loadOwned(row.draftId().toString(), caller.accountId()).then(replay(row, hash)))
				.switchIfEmpty(Mono.defer(() -> {
					requireWrites();
					return resolve(caller, command).flatMap(resolved -> prepareRow(caller, command, resolved, hash))
							.flatMap(prepared -> runModel(exchange, caller, prepared));
				}));
	}

	/** 生成前的解析与缺省推荐（§6.5）；非法输入在此 400，不占位、不调模型。全部为响应式读取。 */
	private Mono<Resolved> resolve(Caller caller, PrepareCommand command) {
		return contexts.loadOwnedDraftContext(command.draftId().toString(), caller)
				.flatMap(context -> Mono
						.zip(plans.hasConfirmedStoryPlan(caller.accountId()), loadSourceDocument(caller, command))
						.map(tuple -> resolveWith(context, caller, command, tuple.getT1(), tuple.getT2())));
	}

	private Resolved resolveWith(DraftContext context, Caller caller, PrepareCommand command, boolean hasStoryHistory,
			SourceDocument document) {
		CreationDraft draft = context.draft();
		if (draft.status() == com.grassland.intelligence.creationassistant.DraftStatus.ARCHIVED)
			throw new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "归档草稿只读");
		if (draft.version() != command.expectedDraftVersion()) {
			throw new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿版本已变化，请刷新后重试");
		}
		RecipeDefinition recipe = resolveRecipe(command.recipeId(), command.recipeVersion(), draft);
		if ("article-format".equals(recipe.id()))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "原稿排版不支持视觉策划");
		if (!document.normalizedMarkdown().equals(context.lfContent()))
			throw new IntelligenceException(409, "STUDIO_PLAN_STALE", "请按当前正文重新冻结来源");
		if (command.sourceContentHash() == null || !command.sourceContentHash().equals(document.contentHash())) {
			throw new IntelligenceException(409, "STUDIO_PLAN_STALE", "来源正文已变化，请重新核对");
		}
		List<SourceDocument.Block> blocks = selectBlocks(document, command.selectedBlockIds());
		int itemCount = resolveItemCount(command.itemCount(), recipe, blocks);
		String strategy = resolveStrategy(command.strategy(), recipe, hasStoryHistory, context, blocks);
		VisualPlan.Style style = resolveStyle(command.styleId(), command.layoutId(), command.paletteId());
		String targetAspect = resolveAspect(command.targetAspect() != null
				? command.targetAspect()
				: "douyin".equals(draft.platform()) ? "9:16" : "xiaohongshu".equals(draft.platform()) ? "3:4" : null,
				recipe);
		return new Resolved(recipe, context, document, blocks, strategy, itemCount, style, targetAspect);
	}

	private Mono<SourceDocument> loadSourceDocument(Caller caller, PrepareCommand command) {
		return sources.findById(command.sourceDocumentId()).filter(
				found -> caller.accountId().equals(found.ownerAccountId()) && command.draftId().equals(found.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源文档不属于当前草稿")));
	}

	private record Prepared(VisualPlan.PlanRow row, Resolved resolved, String systemPrompt, String userPrompt) {
	}

	private Mono<Prepared> prepareRow(Caller caller, PrepareCommand command, Resolved resolved, String hash) {
		EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
		if (crypto == null) {
			// §7.2：没有 KEK 时拒绝需要加密记录的制作动作（fail-closed，不落明文 prompt）。
			return Mono.error(new IntelligenceException(503, "STUDIO_DEPENDENCY_UNAVAILABLE",
					"计划受保护记录不可用：未配置 CRYPTO_KEK_BASE64"));
		}
		String platformLabel = VisualPlanPrompts.platformLabel(resolved.context().draft().platform());
		String strategyText = VisualPlanPrompts.strategyText(resolved.strategy());
		boolean answerMode = resolved.context().draft().contentMode() != null
				&& "answer".equals(resolved.context().draft().contentMode().db());
		// C101-14：按 recipe 分派——文章配图／封面走 ArticleVisualPlanAdapter 模板；图卡不变。
		String systemPrompt = ArticleVisualPlanAdapter.isArticleVisuals(resolved.recipe().id())
				|| ArticleVisualPlanAdapter.isCoverOnly(resolved.recipe().id())
						? ArticleVisualPlanAdapter.systemPrompt(resolved.recipe(), platformLabel, strategyText,
								resolved.itemCount(), answerMode)
						: VisualPlanPrompts.system(platformLabel, resolved.recipe().label(), strategyText,
								styleText(resolved.style().styleId()), layoutText(resolved.style().layoutId()),
								paletteText(resolved.style().paletteId()), resolved.itemCount(),
								allowedRoles(resolved.recipe()));
		String upstream = ArticleVisualPlanAdapter.isArticleVisuals(resolved.recipe().id())
				|| ArticleVisualPlanAdapter.isCoverOnly(resolved.recipe().id())
						? VisualPlanPrompts.ARTICLE_UPSTREAM_VERSION
						: VisualPlanPrompts.UPSTREAM_VERSION;
		String userPrompt = VisualPlanPrompts.user(resolved.blocks().stream()
				.map(block -> Map.<String, Object>of("id", block.id(), "kind", block.kind(), "text", block.text()))
				.toList());
		String finalPrompt = systemPrompt + "\n\n---\n\n" + userPrompt;
		String promptCiphertext = crypto.encrypt(finalPrompt);
		String promptHash = PlanJson.sha256(finalPrompt);
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("strategy", resolved.strategy());
		snapshot.put("itemCount", resolved.itemCount());
		snapshot.put("targetAspect", resolved.targetAspect());
		snapshot.put("style", resolved.style().toMap());
		snapshot.put("platform", resolved.context().draft().platform());
		snapshot.put("contentForm", resolved.context().draft().contentForm());
		snapshot.put("answerMode", answerMode);
		snapshot.put("selectedBlockIds", resolved.blocks().stream().map(SourceDocument.Block::id).toList());
		OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
		var row = new VisualPlan.PlanRow(UUID.randomUUID(), caller.accountId(), command.draftId(),
				command.requestId().toString(), hash, command.sourceDocumentId(), command.sourceContentHash(),
				resolved.context().draft().version(), resolved.context().baseContentHash(), command.recipeId(),
				resolved.recipe().version(), upstream, PlanJson.json(snapshot), promptCiphertext, promptHash,
				"preparing", 0, null, null, null, null, null, null, null, now, now);
		return plans.insertPlaceholder(row).flatMap(inserted -> inserted
				? Mono.just(new Prepared(row, resolved, systemPrompt, userPrompt))
				: plans.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString()).flatMap(
						existing -> replay(existing, hash).map(ignored -> new Prepared(existing, null, null, null))));
	}

	private Mono<Outcome> runModel(ServerWebExchange exchange, Caller caller, Prepared prepared) {
		if (prepared.resolved() == null) {
			// 占位竞争失败：并发请求已落库，按重放读取（不再次调模型）。
			return replay(prepared.row(), prepared.row().requestHash());
		}
		List<ChatMessage> messages = List.of(ChatMessage.system(prepared.systemPrompt()),
				ChatMessage.user(prepared.userPrompt()));
		return Mono
				.defer(() -> contexts.executeText(exchange, caller, prepared.resolved().context(), messages, MAX_TOKENS,
						"social-card-series".equals(prepared.resolved().recipe().id())
								? CreditFeature.CARD_SERIES_PLAN
								: CreditFeature.CREATION_ASSISTANT,
						MODEL_TIMEOUT, (runId, actual) -> {
							String prompt = PlanJson.json(actual);
							return plans.capturePrompt(prepared.row().id(), runId,
									encryptionProvider.getIfAvailable().encrypt(prompt), PlanJson.sha256(prompt));
						}, completion -> parseModelDocument(prepared.resolved(), completion.content())))
				.flatMap(traced -> {
					VisualPlan.Document document = traced.value();
					String documentJson = PlanJson.json(document.toMap());
					String documentHash = PlanJson.sha256(documentJson);
					return plans
							.completeReady(prepared.row().id(), traced.runId(), documentJson, documentHash,
									prepared.row().promptCiphertext(), prepared.row().promptHash())
							.then(plans.findById(prepared.row().id()))
							.map(row -> new Outcome(row, false, document.toMap(), false));
				}).onErrorResume(error -> {
					IntelligenceException failure = error instanceof IntelligenceException original
							? original
							: new IntelligenceException(503, "STUDIO_PROVIDER_FAILED", "模型暂不可用，请稍后重试");
					if (!(error instanceof IntelligenceException)) {
						org.slf4j.LoggerFactory.getLogger(VisualPlanBuildService.class).warn("视觉计划生成失败 planId={}",
								prepared.row().id(), error);
					}
					if ("STUDIO_INVALID_PLAN".equals(failure.code())) {
						// 模型输出违约（确定性失败）：保留 run 与失败记录，200 返回 failed 计划。
						return plans.completeInvalid(prepared.row().id(), null, "STUDIO_INVALID_PLAN")
								.then(plans.findById(prepared.row().id()))
								.map(row -> new Outcome(row, false, null, false));
					}
					return plans
							.completeFailed(prepared.row().id(), null,
									failure.code() == null ? "STUDIO_PROVIDER_FAILED" : failure.code())
							.then(Mono.error(failure));
				});
	}

	// ---- API101-09 读取（历史 revision 只读） ----

	public Mono<Outcome> loadOwned(UUID id, Caller caller, Integer revision) {
		return loadOwnedRow(id, caller).flatMap(row -> plans.expirePreparing(id).then(plans.findById(id)))
				.flatMap(row -> {
					if (revision != null && (revision < 1 || revision > row.currentRevision())) {
						return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划版本不存在"));
					}
					int target = revision == null ? row.currentRevision() : revision;
					return withDocument(row, target);
				});
	}

	Mono<Outcome> withDocument(VisualPlan.PlanRow row, int revision) {
		Mono<Map<String, Object>> document = revision < 1
				? Mono.just(Map.of())
				: plans.findRevision(row.id(), revision).map(rev -> PlanJson.readJson(rev.documentJson()));
		return Mono.zip(document, staleFlag(row))
				.map(tuple -> new Outcome(revision == row.currentRevision() ? row : refresh(row, revision),
						"preparing".equals(row.status()), tuple.getT1(), tuple.getT2()));
	}

	/** 与当前草稿 baseContentHash 比对（§6.5 来源选择：正文漂移 → stale，不挪用旧块 ID）。 */
	private Mono<Boolean> staleFlag(VisualPlan.PlanRow row) {
		return drafts.loadOwned(row.draftId().toString(), row.ownerAccountId())
				.map(draft -> !CreationStudioContextService.computeBaseContentHash(draft).equals(row.baseContentHash()))
				.onErrorResume(error -> Mono.just(true)).defaultIfEmpty(true);
	}

	void requireWrites() {
		if (!properties.isWritesEnabled())
			throw new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放");
	}

	Mono<SourceDocument> loadPlanSource(VisualPlan.PlanRow row) {
		return sources.findById(row.sourceDocumentId())
				.filter(document -> row.ownerAccountId().equals(document.ownerAccountId())
						&& row.draftId().equals(document.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源文档不属于当前计划")));
	}

	public Mono<VisualPlan.PlanRow> loadOwnedRow(UUID id, Caller caller) {
		return plans.findById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在")))
				.flatMap(row -> drafts.loadOwned(row.draftId().toString(), caller.accountId()).then(loadPlanSource(row))
						.thenReturn(row));
	}

	// ---- 模型输出 → 受控 PlanDocument ----

	/**
	 * 严格解析（TC101-022）：根与项均拒未知字段（身份／排版字段不由模型提供）；假 blockId、 非逐字 criticalText、错角色、数量不符
	 * → STUDIO_INVALID_PLAN（计划 failed，不做第二次 隐形修复调用）。itemId／cardId／position 由服务端铸造。
	 */
	VisualPlan.Document parseModelDocument(Resolved resolved, String content) {
		try {
			JsonNode root = MAPPER.readTree(stripCodeFence(content));
			rejectUnknown(root, Set.of("items", "explanation"), "模型输出");
			JsonNode itemsNode = root.path("items");
			if (!itemsNode.isArray() || itemsNode.isEmpty()) {
				throw new IllegalArgumentException("模型未返回计划项");
			}
			if (itemsNode.size() != resolved.itemCount()) {
				throw new IllegalArgumentException("计划项数量与要求不符");
			}
			Set<String> selectedIds = new LinkedHashSet<>();
			Map<String, SourceDocument.Block> byId = new LinkedHashMap<>();
			for (var block : resolved.blocks()) {
				selectedIds.add(block.id());
				byId.put(block.id(), block);
			}
			List<VisualPlan.Item> items = new ArrayList<>();
			Set<String> itemIds = new LinkedHashSet<>();
			Set<String> cardIds = new LinkedHashSet<>();
			for (int index = 0; index < itemsNode.size(); index++) {
				items.add(parseModelItem(itemsNode.get(index), index, resolved, selectedIds, byId, itemIds, cardIds));
			}
			List<String> uncovered = uncoveredBlockIds(selectedIds, items);
			String explanation = boundedText(root.path("explanation"), MAX_EXPLANATION, "explanation");
			return new VisualPlan.Document(resolved.recipe().id(), resolved.recipe().version(), resolved.strategy(),
					resolved.style(), List.copyOf(items), explanation == null ? "" : explanation, uncovered);
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw new IntelligenceException(502, "STUDIO_INVALID_PLAN", "模型返回不合法计划");
		}
	}

	private VisualPlan.Item parseModelItem(JsonNode node, int index, Resolved resolved, Set<String> selectedIds,
			Map<String, SourceDocument.Block> byId, Set<String> itemIds, Set<String> cardIds) {
		// 模型只允许输出内容字段（C101-14 起 article-visuals 另允许 afterBlockId）；
		// itemId／cardId／position／layoutId／targetAspect 属于服务端职责，出现即为未知字段。
		Set<String> allowedFields = ArticleVisualPlanAdapter.isArticleVisuals(resolved.recipe().id())
				? ArticleVisualPlanAdapter.modelItemFields(resolved.recipe().id())
				: Set.of("role", "title", "bullets", "criticalText", "illustration", "caption", "purpose",
						"sourceBlockIds");
		rejectUnknown(node, allowedFields, "计划项");
		String role = node.path("role").asText();
		if ("cover".equals(role)) {
			if (index != 0) {
				throw new IllegalArgumentException("封面只能是第 1 项");
			}
		} else if (!allowedRoles(resolved.recipe()).contains(role) || index == 0) {
			throw new IllegalArgumentException("计划项角色不合法：" + role);
		}
		String title = requireBounded(node.path("title"), MAX_TITLE, "title");
		if (!node.path("bullets").isArray() || !node.path("criticalText").isArray())
			throw new IllegalArgumentException("bullets/criticalText 必须是数组");
		List<String> bullets = new ArrayList<>();
		for (JsonNode bullet : node.path("bullets")) {
			if (!bullet.isTextual() || bullet.asText().isBlank()) {
				throw new IllegalArgumentException("bullets 元素必须是非空字符串");
			}
			if (bullet.asText().codePointCount(0, bullet.asText().length()) > MAX_BULLET_LENGTH) {
				throw new IllegalArgumentException("单条要点过长");
			}
			bullets.add(bullet.asText());
			if (bullets.size() > MAX_BULLETS) {
				throw new IllegalArgumentException("bullets 超过 " + MAX_BULLETS + " 条");
			}
		}
		String illustration = requireBounded(node.path("illustration"), MAX_ILLUSTRATION, "illustration");
		List<String> sourceBlockIds = parseSourceBlockIds(node.path("sourceBlockIds"), selectedIds);
		StringBuilder referenced = new StringBuilder();
		for (String id : sourceBlockIds) {
			referenced.append(byId.get(id).text()).append('\n');
		}
		List<String> criticalText = new ArrayList<>();
		for (JsonNode critical : node.path("criticalText")) {
			if (!critical.isTextual() || critical.asText().isBlank())
				throw new IllegalArgumentException("关键文字必须是非空字符串");
			String text = critical.asText();
			if (text.codePointCount(0, text.length()) > MAX_CRITICAL_LENGTH) {
				throw new IllegalArgumentException("关键文字过长");
			}
			if (sourceBlockIds.stream().noneMatch(id -> byId.get(id).text().contains(text))) {
				throw new IllegalArgumentException("关键文字未逐字出现在所引来源块中");
			}
			criticalText.add(text);
			if (criticalText.size() > MAX_CRITICAL) {
				throw new IllegalArgumentException("criticalText 超过 " + MAX_CRITICAL + " 条");
			}
		}
		// C101-14：article-visuals 插图段落定位（afterBlockId）；其余模板禁止携带。
		String afterBlockId = ArticleVisualPlanAdapter.parseAfterBlockId(resolved.recipe().id(), role, node,
				selectedIds);
		String itemId = VisualPlanPrompts.mintId();
		String cardId = VisualPlanPrompts.mintId();
		if (!itemIds.add(itemId) || !cardIds.add(cardId)) {
			throw new IllegalArgumentException("计划项身份重复");
		}
		return new VisualPlan.Item(itemId, cardId, index + 1, role, title, List.copyOf(bullets),
				boundedText(node.path("caption"), 500, "caption"), boundedText(node.path("purpose"), 200, "purpose"),
				illustration, List.copyOf(sourceBlockIds), List.copyOf(criticalText), resolved.style().layoutId(),
				resolved.targetAspect(), afterBlockId);
	}

	private static List<String> parseSourceBlockIds(JsonNode node, Set<String> selectedIds) {
		if (!node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException("计划项必须引用来源块");
		}
		List<String> ids = new ArrayList<>();
		for (JsonNode blockId : node) {
			if (!blockId.isTextual())
				throw new IllegalArgumentException("来源块 ID 必须是字符串");
			String id = blockId.asText();
			if (!selectedIds.contains(id)) {
				throw new IllegalArgumentException("来源块不在本次选择内：" + id);
			}
			if (ids.contains(id))
				throw new IllegalArgumentException("来源块 ID 不允许重复");
			ids.add(id);
		}
		return ids;
	}

	/** appendRevision 后行内容已知（指针推进、确认清空），避免与并发读竞争再读一次。 */
	static VisualPlan.PlanRow refresh(VisualPlan.PlanRow row, int newRevision) {
		return new VisualPlan.PlanRow(row.id(), row.ownerAccountId(), row.draftId(), row.requestId(), row.requestHash(),
				row.sourceDocumentId(), row.sourceContentHash(), row.baseDraftVersion(), row.baseContentHash(),
				row.recipeId(), row.recipeVersion(), row.upstreamCommit(), row.inputSnapshotJson(),
				row.promptCiphertext(), row.promptHash(), row.status(), newRevision, null, null, null, null, null,
				row.runId(), row.errorCode(), row.createdAt(), OffsetDateTime.now(ZoneOffset.UTC));
	}

	static List<String> uncoveredBlockIds(Set<String> selectedIds, List<VisualPlan.Item> items) {
		Set<String> referenced = new LinkedHashSet<>();
		for (var item : items) {
			referenced.addAll(item.sourceBlockIds());
		}
		List<String> uncovered = new ArrayList<>();
		for (String id : selectedIds) {
			if (!referenced.contains(id)) {
				uncovered.add(id);
			}
		}
		return List.copyOf(uncovered);
	}

	private static void rejectUnknown(JsonNode node, Set<String> allowed, String what) {
		if (!node.isObject()) {
			throw new IllegalArgumentException(what + " 必须是对象");
		}
		var fields = node.fieldNames();
		while (fields.hasNext()) {
			String field = fields.next();
			if (!allowed.contains(field)) {
				throw new IllegalArgumentException(what + " 含未知字段：" + field);
			}
		}
	}

	private static String requireBounded(JsonNode node, int max, String what) {
		if (!node.isTextual() || node.asText().isBlank()) {
			throw new IllegalArgumentException(what + " 不能为空");
		}
		String text = node.asText();
		if (text.codePointCount(0, text.length()) > max) {
			throw new IllegalArgumentException(what + " 过长");
		}
		return text;
	}

	private static String boundedText(JsonNode node, int max, String what) {
		if (node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (!node.isTextual()) {
			throw new IllegalArgumentException(what + " 必须是字符串");
		}
		String text = node.asText();
		if (text.codePointCount(0, text.length()) > max) {
			throw new IllegalArgumentException(what + " 过长");
		}
		return text;
	}

	private static String stripCodeFence(String content) {
		String text = content == null ? "" : content.trim();
		if (text.startsWith("```")) {
			int firstNewLine = text.indexOf('\n');
			if (firstNewLine > 0) {
				text = text.substring(firstNewLine + 1);
			}
			if (text.endsWith("```")) {
				text = text.substring(0, text.length() - 3);
			}
		}
		return text.trim();
	}

	private Mono<Outcome> replay(VisualPlan.PlanRow row, String hash) {
		if (!row.requestHash().equals(hash)) {
			return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "同一 requestId 已用于不同请求"));
		}
		if ("preparing".equals(row.status())) {
			return plans.expirePreparing(row.id()).then(plans.findById(row.id()))
					.flatMap(saved -> withDocument(saved, saved.currentRevision()));
		}
		return withDocument(row, row.currentRevision());
	}
}
