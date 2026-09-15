package com.grassland.intelligence.creationstudio.plan;

import static com.grassland.intelligence.creationstudio.plan.VisualPlanBuildService.refresh;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanBuildService.uncoveredBlockIds;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.ASPECTS;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.allowedRoles;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_BULLETS;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_BULLET_LENGTH;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_CRITICAL;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_CRITICAL_LENGTH;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_EXPLANATION;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_ILLUSTRATION;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.MAX_TITLE;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.hashCanonical;
import static com.grassland.intelligence.creationstudio.plan.VisualPlanService.resolveStyle;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService.ConfirmCommand;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService.Outcome;
import com.grassland.intelligence.creationstudio.plan.VisualPlanService.PatchCommand;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationassistant.CreationResultReferences;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog;
import com.grassland.intelligence.creationstudio.CreationVisualPresetCatalog;
import com.grassland.intelligence.creationstudio.CreationRecipeCatalog.RecipeDefinition;
import com.grassland.intelligence.creationstudio.CreationStudioContextService;
import com.grassland.intelligence.creationstudio.source.SourceDocument;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：视觉计划「修订」服务——自 {@link VisualPlanService} 按职责原样搬移： PATCH（完整
 * document 逐项校验→不可变 revision 追加→指针推进）与 confirm（行锁+版本/正文 hash 核验），共用草稿锁与
 * requestId 幂等包装；共享读组装经注入 {@link VisualPlanBuildService} 复用。 逻辑零变更；facade
 * 以委托保持既有 API。
 */
@Service
public class VisualPlanRevisionService {

	private final VisualPlanBuildService build;
	private final VisualPlanRepository plans;
	private final CreationDraftService drafts;
	private final CreationResultReferences mediaReferences;

	public VisualPlanRevisionService(VisualPlanBuildService build, VisualPlanRepository plans,
			CreationDraftService drafts, CreationResultReferences mediaReferences) {
		this.build = build;
		this.plans = plans;
		this.drafts = drafts;
		this.mediaReferences = mediaReferences;
	}

	// ---- API101-10 PATCH ----

	public Mono<Outcome> patch(Caller caller, UUID id, PatchCommand command) {
		String hash = hashCanonical(Map.of("id", id.toString(), "expectedRevision", command.expectedRevision(),
				"document", command.documentRaw()));
		return command(caller, id, "plan-patch", command.requestId(), hash, (row, draft) -> {
			if (!"ready".equals(row.status()))
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划当前状态不可编辑"));
			if (row.currentRevision() != command.expectedRevision())
				return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划版本已变化，请刷新后重试"));
			return patchLocked(row, command, caller);
		});
	}

