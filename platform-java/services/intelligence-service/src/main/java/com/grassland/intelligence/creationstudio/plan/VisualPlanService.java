package com.grassland.intelligence.creationstudio.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog.RecipeDefinition;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.CreationStudioContextService.DraftContext;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.CreationVisualPresetCatalog;
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
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-05（API101-08～11）：服务端视觉计划、修订与确认。
 *
 * <ul>
 * <li>prepare：先落 preparing 占位（同键幂等），onPrepared 先持久化 runId 再请求模型； 最终组装 prompt
 * 以信封加密完整留档（无 KEK → 503 fail-closed，不落明文）。</li>
 * <li>模型输出严格解析：根／项拒未知字段，假 blockId、非逐字 criticalText、错角色、数量不符 →
 * failed（STUDIO_INVALID_PLAN），不做第二次隐形修复调用；itemId／cardId／position 由服务端铸造。</li>
 * <li>PATCH：客户端提交完整 document，逐项校验后追加不可变 revision 并推进指针、清确认；
 * 来源、模板与策略不允许切换（换策略须新建计划）；position 按提交顺序重排，身份不变。</li>
 * <li>confirm：只确认当前 revision（行锁 + 草稿版本／来源 hash／正文 hash 核验），记录确认时
 * draftVersion、contentHash 与 actor；正文漂移 → STUDIO_PLAN_STALE，须重建或确认新计划。</li>
 * </ul>
 *
 * <p>
 * 锁顺序遵守 §7.3.2（draft → plan → apply）；本卡确认／PATCH 只锁 plan 行，草稿侧以
 * 版本核验代替写锁（本卡不写草稿）。
 */
@Service
public class VisualPlanService {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	static final Duration MODEL_TIMEOUT = Duration.ofSeconds(90);
	private static final int MAX_SELECTED_CODE_POINTS = 8_000;
	private static final int MAX_TITLE = 128;
	private static final int MAX_BULLETS = 5;
	private static final int MAX_BULLET_LENGTH = 64;
	private static final int MAX_TEXT_FIELD = 300;
	private static final int MAX_ILLUSTRATION = 600;
	private static final int MAX_CRITICAL = 10;
	private static final int MAX_CRITICAL_LENGTH = 128;
	private static final int MAX_EXPLANATION = 500;
	private static final int MAX_TOKENS = 8192;
	static final Set<String> ASPECTS = Set.of("3:4", "9:16", "1:1", "16:9", "2.35:1");
	private static final Set<String> STRATEGIES = Set.of("story", "information", "visual");

	private final VisualPlanRepository plans;
	private final CreationStudioContextService contexts;
	private final SourceDocumentRepository sources;
	private final CreationStudioProperties properties;
	private final FrozenTextExecutionService frozenText;
	private final CreationDraftService drafts;
	private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
	private final TransactionalOperator transactions;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public VisualPlanService(VisualPlanRepository plans, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties,
			FrozenTextExecutionService frozenText, CreationDraftService drafts,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, TransactionalOperator transactions) {
		this(plans, contexts, sources, properties, frozenText, drafts, encryptionProvider, transactions,
				Clock.systemUTC());
	}

	VisualPlanService(VisualPlanRepository plans, CreationStudioContextService contexts,
			SourceDocumentRepository sources, CreationStudioProperties properties,
			FrozenTextExecutionService frozenText, CreationDraftService drafts,
			ObjectProvider<EnvelopeEncryption> encryptionProvider, TransactionalOperator transactions, Clock clock) {
		this.plans = plans;
		this.contexts = contexts;
		this.sources = sources;
		this.properties = properties;
		this.frozenText = frozenText;
		this.drafts = drafts;
		this.encryptionProvider = encryptionProvider;
		this.transactions = transactions;
		this.clock = clock;
	}

	// ---- 命令 ----

