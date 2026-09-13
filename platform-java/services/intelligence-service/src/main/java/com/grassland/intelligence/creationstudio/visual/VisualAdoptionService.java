package com.grassland.intelligence.creationstudio.visual;

import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftService;
import com.grassland.intelligence.creationassistant.CreationResultReferences;
import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.plan.VisualPlan;
import com.grassland.intelligence.creationstudio.plan.VisualPlanRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-12（API101-17 §6.5）：视觉成品原子采用。 服务端按当前计划 revision 读取
 * artifact（deliveryMediaRef 是唯一入稿引用），不相信客户端媒体字段；同事务走
 * {@link CreationDraftService#applyStudioMutation}（appendVersion +
 * save），人工文案字段不触碰。 重复采用同一批选择为 no-op（不追加版本、不新增模型调用）；并发采用经计划行锁 + 草稿乐观锁串行。
 */
@Service
public class VisualAdoptionService {

	private final VisualPlanRepository plans;
	private final VisualArtifactRepository artifacts;
	private final VisualItemRepository items;
	private final CreationDraftService drafts;
	private final CreationResultReferences resultReferences;
	private final CreationStudioProperties properties;

	public VisualAdoptionService(VisualPlanRepository plans, VisualArtifactRepository artifacts,
			VisualItemRepository items, CreationDraftService drafts, CreationResultReferences resultReferences,
			CreationStudioProperties properties) {
		this.plans = plans;
		this.artifacts = artifacts;
		this.items = items;
		this.drafts = drafts;
		this.resultReferences = resultReferences;
		this.properties = properties;
	}

	public record AdoptCommand(UUID requestId, UUID draftId, int expectedDraftVersion, int expectedPlanRevision,
			List<Selection> selections) {

		public record Selection(String itemId, UUID artifactId) {
		}
	}

	public record AdoptOutcome(CreationDraft draft, int appliedVersion, boolean alreadyApplied) {
	}

	public Mono<AdoptOutcome> adopt(Caller caller, UUID planId, AdoptCommand command) {
		if (!properties.isWritesEnabled()) {
			return Mono.error(new IntelligenceException(404, "STUDIO_DISABLED", "创作工作台写入暂未开放"));
		}
		if (command.selections() == null || command.selections().isEmpty() || command.selections().size() > 36) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selections 必须为 1~36 项"));
		}
		List<String> itemIds = command.selections().stream().map(AdoptCommand.Selection::itemId).toList();
		if (new LinkedHashSet<>(itemIds).size() != itemIds.size()) {
			return Mono.error(new IntelligenceException(400, "STUDIO_INVALID_INPUT", "同一 item 不允许重复选择"));
		}
		// 计划行锁：同计划并发采用串行化（no-op 判定因此可靠）。
		return plans.lockById(planId).filter(row -> caller.accountId().equals(row.ownerAccountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉计划不存在")))
				.flatMap(plan -> {
					if (!"ready".equals(plan.status())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED", "计划当前状态不可采用"));
					}
					if (plan.confirmedRevision() == null || plan.confirmedRevision() != plan.currentRevision()
							|| plan.confirmedRevision() != command.expectedPlanRevision()) {
						return Mono.error(new IntelligenceException(409, "STUDIO_PLAN_STALE", "计划已变更，请刷新后重新确认"));
					}
					if (!plan.draftId().equals(command.draftId())) {
						return Mono.error(new IntelligenceException(409, "STUDIO_OPERATION_CONFLICT", "计划不属于该草稿"));
					}
					return loadAdoptableArtifacts(caller, plan, command.selections())
							.zipWith(plans.findRevision(planId, plan.currentRevision()))
							.flatMap(tuple -> adoptWithDraft(caller, plan, tuple.getT1(), tuple.getT2().documentJson(),
									command));
				});
	}

	/** 每个 selection 解析为服务端权威 artifact（owner/计划/版本/条目/终态全链校验）。 */
	private Mono<List<VisualArtifact>> loadAdoptableArtifacts(Caller caller, VisualPlan.PlanRow plan,
			List<AdoptCommand.Selection> selections) {
		return Flux.fromIterable(selections)
				.concatMap(selection -> artifacts.findByIdAndOwner(selection.artifactId(), caller.accountId())
						.switchIfEmpty(Mono.error(new IntelligenceException(404, "STUDIO_NOT_FOUND", "所选成品不存在")))
						.flatMap(artifact -> {
							if (!artifact.planId().equals(plan.id())
									|| artifact.planRevision() != plan.currentRevision()
									|| !artifact.itemId().equals(selection.itemId())) {
								return Mono.error(
										new IntelligenceException(409, "STUDIO_PLAN_STALE", "所选成品不属于当前计划版本，请刷新候选"));
							}
							// §6.5：必须 state=succeeded——按 attempt 读条目终态，不信客户端。
							return items.findById(artifact.attemptId())
									.filter(item -> VisualItemRepository.STATE_SUCCEEDED.equals(item.state()))
									.switchIfEmpty(Mono.error(new IntelligenceException(409, "STUDIO_RESOURCE_LOCKED",
											"所选条目尚未成功结算，不能采用")))
									.map(item -> artifact);
						}))
				.collectList();
	}

	private Mono<AdoptOutcome> adoptWithDraft(Caller caller, VisualPlan.PlanRow plan, List<VisualArtifact> chosen,
			String documentJson, AdoptCommand command) {
		return drafts.loadOwned(plan.draftId().toString(), caller.accountId()).flatMap(current -> {
			Mutation mutation = computeMutation(current, documentJson, chosen);
			if (!mutation.changed()) {
				// 重复采用同一批选择：不追加版本、不产生任何写（§6.5 只产生一次版本变更）。
				return Mono.just(new AdoptOutcome(current, current.version(), true));
			}
			return resultReferences.validateNew(mutation.workspace, current.workspace(), caller, current.id())
					.then(drafts.applyStudioMutation(plan.draftId().toString(), caller.accountId(),
							command.expectedDraftVersion(), draft -> applyMutation(draft, mutation)))
					.map(mutated -> new AdoptOutcome(mutated, mutated.version(), false));
		});
	}

	// ---- 工作区演化（§6.5：只动 resultRefs / delivery.coverRef / delivery.mediaRefs /
	// resultAssetIds /
	// runIds；标题、正文、摘要、分享配文等人工编辑字段一律不碰） ----

	record Mutation(boolean changed, Map<String, Object> workspace, List<String> resultAssetIds, List<String> runIds) {
	}

	@SuppressWarnings("unchecked")
	private Mutation computeMutation(CreationDraft current, String documentJson, List<VisualArtifact> chosen) {
		Map<String, Object> document = PlanJson.readJson(documentJson);
		List<Map<String, Object>> documentItems = document.get("items") instanceof List<?> list
				? (List<Map<String, Object>>) (List<?>) list
				: List.of();
		Map<String, VisualArtifact> selectedByItem = new LinkedHashMap<>();
		for (VisualArtifact artifact : chosen) {
			selectedByItem.put(artifact.itemId(), artifact);
		}
		// 计划 cardId → itemId：替换范围只覆盖本次所选条目（其余引用原样保留，§6.5 保留未涉及素材）。
		Map<String, String> itemIdByCardId = new LinkedHashMap<>();
		for (Map<String, Object> item : documentItems) {
			if (item.get("itemId") instanceof String itemId) {
				itemIdByCardId.put(String.valueOf(item.getOrDefault("cardId", itemId)), itemId);
			}
		}

		Map<String, Object> workspace = new LinkedHashMap<>(
				current.workspace() == null ? Map.of() : current.workspace());
		List<Object> existingRefs = workspace.get("resultRefs") instanceof List<?> refs
				? new ArrayList<>(refs)
				: new ArrayList<>();
		Map<String, Object> delivery = workspace.get("delivery") instanceof Map<?, ?> deliveryMap
				? new LinkedHashMap<>((Map<String, ?>) deliveryMap)
				: new LinkedHashMap<>();
		List<Object> existingMediaRefs = delivery.get("mediaRefs") instanceof List<?> mediaRefs
				? new ArrayList<>(mediaRefs)
				: new ArrayList<>();

		List<Map<String, Object>> newRefs = new ArrayList<>();
		Map<String, Map<String, Object>> newRefByCardId = new LinkedHashMap<>();
		Map<String, Object> coverRef = null;
		// 按计划文档顺序落引用（媒体顺序=计划顺序，不受提交顺序影响）。
		for (Map<String, Object> item : documentItems) {
			if (!(item.get("itemId") instanceof String itemId)) {
				continue;
			}
			VisualArtifact artifact = selectedByItem.get(itemId);
			if (artifact == null) {
				continue;
			}
			String cardId = String.valueOf(item.getOrDefault("cardId", itemId));
			Map<String, Object> ref = new LinkedHashMap<>();
			ref.put("id", artifact.deliveryMediaId().toString());
			ref.put("refType", "media");
			ref.put("role", "card");
			ref.put("cardId", cardId);
			if (item.get("position") instanceof Number number && number.intValue() >= 1) {
				ref.put("position", number.intValue());
			}
			if (artifact.runId() != null) {
				ref.put("runId", artifact.runId().toString());
			}
			// C101-14：article-visuals 插图的段落位置随采用持久化（§6.5 正文图片保留段落绑定）。
			if (item.get("placement") instanceof Map<?, ?> placement
					&& placement.get("afterBlockId") instanceof String afterBlockId && !afterBlockId.isBlank()) {
				ref.put("placement", Map.of("afterBlockId", afterBlockId));
			}
			newRefs.add(ref);
			newRefByCardId.put(cardId, ref);
			if ("cover".equals(item.get("role"))) {
				coverRef = new LinkedHashMap<>(ref);
				coverRef.put("role", "cover");
			}
		}

		// 合并：计划顺序为骨架——本次选用新引用，未选条目保留其既有引用的位次；
		// 计划外旧引用（legacy 图卡等）按原顺序缀尾（§6.5 保留未涉及素材）。
		workspace.put("resultRefs", mergeByPlanOrder(documentItems, existingRefs, newRefByCardId, itemIdByCardId));
		delivery.put("mediaRefs", mergeByPlanOrder(documentItems, existingMediaRefs, newRefByCardId, itemIdByCardId));
		if (coverRef != null) {
			delivery.put("coverRef", coverRef);
		}
		workspace.put("delivery", delivery);
		List<String> resultAssetIds = mergeIds(current.resultAssetIds(), deliveryMediaIds(newRefs));
		List<String> runIds = mergeIds(current.runIds(), runIds(newRefs));
		// 变更判定：键序规范化后整体比对（jsonb 读回不保键序；resultRefs/mediaRefs/coverRef
		// 任一实质差异即变更，重放同批选择必须命中 no-op）。
		boolean changed = !canonicalJson(workspace)
				.equals(canonicalJson(current.workspace() == null ? Map.of() : current.workspace()))
				|| !resultAssetIds.equals(current.resultAssetIds()) || !runIds.equals(current.runIds());
		return new Mutation(changed, workspace, resultAssetIds, runIds);
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper SORTED = new com.fasterxml.jackson.databind.ObjectMapper()
			.configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	/** 计划顺序合并：新引用落在所选条目位次；未选条目保留既有引用；其余旧引用按原顺序缀尾。 */
	private static List<Object> mergeByPlanOrder(List<Map<String, Object>> documentItems, List<Object> existingRefs,
			Map<String, Map<String, Object>> newRefByCardId, Map<String, String> itemIdByCardId) {
		Map<String, Object> existingByCardId = new LinkedHashMap<>();
		for (Object ref : existingRefs) {
			if (ref instanceof Map<?, ?> refMap && refMap.get("cardId") instanceof String cardId
					&& itemIdByCardId.containsKey(cardId) && !existingByCardId.containsKey(cardId)) {
				existingByCardId.put(cardId, ref);
			}
		}
		List<Object> merged = new ArrayList<>();
		Set<String> placedCardIds = new java.util.HashSet<>();
		for (Map<String, Object> item : documentItems) {
			if (!(item.get("itemId") instanceof String itemId)) {
				continue;
			}
			String cardId = String.valueOf(item.getOrDefault("cardId", itemId));
			Map<String, Object> replacement = newRefByCardId.get(cardId);
			if (replacement != null) {
				merged.add(replacement);
				placedCardIds.add(cardId);
			} else {
				Object existing = existingByCardId.get(cardId);
				if (existing != null) {
					merged.add(existing);
					placedCardIds.add(cardId);
				}
			}
		}
		// 未落位的既有引用（legacy 图卡/无 cardId 旧形态）原样按序缀尾（§6.5 保留未涉及素材）。
		for (Object ref : existingRefs) {
			if (ref instanceof Map<?, ?> refMap && refMap.get("cardId") instanceof String cardId
					&& placedCardIds.contains(cardId)) {
				continue;
			}
			merged.add(ref);
		}
		return merged;
	}

	private static String canonicalJson(Object value) {
		try {
			return SORTED.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalStateException("采用比对序列化失败", error);
		}
	}

	private static List<String> deliveryMediaIds(List<Map<String, Object>> refs) {
		List<String> ids = new ArrayList<>();
		for (Map<String, Object> ref : refs) {
			ids.add(String.valueOf(ref.get("id")));
		}
		return ids;
	}

	private static List<String> runIds(List<Map<String, Object>> refs) {
		List<String> ids = new ArrayList<>();
		for (Map<String, Object> ref : refs) {
			if (ref.get("runId") instanceof String runId) {
				ids.add(runId);
			}
		}
		return ids;
	}

	private static List<String> mergeIds(List<String> current, List<String> added) {
		LinkedHashSet<String> merged = new LinkedHashSet<>(current == null ? List.of() : current);
		merged.addAll(added);
		if (merged.size() > 20) {
			throw new IntelligenceException(409, "STUDIO_LIMIT_EXCEEDED", "结果引用超出 20 项上限，请清理旧素材后重试");
		}
		return List.copyOf(merged);
	}

	private static CreationDraft applyMutation(CreationDraft draft, Mutation mutation) {
		return new CreationDraft(draft.id(), draft.ownerAccountId(), draft.organizationId(), draft.title(),
				draft.sourceType(), draft.taskId(), draft.taskVersion(), draft.storeId(), draft.platform(),
				draft.contentForm(), draft.topic(), draft.articleTitle(), draft.outline(), draft.content(),
				draft.contentMode(), draft.questionText(), draft.questionRef(), draft.status(), draft.version(),
				draft.createdAt(), draft.updatedAt(), draft.deletedAt(), mutation.workspace(),
				mutation.resultAssetIds(), mutation.runIds());
	}
}