	private Mono<Outcome> command(Caller caller, UUID id, String kind, UUID requestId, String hash,
			java.util.function.BiFunction<VisualPlan.PlanRow, CreationDraft, Mono<Outcome>> action) {
		return build.loadOwnedRow(id, caller)
				.flatMap(owned -> drafts.withStudioDraftLock(owned.draftId().toString(), caller,
						draft -> plans.lockById(id).flatMap(row -> plans
								.findStudioApply(caller.accountId(), kind, requestId.toString()).flatMap(applied -> {
									if (!id.equals(applied.resourceId()) || !hash.equals(applied.requestHash()))
										return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT",
												"同一 requestId 已用于不同请求"));
									return build.withDocument(row, applied.appliedVersion())
											.map(outcome -> new Outcome(outcome.plan(), false, outcome.document(),
													outcome.stale(), applied.result()));
								}).switchIfEmpty(Mono.defer(() -> {
									build.requireWrites();
									CreationStudioContextService.requireStudioScope(draft);
									if (draft
											.status() == com.grassland.intelligence.creationassistant.DraftStatus.ARCHIVED)
										return Mono.error(
												new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "归档草稿不能修改计划"));
									return action.apply(row, draft)
											.flatMap(outcome -> plans
													.recordStudioApply(caller.accountId(), kind, requestId.toString(),
															hash, id, outcome.plan().currentRevision(),
															VisualPlanController.toBody(outcome))
													.flatMap(inserted -> inserted
															? Mono.just(outcome)
															: Mono.error(new IntelligenceException(409,
																	"STUDIO_OPERATION_CONFLICT",
																	"同一 requestId 已用于不同请求"))));
								})))));
	}

	private Mono<Outcome> patchLocked(VisualPlan.PlanRow row, PatchCommand command, Caller caller) {
		return build.loadPlanSource(row).zipWith(plans.findRevision(row.id(), row.currentRevision())).map(tuple -> {
			SourceDocument document = tuple.getT1();
			Set<String> selected = selectedBlockIdsFromSnapshot(row, document);
			Map<String, SourceDocument.Block> byId = new LinkedHashMap<>();
			for (var block : document.blocks()) {
				byId.put(block.id(), block);
			}
			VisualPlan.Document patched = buildDocument(row, command.documentRaw(), selected, byId);
			Map<String, String> identities = new LinkedHashMap<>();
			Map<String, Object> previous = PlanJson.readJson(tuple.getT2().documentJson());
			if (previous.get("items") instanceof List<?> items) {
				for (Object value : items)
					if (value instanceof Map<?, ?> item) {
						identities.put(String.valueOf(item.get("itemId")), String.valueOf(item.get("cardId")));
					}
			}
			for (VisualPlan.Item item : patched.items()) {
				if (!item.cardId().equals(identities.get(item.itemId()))) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划编辑须保留原有 itemId/cardId；新增条目请重新策划");
				}
			}
			return patched;
		}).flatMap(document -> {
			String documentJson = PlanJson.json(document.toMap());
			String documentHash = PlanJson.sha256(documentJson);
			int next = row.currentRevision() + 1;
			return reactor.core.publisher.Flux.fromIterable(document.items())
					.filter(item -> item.inputMediaRef() != null)
					.concatMap(item -> mediaReferences.resolveMedia(item.inputMediaRef(), caller)).then()
					.then(plans.appendRevision(row.id(), row.currentRevision(), next, documentJson, documentHash))
					.flatMap(updated -> updated > 0
							? build.withDocument(refresh(row, next), next)
							: Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划已被并发修改，请刷新后重试")));
		});
	}

	// ---- API101-11 confirm ----

	public Mono<Outcome> confirm(Caller caller, UUID id, ConfirmCommand command) {
		String hash = hashCanonical(Map.of("id", id.toString(), "draftId", command.draftId().toString(),
				"expectedDraftVersion", command.expectedDraftVersion(), "expectedRevision", command.expectedRevision(),
				"sourceContentHash", command.sourceContentHash()));
		return command(caller, id, "plan-confirm", command.requestId(), hash, (row, draft) -> {
			if (!row.draftId().equals(command.draftId()))
				return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "确认请求与计划不属于同一草稿"));
			if (!"ready".equals(row.status()))
				return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划未就绪，不能确认"));
			if (row.currentRevision() != command.expectedRevision()
					|| draft.version() != command.expectedDraftVersion())
				return Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "草稿或计划版本已变化，请刷新后重试"));
			if (!row.sourceContentHash().equals(command.sourceContentHash())
					|| !row.baseContentHash().equals(CreationStudioContextService.computeBaseContentHash(draft)))
				return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "正文或来源已变化，请重新策划"));
			return build.loadPlanSource(row)
					.then(plans.confirm(row.id(), command.expectedRevision(), draft.version(),
							command.sourceContentHash(), caller.accountId()))
					.flatMap(updated -> updated > 0
							? build.withDocument(confirmed(row, command.expectedRevision(), draft.version()),
									row.currentRevision())
							: Mono.error(new IntelligenceException(409, "STUDIO_VERSION_CONFLICT", "计划已被并发修改，请刷新后重试")));
		});
	}

	private static VisualPlan.PlanRow confirmed(VisualPlan.PlanRow row, int revision, int draftVersion) {
		return new VisualPlan.PlanRow(row.id(), row.ownerAccountId(), row.draftId(), row.requestId(), row.requestHash(),
				row.sourceDocumentId(), row.sourceContentHash(), row.baseDraftVersion(), row.baseContentHash(),
				row.recipeId(), row.recipeVersion(), row.upstreamCommit(), row.inputSnapshotJson(),
				row.promptCiphertext(), row.promptHash(), row.status(), row.currentRevision(), revision, draftVersion,
				row.sourceContentHash(), OffsetDateTime.now(ZoneOffset.UTC), row.ownerAccountId(), row.runId(),
				row.errorCode(), row.createdAt(), OffsetDateTime.now(ZoneOffset.UTC));
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
		if (!Set.of("id", "version").containsAll(recipeMap.keySet()))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "recipe 含未知字段");
		Object strategy = documentRaw.get("strategy");
		if (!String.valueOf(snapshot.get("strategy")).equals(strategy)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划编辑不能切换策略，请新建计划");
		}
		Object styleRaw = documentRaw.get("style");
		if (!(styleRaw instanceof Map<?, ?> styleMap)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "document.style 必须是对象");
		}
		if (!Set.of("styleId", "layoutId", "paletteId").containsAll(styleMap.keySet()))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "style 含未知字段");
		for (String field : List.of("styleId", "layoutId", "paletteId"))
			if (!(styleMap.get(field) instanceof String text) || text.isBlank())
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划样式字段必须完整");
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
						"sourceBlockIds", "criticalText", "layoutId", "targetAspect", "placement", "inputMediaRef"),
				"items[" + index + "]");
		String itemId = textOrNull(item.get("itemId"), "itemId");
		String cardId = textOrNull(item.get("cardId"), "cardId");
		if (itemId == null || cardId == null || itemId.length() > 64 || cardId.length() > 64) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项身份（itemId/cardId）不能为空");
		}
		com.grassland.intelligence.creationstudio.StudioRequestValidator.requirePositiveInt(item, "position");
		for (String field : List.of("caption", "purpose"))
			if (!(item.get(field) instanceof String))
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 必须是字符串");
		String role = textOrNull(item.get("role"), "role");
		if ("cover".equals(role)) {
			if (index != 0) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "封面只能是第 1 项");
			}
		} else if (!allowedRoles(recipe).contains(role) || index == 0) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "计划项角色不合法：" + role);
		}
		String title = textOrNull(item.get("title"), "title");
		if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > MAX_TITLE) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "标题为空或过长");
		}
		Object bulletsRaw = item.get("bullets");
		if (!(bulletsRaw instanceof List<?>)) {
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
		if (!(item.get("criticalText") instanceof List<?>))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "criticalText 必须是数组");
		if (item.get("criticalText") instanceof List<?> criticalList) {
			for (Object critical : criticalList) {
				if (!(critical instanceof String text) || text.isBlank()
						|| text.codePointCount(0, text.length()) > MAX_CRITICAL_LENGTH) {
					throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "criticalText 元素为空或过长");
				}
				if (sourceBlockIds.stream().noneMatch(id -> byId.get(id).text().contains(text))) {
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
			if (!Set.of("afterBlockId").containsAll(placementMap.keySet()))
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "placement 含未知字段");
			// C101-14：段落定位仅 article-visuals 使用（§6.2），其余模板携带即拒。
			if (!ArticleVisualPlanAdapter.isArticleVisuals(recipe.id())) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "该模板不支持 placement");
			}
			if ("cover".equals(role)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "封面不支持段落定位");
			}
			Object after = placementMap.get("afterBlockId");
			if (after instanceof String afterText && !afterText.isBlank()) {
				if (!selectedIds.contains(afterText)) {
					throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "placement.afterBlockId 不在来源块内");
				}
				placement = afterText;
			}
		}
		if ("illustration".equals(role) && placement == null)
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "插图必须绑定所选原文段落");
		for (String field : List.of("caption", "purpose")) {
			String text = textOrNull(item.get(field), field);
			if (text != null && text.codePointCount(0, text.length()) > (field.equals("caption") ? 500 : 200))
				throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", field + " 过长");
		}
		return new VisualPlan.Item(itemId, cardId, index + 1, role, title, List.copyOf(bullets),
				textOrNull(item.get("caption"), "caption"), textOrNull(item.get("purpose"), "purpose"), illustration,
				List.copyOf(sourceBlockIds), List.copyOf(criticalText), layoutId, targetAspect, placement,
				parseInputMedia(item.get("inputMediaRef")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parseInputMedia(Object raw) {
		if (raw == null)
			return null;
		if (!(raw instanceof Map<?, ?> ref))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "inputMediaRef 必须是媒体引用");
		Map<String, Object> value = (Map<String, Object>) ref;
		if (value.values().stream().anyMatch(java.util.Objects::isNull))
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "引用字段不允许 null");
		rejectUnknownMap(value, Set.of("id", "refType", "role", "cardId", "position", "runId", "taskId"),
				"inputMediaRef");
		com.grassland.intelligence.creationstudio.StudioRequestValidator.requireUuid(value, "id");
		com.grassland.intelligence.creationstudio.StudioRequestValidator.requireEnum(value, "refType",
				Set.of("media", "content-asset"));
		return Map.copyOf(value);
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

	private static void rejectUnknownMap(Map<String, Object> map, Set<String> allowed, String what) {
		for (String field : map.keySet()) {
			if (!allowed.contains(field)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", what + " 含未知字段：" + field);
			}
		}
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
}