	/** API101-08 请求（wire 解析在 Controller）；style 三段各自可空（部分 VisualStyle）。 */
	public record PrepareCommand(UUID requestId, UUID draftId, int expectedDraftVersion, String recipeId,
			String recipeVersion, UUID sourceDocumentId, String sourceContentHash, List<String> selectedBlockIds,
			String strategy, Integer itemCount, String styleId, String layoutId, String paletteId,
			String targetAspect) {

		String requestHash() {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("draftId", draftId.toString());
			canonical.put("expectedDraftVersion", expectedDraftVersion);
			canonical.put("recipeId", recipeId);
			canonical.put("recipeVersion", recipeVersion);
			canonical.put("sourceDocumentId", sourceDocumentId.toString());
			canonical.put("sourceContentHash", sourceContentHash);
			canonical.put("selectedBlockIds", selectedBlockIds == null ? List.of() : selectedBlockIds);
			canonical.put("strategy", strategy == null ? "" : strategy);
			canonical.put("itemCount", itemCount == null ? 0 : itemCount);
			canonical.put("styleId", styleId == null ? "" : styleId);
			canonical.put("layoutId", layoutId == null ? "" : layoutId);
			canonical.put("paletteId", paletteId == null ? "" : paletteId);
			canonical.put("targetAspect", targetAspect == null ? "" : targetAspect);
			return hashCanonical(canonical);
		}
	}

	public record Outcome(VisualPlan.PlanRow plan, boolean preparing, Map<String, Object> document, boolean stale) {
	}

	public record ConfirmCommand(UUID requestId, UUID draftId, int expectedDraftVersion, int expectedRevision,
			String sourceContentHash) {
	}

	public record PatchCommand(UUID requestId, int expectedRevision, Map<String, Object> documentRaw) {
	}

	/** 解析产物：占位与模型调用所需的全部冻结输入。 */
	record Resolved(RecipeDefinition recipe, DraftContext context, SourceDocument document,
			List<SourceDocument.Block> blocks, String strategy, int itemCount, VisualPlan.Style style,
			String targetAspect) {
	}

	// ---- API101-08 prepare ----

	public Mono<Outcome> prepare(ServerWebExchange exchange, Caller caller, PrepareCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		String hash = command.requestHash();
		return plans.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
				.flatMap(row -> replay(row, hash))
				.switchIfEmpty(Mono.defer(
						() -> resolve(caller, command).flatMap(resolved -> prepareRow(caller, command, resolved, hash))
								.flatMap(prepared -> runModel(exchange, caller, prepared))));
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
		if (draft.version() != command.expectedDraftVersion()) {
			throw new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿版本已变化，请刷新后重试");
		}
		RecipeDefinition recipe = resolveRecipe(command.recipeId(), command.recipeVersion(), draft);
		if (command.sourceContentHash() == null || !command.sourceContentHash().equals(document.contentHash())) {
			throw new IntelligenceException(409, "STUDIO_PLAN_STALE", "来源正文已变化，请重新核对");
		}
		List<SourceDocument.Block> blocks = selectBlocks(document, command.selectedBlockIds());
		int itemCount = resolveItemCount(command.itemCount(), recipe, blocks);
		String strategy = resolveStrategy(command.strategy(), recipe, hasStoryHistory, context, blocks);
		VisualPlan.Style style = resolveStyle(command.styleId(), command.layoutId(), command.paletteId());
		String targetAspect = resolveAspect(command.targetAspect(), recipe);
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
		String systemPrompt = VisualPlanPrompts.system(
				VisualPlanPrompts.platformLabel(resolved.context().draft().platform()), resolved.recipe().label(),
				VisualPlanPrompts.strategyText(resolved.strategy()), styleText(resolved.style().styleId()),
				layoutText(resolved.style().layoutId()), paletteText(resolved.style().paletteId()),
				resolved.itemCount(), allowedRoles(resolved.recipe()));
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
		snapshot.put("selectedBlockIds", resolved.blocks().stream().map(SourceDocument.Block::id).toList());
		OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
		var row = new VisualPlan.PlanRow(UUID.randomUUID(), caller.accountId(), command.draftId(),
				command.requestId().toString(), hash, command.sourceDocumentId(), command.sourceContentHash(),
				resolved.context().draft().version(), resolved.context().baseContentHash(), command.recipeId(),
				resolved.recipe().version(), VisualPlanPrompts.UPSTREAM_VERSION, PlanJson.json(snapshot),
				promptCiphertext, promptHash, "preparing", 0, null, null, null, null, null, null, null, now, now);
		return plans.insertPlaceholder(row)
				.flatMap(inserted -> inserted
						? Mono.just(new Prepared(row, resolved, systemPrompt, userPrompt))
						: plans.findByOwnerAndRequestId(caller.accountId(), command.requestId().toString())
								.map(existing -> new Prepared(existing, null, null, null)));
	}

	private Mono<Outcome> runModel(ServerWebExchange exchange, Caller caller, Prepared prepared) {
		if (prepared.resolved() == null) {
			// 占位竞争失败：并发请求已落库，按重放读取（不再次调模型）。
			return plans.findById(prepared.row().id()).map(row -> new Outcome(row, true, null, false));
		}
		List<ChatMessage> messages = List.of(ChatMessage.system(prepared.systemPrompt()),
				ChatMessage.user(prepared.userPrompt()));
		return Mono
				.defer(() -> frozenText.executeIndependentPrepared(exchange, caller, messages, MAX_TOKENS,
						CreditFeature.CARD_SERIES_PLAN, MODEL_TIMEOUT,
						runId -> plans.attachRun(prepared.row().id(), runId).then(),
						completion -> parseModelDocument(prepared.resolved(), completion.content())))
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
						org.slf4j.LoggerFactory.getLogger(VisualPlanService.class).warn("视觉计划生成失败 planId={}",
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
		return plans.findById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在"))).flatMap(row -> {
					if (revision != null && (revision < 1 || revision > row.currentRevision())) {
						return Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划版本不存在"));
					}
					int target = revision == null ? row.currentRevision() : revision;
					return withDocument(row, target);
				});
	}

	private Mono<Outcome> withDocument(VisualPlan.PlanRow row, int revision) {
		Mono<Map<String, Object>> document = revision < 1
				? Mono.just(Map.of())
				: plans.findRevision(row.id(), revision).map(rev -> PlanJson.readJson(rev.documentJson()));
		return Mono.zip(document, staleFlag(row)).map(tuple -> new Outcome(row, false, tuple.getT1(), tuple.getT2()));
	}

	/** 与当前草稿 baseContentHash 比对（§6.5 来源选择：正文漂移 → stale，不挪用旧块 ID）。 */
	private Mono<Boolean> staleFlag(VisualPlan.PlanRow row) {
		return drafts.loadOwned(row.draftId().toString(), row.ownerAccountId())
				.map(draft -> !CreationStudioContextService.computeBaseContentHash(draft).equals(row.baseContentHash()))
				.onErrorResume(error -> Mono.just(true)).defaultIfEmpty(true);
	}

	// ---- API101-10 PATCH ----

	public Mono<Outcome> patch(Caller caller, UUID id, PatchCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		// 整个「行锁 → 校验 → 追加 revision → 推进指针」在同一事务（§7.3.2 共享计划行锁；
		// CAS 失败时 appendRevision 抛错回滚 INSERT，不留孤儿 revision）。
		return plans.lockById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在"))).flatMap(row -> {
					if (!"ready".equals(row.status())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划当前状态不可编辑"));
					}
					if (row.currentRevision() != command.expectedRevision()) {
						return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划版本已变化，请刷新后重试"));
					}
					return patchLocked(row, command);
				}).as(transactions::transactional).onErrorMap(IllegalStateException.class,
						error -> new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划已被并发修改，请刷新后重试"));
	}

	private Mono<Outcome> patchLocked(VisualPlan.PlanRow row, PatchCommand command) {
		return loadPlanSource(row).map(document -> {
			Set<String> selected = selectedBlockIdsFromSnapshot(row, document);
			Map<String, SourceDocument.Block> byId = new LinkedHashMap<>();
			for (var block : document.blocks()) {
				byId.put(block.id(), block);
			}
			return buildDocument(row, command.documentRaw(), selected, byId);
		}).flatMap(document -> {
			String documentJson = PlanJson.json(document.toMap());
			String documentHash = PlanJson.sha256(documentJson);
			int next = row.currentRevision() + 1;
			return plans.appendRevision(row.id(), row.currentRevision(), next, documentJson, documentHash)
					.flatMap(updated -> updated > 0
							? withDocument(refresh(row, next), next)
							: Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划已被并发修改，请刷新后重试")));
		});
	}

	/** appendRevision 后行内容已知（指针推进、确认清空），避免与并发读竞争再读一次。 */
	private static VisualPlan.PlanRow refresh(VisualPlan.PlanRow row, int newRevision) {
		return new VisualPlan.PlanRow(row.id(), row.ownerAccountId(), row.draftId(), row.requestId(), row.requestHash(),
				row.sourceDocumentId(), row.sourceContentHash(), row.baseDraftVersion(), row.baseContentHash(),
				row.recipeId(), row.recipeVersion(), row.upstreamCommit(), row.inputSnapshotJson(),
				row.promptCiphertext(), row.promptHash(), row.status(), newRevision, null, null, null, null, null,
				row.runId(), row.errorCode(), row.createdAt(), OffsetDateTime.now(ZoneOffset.UTC));
	}

	private Mono<SourceDocument> loadPlanSource(VisualPlan.PlanRow row) {
		return sources.findById(row.sourceDocumentId())
				.filter(document -> row.ownerAccountId().equals(document.ownerAccountId())
						&& row.draftId().equals(document.draftId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "来源文档不属于当前计划")));
	}

	// ---- API101-11 confirm ----

	public Mono<Outcome> confirm(Caller caller, UUID id, ConfirmCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		// 与 PATCH 共享计划行锁（§7.3.2）：行锁 → 版本／hash 核验 → 写确认元数据一个事务，
		// confirm 与 PATCH 并发时按行锁串行，无半更新确认（TC101-024）。
		return plans.lockById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在"))).flatMap(row -> {
					if (!row.draftId().equals(command.draftId())) {
						return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "确认请求与计划不属于同一草稿"));
					}
					if (!"ready".equals(row.status())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划未就绪，不能确认"));
					}
					if (row.currentRevision() != command.expectedRevision()) {
						return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划版本已变化，请刷新后重试"));
					}
					if (command.sourceContentHash() == null
							|| !command.sourceContentHash().equals(row.sourceContentHash())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "来源正文已变化，请重新核对"));
					}
					boolean alreadyConfirmed = row.confirmedRevision() != null
							&& row.confirmedRevision() == command.expectedRevision();
					return contexts.loadOwnedDraftContext(row.draftId().toString(), caller).flatMap(context -> {
						if (context.draft().version() != command.expectedDraftVersion()) {
							return Mono
									.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿版本已变化，请刷新后重试"));
						}
						if (!context.baseContentHash().equals(row.baseContentHash())) {
							return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "正文已变化，请重建或确认新计划"));
						}
						if (alreadyConfirmed) {
							// 同 revision 重复确认：核验通过即幂等返回，不重复写确认元数据。
							return withDocument(row, row.currentRevision());
						}
						return plans
								.confirm(row.id(), command.expectedRevision(), context.draft().version(),
										command.sourceContentHash(), caller.accountId())
								.flatMap(
										updated -> updated > 0
												? withDocument(confirmed(row, command.expectedRevision(),
														context.draft().version()), row.currentRevision())
												: Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT",
														"计划已被并发修改，请刷新后重试")));
					});
				}).as(transactions::transactional);
	}

	private static VisualPlan.PlanRow confirmed(VisualPlan.PlanRow row, int revision, int draftVersion) {
		return new VisualPlan.PlanRow(row.id(), row.ownerAccountId(), row.draftId(), row.requestId(), row.requestHash(),
				row.sourceDocumentId(), row.sourceContentHash(), row.baseDraftVersion(), row.baseContentHash(),
				row.recipeId(), row.recipeVersion(), row.upstreamCommit(), row.inputSnapshotJson(),
				row.promptCiphertext(), row.promptHash(), row.status(), row.currentRevision(), revision, draftVersion,
				row.sourceContentHash(), OffsetDateTime.now(ZoneOffset.UTC), row.ownerAccountId(), row.runId(),
				row.errorCode(), row.createdAt(), OffsetDateTime.now(ZoneOffset.UTC));
	}

	public Mono<VisualPlan.PlanRow> loadOwnedRow(UUID id, Caller caller) {
		return plans.findById(id).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在")));
	}

	// ---- 解析与缺省（§6.5 推荐缺省） ----

	/** 模板解析：未知／版本不符／未开放／平台形式不适用 → 400（不回退其他模板）。 */
	static RecipeDefinition resolveRecipe(String recipeId, String recipeVersion, CreationDraft draft) {
		RecipeDefinition recipe = CreationRecipeCatalog.byId(recipeId);
		if (recipe == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知模板：" + recipeId);
		}
		if (recipeVersion != null && !recipeVersion.equals(recipe.version())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT",
					"模板 " + recipeId + " 不存在版本 " + recipeVersion + "（当前 " + recipe.version() + "）");
		}
		if (!recipe.enabled()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」暂未开放");
		}
		if (draft.platform() != null && !recipe.platformIds().contains(draft.platform())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」不适用于当前平台");
		}
		if (draft.contentForm() != null && !recipe.contentForms().contains(draft.contentForm())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」不适用于当前内容形式");
		}
		return recipe;
	}

	static List<SourceDocument.Block> selectBlocks(SourceDocument document, List<String> selectedBlockIds) {
		Map<String, SourceDocument.Block> byId = new LinkedHashMap<>();
		for (var block : document.blocks()) {
			byId.put(block.id(), block);
		}
		List<SourceDocument.Block> selected;
		if (selectedBlockIds == null || selectedBlockIds.isEmpty()) {
			selected = document.blocks();
		} else {
			selected = new ArrayList<>();
			for (String blockId : selectedBlockIds) {
				var block = byId.get(blockId);
				if (block == null) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "选择的来源块不存在：" + blockId);
				}
				selected.add(block);
			}
		}
		int total = 0;
		for (var block : selected) {
			total += block.text().codePointCount(0, block.text().length());
		}
		if (total > MAX_SELECTED_CODE_POINTS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
					"选择范围合计超过 " + MAX_SELECTED_CODE_POINTS + " 字符，请缩小处理范围");
		}
		return selected;
	}

	/**
	 * §6.5 推荐缺省：图卡 min(6,max(2,1+ceil(非空块/2)))；文章配图 min(6,max(1,标题块+1))； 封面
	 * 1；均不超模板上下限。显式数量在上下限内时原样采用（不覆盖用户选择）。
	 */
	static int resolveItemCount(Integer explicit, RecipeDefinition recipe, List<SourceDocument.Block> blocks) {
		if (explicit != null) {
			if (explicit < recipe.minItems() || explicit > recipe.maxItems()) {
				throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
						"项目数须在 " + recipe.minItems() + "～" + recipe.maxItems() + " 之间（模板上限）");
			}
			return explicit;
		}
		int recommended = switch (recipe.id()) {
			case "social-card-series" -> {
				int nonEmpty = 0;
				for (var block : blocks) {
					if (!block.text().isBlank()) {
						nonEmpty++;
					}
				}
				yield Math.min(6, Math.max(2, 1 + ceilHalf(nonEmpty)));
			}
			case "article-visuals" -> {
				int headings = 0;
				for (var block : blocks) {
					if ("heading".equals(block.kind())) {
						headings++;
					}
				}
				yield Math.min(6, Math.max(1, headings + 1));
			}
			case "cover-only" -> 1;
			default -> Math.max(1, recipe.minItems());
		};
		return Math.min(Math.max(recommended, recipe.minItems()), recipe.maxItems());
	}

	private static int ceilHalf(int value) {
		return (value + 1) / 2;
	}

	/**
	 * §6.5 推荐缺省：story 须本人有确认过的体验型（story）计划经历；visual 须 ≥3 既有素材 且所选文字 ≤1,200 字；其余
	 * information。显式策略必须在模板支持集内。
	 */
	static String resolveStrategy(String explicit, RecipeDefinition recipe, boolean hasStoryHistory,
			DraftContext context, List<SourceDocument.Block> blocks) {
		if (explicit != null) {
			if (!STRATEGIES.contains(explicit) || !recipe.supportedStrategies().contains(explicit)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板不支持该策划策略");
			}
			return explicit;
		}
		if (recipe.supportedStrategies().contains("story") && hasStoryHistory) {
			return "story";
		}
		int total = 0;
		for (var block : blocks) {
			total += block.text().codePointCount(0, block.text().length());
		}
		if (recipe.supportedStrategies().contains("visual") && context.draft().resultAssetIds() != null
				&& context.draft().resultAssetIds().size() >= 3 && total <= 1_200) {
			return "visual";
		}
		return recipe.supportedStrategies().contains("information")
				? "information"
				: recipe.supportedStrategies().get(0);
	}

	/** 部分样式补全：缺省取目录内首个完整预设（种草清单），未知 ID 一律 400（不猜测）。 */
	static VisualPlan.Style resolveStyle(String styleId, String layoutId, String paletteId) {
		var preset = CreationVisualPresetCatalog.preset("xhs-cute-list");
		String resolvedStyle = styleId != null ? styleId : preset.styleId();
		String resolvedLayout = layoutId != null ? layoutId : preset.layoutId();
		String resolvedPalette = paletteId != null ? paletteId : preset.paletteId();
		if (CreationVisualPresetCatalog.style(resolvedStyle) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知视觉风格：" + resolvedStyle);
		}
		if (CreationVisualPresetCatalog.layout(resolvedLayout) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知画面布局：" + resolvedLayout);
		}
		if (CreationVisualPresetCatalog.palette(resolvedPalette) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知配色：" + resolvedPalette);
		}
		return new VisualPlan.Style(resolvedStyle, resolvedLayout, resolvedPalette);
	}

	static String resolveAspect(String explicit, RecipeDefinition recipe) {
		if (explicit == null) {
			return recipe.defaultAspect() == null ? "3:4" : recipe.defaultAspect();
		}
		if (!ASPECTS.contains(explicit)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "目标画幅不合法：" + explicit);
		}
		return explicit;
	}

	static List<String> allowedRoles(RecipeDefinition recipe) {
		return switch (recipe.id()) {
			case "social-card-series" -> List.of("cover", "content", "summary");
			case "article-visuals" -> List.of("cover", "illustration");
			default -> List.of("cover");
		};
	}

	private static String styleText(String styleId) {
		var style = CreationVisualPresetCatalog.style(styleId);
		return style == null ? styleId : style.label() + "（" + style.prompt() + "）";
	}

	private static String layoutText(String layoutId) {
		var layout = CreationVisualPresetCatalog.layout(layoutId);
		return layout == null ? layoutId : layout.label() + "（" + layout.prompt() + "）";
	}

	private static String paletteText(String paletteId) {
		var palette = CreationVisualPresetCatalog.palette(paletteId);
		return palette == null ? paletteId : palette.label() + "（" + palette.prompt() + "）";
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
		// 模型只允许输出内容字段；itemId／cardId／position／layoutId／targetAspect／placement
		// 属于服务端职责，出现即为未知字段（TC101-022 恶意字段）。
		rejectUnknown(node, Set.of("role", "title", "bullets", "criticalText", "illustration", "caption", "purpose",
				"sourceBlockIds"), "计划项");
		String role = node.path("role").asText();
		if ("cover".equals(role)) {
			if (index != 0) {
				throw new IllegalArgumentException("封面只能是第 1 项");
			}
		} else if (!allowedRoles(resolved.recipe()).contains(role) || index == 0) {
			throw new IllegalArgumentException("计划项角色不合法：" + role);
		}
		String title = requireBounded(node.path("title"), MAX_TITLE, "title");
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
			String text = critical.asText();
			if (text.codePointCount(0, text.length()) > MAX_CRITICAL_LENGTH) {
				throw new IllegalArgumentException("关键文字过长");
			}
			if (!referenced.toString().contains(text)) {
				throw new IllegalArgumentException("关键文字未逐字出现在所引来源块中");
			}
			criticalText.add(text);
			if (criticalText.size() > MAX_CRITICAL) {
				throw new IllegalArgumentException("criticalText 超过 " + MAX_CRITICAL + " 条");
			}
		}
		String itemId = VisualPlanPrompts.mintId();
		String cardId = VisualPlanPrompts.mintId();
		if (!itemIds.add(itemId) || !cardIds.add(cardId)) {
			throw new IllegalArgumentException("计划项身份重复");
		}
		return new VisualPlan.Item(itemId, cardId, index + 1, role, title, List.copyOf(bullets),
				boundedText(node.path("caption"), MAX_TEXT_FIELD, "caption"),
				boundedText(node.path("purpose"), MAX_TEXT_FIELD, "purpose"), illustration, List.copyOf(sourceBlockIds),
				List.copyOf(criticalText), resolved.style().layoutId(), resolved.targetAspect(), null);
	}

	private static List<String> parseSourceBlockIds(JsonNode node, Set<String> selectedIds) {
		if (!node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException("计划项必须引用来源块");
		}
		List<String> ids = new ArrayList<>();
		for (JsonNode blockId : node) {
			String id = blockId.asText();
			if (!selectedIds.contains(id)) {
				throw new IllegalArgumentException("来源块不在本次选择内：" + id);
			}
			if (!ids.contains(id)) {
				ids.add(id);
			}
		}
		return ids;
	}

	// ---- PATCH document → 受控 PlanDocument ----

	/**
	 * PATCH 客户端提交完整 document（§6.5 计划编辑）：来源、模板与策略不可切换（切换须新建 计划）；逐项校验后由服务端按提交顺序重排
	 * position（顺序变动不变身份）；itemId／cardId 身份保留客户端原值；首项必须仍是唯一封面（删除封面须先指定新合法封面）。
	 */
	VisualPlan.Document buildDocument(VisualPlan.PlanRow row, Map<String, Object> documentRaw, Set<String> selectedIds,
			Map<String, SourceDocument.Block> byId) {
		RecipeDefinition recipe = CreationRecipeCatalog.byId(row.recipeId());
		Map<String, Object> snapshot = PlanJson.readJson(row.inputSnapshotJson());
		if (documentRaw == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "document 不能为空");
		}
		rejectUnknownMap(documentRaw,
				Set.of("recipe", "strategy", "style", "items", "explanation", "uncoveredBlockIds"), "document");
		Object recipeRef = documentRaw.get("recipe");
		if (!(recipeRef instanceof Map<?, ?> recipeMap) || !row.recipeId().equals(recipeMap.get("id"))
				|| !row.recipeVersion().equals(recipeMap.get("version"))) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划编辑不能切换来源模板，请新建计划");
		}
		Object strategy = documentRaw.get("strategy");
		if (!String.valueOf(snapshot.get("strategy")).equals(strategy)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划编辑不能切换策略，请新建计划");
		}
		Object styleRaw = documentRaw.get("style");
		if (!(styleRaw instanceof Map<?, ?> styleMap)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "document.style 必须是对象");
		}
		VisualPlan.Style style = resolveStyle(textOrNull(styleMap.get("styleId"), "style.styleId"),
				textOrNull(styleMap.get("layoutId"), "style.layoutId"),
				textOrNull(styleMap.get("paletteId"), "style.paletteId"));
		Object itemsRaw = documentRaw.get("items");
		if (!(itemsRaw instanceof List<?> itemsList) || itemsList.isEmpty()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "document.items 必须是非空数组");
		}
		if (itemsList.size() < recipe.minItems() || itemsList.size() > recipe.maxItems()) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
					"项目数须在 " + recipe.minItems() + "～" + recipe.maxItems() + " 之间（模板上限）");
		}
		List<VisualPlan.Item> items = new ArrayList<>();
		Set<String> itemIds = new LinkedHashSet<>();
		Set<String> cardIds = new LinkedHashSet<>();
		for (int index = 0; index < itemsList.size(); index++) {
			if (!(itemsList.get(index) instanceof Map<?, ?> itemMap)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "items 元素必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) itemMap;
			items.add(parseWireItem(cast, index, recipe, style, selectedIds, byId, itemIds, cardIds));
		}
		long covers = items.stream().filter(item -> "cover".equals(item.role())).count();
		if (covers != 1 || !"cover".equals(items.get(0).role())) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划必须以唯一封面项开头，删除封面须先指定新封面");
		}
		List<String> uncovered = uncoveredBlockIds(selectedIds, items);
		Object explanation = documentRaw.get("explanation");
		if (explanation != null && !(explanation instanceof String explanationText)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "explanation 必须是字符串");
		}
		if (explanation instanceof String text && text.codePointCount(0, text.length()) > MAX_EXPLANATION) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "explanation 过长");
		}
		return new VisualPlan.Document(row.recipeId(), row.recipeVersion(), String.valueOf(snapshot.get("strategy")),
				style, List.copyOf(items), explanation == null ? "" : (String) explanation, uncovered);
	}

	private VisualPlan.Item parseWireItem(Map<String, Object> item, int index, RecipeDefinition recipe,
			VisualPlan.Style sharedStyle, Set<String> selectedIds, Map<String, SourceDocument.Block> byId,
			Set<String> itemIds, Set<String> cardIds) {
		rejectUnknownMap(item,
				Set.of("itemId", "cardId", "position", "role", "title", "bullets", "caption", "purpose", "illustration",
						"sourceBlockIds", "criticalText", "layoutId", "targetAspect", "placement"),
				"items[" + index + "]");
		String itemId = textOrNull(item.get("itemId"), "itemId");
		String cardId = textOrNull(item.get("cardId"), "cardId");
		if (itemId == null || cardId == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项身份（itemId/cardId）不能为空");
		}
		String role = textOrNull(item.get("role"), "role");
		if ("cover".equals(role)) {
			if (index != 0) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "封面只能是第 1 项");
			}
		} else if (!allowedRoles(recipe).contains(role) || index == 0) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项角色不合法：" + role);
		}
		String title = textOrNull(item.get("title"), "title");
		if (title == null || title.codePointCount(0, title.length()) > MAX_TITLE) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "标题为空或过长");
		}
		Object bulletsRaw = item.get("bullets");
		if (bulletsRaw != null && !(bulletsRaw instanceof List<?>)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "bullets 必须是数组");
		}
		List<String> bullets = new ArrayList<>();
		if (bulletsRaw instanceof List<?> bulletsList) {
			for (Object bullet : bulletsList) {
				if (!(bullet instanceof String text) || text.isBlank()
						|| text.codePointCount(0, text.length()) > MAX_BULLET_LENGTH) {
					throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "bullets 元素为空或过长");
				}
				bullets.add(text);
				if (bullets.size() > MAX_BULLETS) {
					throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "bullets 至多 " + MAX_BULLETS + " 条");
				}
			}
		}
		String illustration = textOrNull(item.get("illustration"), "illustration");
		if (illustration == null || illustration.isBlank()
				|| illustration.codePointCount(0, illustration.length()) > MAX_ILLUSTRATION) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "illustration 为空或过长");
		}
		Object sourceRaw = item.get("sourceBlockIds");
		if (!(sourceRaw instanceof List<?> sourceList) || sourceList.isEmpty()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项必须引用来源块");
		}
		List<String> sourceBlockIds = new ArrayList<>();
		StringBuilder referenced = new StringBuilder();
		for (Object blockId : sourceList) {
			if (!(blockId instanceof String id) || !selectedIds.contains(id) || !byId.containsKey(id)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "来源块不在本次选择内");
			}
			if (!sourceBlockIds.contains(id)) {
				sourceBlockIds.add(id);
				referenced.append(byId.get(id).text()).append('\n');
			}
		}
		List<String> criticalText = new ArrayList<>();
		if (item.get("criticalText") instanceof List<?> criticalList) {
			for (Object critical : criticalList) {
				if (!(critical instanceof String text) || text.codePointCount(0, text.length()) > MAX_CRITICAL_LENGTH) {
					throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "criticalText 元素为空或过长");
				}
				if (!referenced.toString().contains(text)) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "关键文字未逐字出现在所引来源块中");
				}
				criticalText.add(text);
				if (criticalText.size() > MAX_CRITICAL) {
					throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
							"criticalText 至多 " + MAX_CRITICAL + " 条");
				}
			}
		}
		if (!itemIds.add(itemId) || !cardIds.add(cardId)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项身份重复");
		}
		String layoutId = textOrNull(item.get("layoutId"), "layoutId");
		if (layoutId == null || CreationVisualPresetCatalog.layout(layoutId) == null) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知画面布局");
		}
		String targetAspect = textOrNull(item.get("targetAspect"), "targetAspect");
		if (targetAspect == null || !ASPECTS.contains(targetAspect)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "目标画幅不合法");
		}
		String placement = null;
		if (item.get("placement") instanceof Map<?, ?> placementMap) {
			Object after = placementMap.get("afterBlockId");
			if (after instanceof String afterText && !afterText.isBlank()) {
				if (!byId.containsKey(afterText)) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "placement.afterBlockId 不在来源块内");
				}
				placement = afterText;
			}
		}
		return new VisualPlan.Item(itemId, cardId, index + 1, role, title, List.copyOf(bullets),
				textOrNull(item.get("caption"), "caption"), textOrNull(item.get("purpose"), "purpose"), illustration,
				List.copyOf(sourceBlockIds), List.copyOf(criticalText), layoutId, targetAspect, placement);
	}

	Set<String> selectedBlockIdsFromSnapshot(VisualPlan.PlanRow row, SourceDocument document) {
		Map<String, Object> snapshot = PlanJson.readJson(row.inputSnapshotJson());
		// 快照记录了冻结时的选择块；旧快照缺失时回退全部块（prepare 起始版本即写入，仅防御）。
		Set<String> selected = new LinkedHashSet<>();
		if (snapshot.get("selectedBlockIds") instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					selected.add(text);
				}
			}
		}
		if (selected.isEmpty()) {
			for (var block : document.blocks()) {
				selected.add(block.id());
			}
		}
		return selected;
	}

	// ---- 公共小工具 ----

	private static List<String> uncoveredBlockIds(Set<String> selectedIds, List<VisualPlan.Item> items) {
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

	private static void rejectUnknownMap(Map<String, Object> map, Set<String> allowed, String what) {
		for (String field : map.keySet()) {
			if (!allowed.contains(field)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", what + " 含未知字段：" + field);
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

	private static String textOrNull(Object value, String field) {
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "字段 " + field + " 必须是字符串");
		}
		return text.isBlank() ? null : text;
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
			return Mono.just(new Outcome(row, true, null, false));
		}
		return withDocument(row, row.currentRevision());
	}

	static String hashCanonical(Map<String, Object> canonical) {
		return PlanJson.sha256(PlanJson.json(canonical));
	}
}
