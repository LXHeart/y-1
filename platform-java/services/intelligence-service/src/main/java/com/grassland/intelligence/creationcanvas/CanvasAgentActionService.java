package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.videoproduction.VideoProductionTaskService;
import com.grassland.intelligence.videoproduction.VideoStoryboardEditService;
import com.grassland.intelligence.videoproduction.VideoStoryboardVariantService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Plan -> sorted parents -> task locks. Validation, domain writes and replay
 * result share one transaction.
 */
@Service
public class CanvasAgentActionService {
	private static final ObjectMapper JSON = new ObjectMapper();
	private final CanvasAgentPlanRepository plans;
	private final DatabaseClient db;
	private final VideoStoryboardEditService edits;
	private final VideoStoryboardVariantService variants;
	private final CanvasProjectAccess projects;
	private final VideoProductionTaskService tasks;
	private final TransactionalOperator transactions;
	private final Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public CanvasAgentActionService(CanvasAgentPlanRepository plans, DatabaseClient db,
			VideoStoryboardEditService edits, VideoStoryboardVariantService variants, CanvasProjectAccess projects,
			VideoProductionTaskService tasks, TransactionalOperator transactions) {
		this(plans, db, edits, variants, projects, tasks, transactions, Clock.systemUTC());
	}

	CanvasAgentActionService(CanvasAgentPlanRepository plans, DatabaseClient db, VideoStoryboardEditService edits,
			VideoStoryboardVariantService variants, CanvasProjectAccess projects, VideoProductionTaskService tasks,
			TransactionalOperator transactions, Clock clock) {
		this.plans = plans;
		this.db = db;
		this.edits = edits;
		this.variants = variants;
		this.projects = projects;
		this.tasks = tasks;
		this.transactions = transactions;
		this.clock = clock;
	}

	public Mono<Map<String, Object>> apply(String accountId, UUID planId) {
		return plans.lockById(planId, accountId)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND", "计划不存在")))
				.flatMap(plan -> {
					JsonNode action = "ready".equals(plan.status()) ? action(plan.actionJson()) : null;
					var key = new CanvasProjectAccess.ProjectKey(plan.draftId(), plan.storyboardId());
					var keys = action != null && "variant".equals(action.path("kind").asText())
							? projects.keysWithRoot(accountId, key)
							: Mono.just(List.of(key));
					return keys.flatMap(all -> projects.lockProjects(accountId, all)).flatMap(locked -> {
						var project = locked.getFirst();
						// Replays still require owned, undeleted parents, but never re-check old
						// versions.
						if ("applied".equals(plan.status()))
							return Mono.just(readResult(plan.applyResultJson()));
						if ("expired".equals(plan.status()) || !plan.expiresAt().toInstant().isAfter(clock.instant()))
							return Mono.error(new IntelligenceException(409, "CANVAS_PLAN_EXPIRED", "计划已过期，请重新提出修改"));
						if (!"ready".equals(plan.status()))
							return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "计划当前状态不可应用"));
						projects.checkWritable(project, CanvasProjectAccess.WriteKind.PLAN_APPLY);
						if (project.draft().version() != plan.baseDraftVersion()
								|| project.storyboard().editVersion() != plan.baseEditVersion()
								|| project.canvas() == null || project.canvas().revision() != plan.baseCanvasRevision())
							return Mono
									.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT", "画布已变化，请重新提出修改"));
						Set<String> selected = selectedShots(plan, project.canvas().documentJson());
						return db.sql("SELECT count(*) FROM video_shot WHERE storyboard_id=:sb")
								.bind("sb", plan.storyboardId()).map(row -> row.get(0, Long.class)).one()
								.flatMap(count -> {
									try {
										CanvasAgentPlan.validateAgainstContext(action.toString(), selected,
												VideoStoryboardEditService.payloadImageCount(project.storyboard()),
												Math.toIntExact(count));
									} catch (IllegalArgumentException error) {
										return Mono.error(invalid(error.getMessage()));
									}
									return switch (action.path("kind").asText()) {
										case "edit" -> applyEdit(plan, action, project);
										case "variant" -> applyVariant(plan, action);
										case "prepare-generation" -> applyPrepare(plan, action, project);
										default -> Mono.error(invalid("未知计划动作"));
									};
								})
								.flatMap(result -> plans.apply(plan.id(), json(result))
										.flatMap(updated -> updated == 1
												? Mono.just(result)
												: Mono.error(new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT",
														"计划状态已变化"))));
					});
				}).as(transactions::transactional);
	}

	private Mono<Map<String, Object>> applyEdit(CanvasAgentPlanRepository.AgentPlanRow plan, JsonNode action,
			CanvasProjectAccess.LockedProject project) {
		var patches = new ArrayList<VideoStoryboardEditService.BatchContentPatch>();
		var appends = new ArrayList<VideoStoryboardEditService.AppendedContent>();
		for (JsonNode item : action.path("actions")) {
			if ("update-shot".equals(item.path("kind").asText())) {
				JsonNode patch = item.path("patch");
				patches.add(
						new VideoStoryboardEditService.BatchContentPatch(UUID.fromString(patch.path("shotId").asText()),
								string(patch, "visual"), string(patch, "narration"), integer(patch, "plannedSeconds"),
								string(patch, "cameraMove"), integer(patch, "anchorImageIndex")));
			} else {
				JsonNode shot = item.path("shot");
				appends.add(new VideoStoryboardEditService.AppendedContent(shot.path("visual").asText(),
						shot.path("narration").asText(), shot.path("plannedSeconds").asInt(),
						shot.path("cameraMove").asText(), shot.path("anchorImageIndex").asInt()));
			}
		}
		return edits.applyPlanEditsLocked(project.storyboard(), patches, appends)
				.map(result -> result(plan, result.editVersion(), result.updatedShotIds(), null, null));
	}

	private Mono<Map<String, Object>> applyVariant(CanvasAgentPlanRepository.AgentPlanRow plan, JsonNode action) {
		List<UUID> shotIds = new ArrayList<>();
		action.path("shotIds").forEach(id -> shotIds.add(UUID.fromString(id.asText())));
		return variants.derive(plan.accountId(), plan.storyboardId(),
				new VideoStoryboardVariantService.CreateVariantRequest(plan.operationId(), plan.baseEditVersion(),
						(long) plan.baseDraftVersion(), action.path("title").asText(), shotIds))
				.map(derived -> result(plan, plan.baseEditVersion(), List.of(), derived.variant(), null));
	}

	private Mono<Map<String, Object>> applyPrepare(CanvasAgentPlanRepository.AgentPlanRow plan, JsonNode action,
			CanvasProjectAccess.LockedProject project) {
		String mode = action.path("mode").asText();
		String shotId = action.path("shotId").isNull() ? null : action.path("shotId").asText();
		Mono<Void> guard = db
				.sql("SELECT id FROM video_production_task WHERE storyboard_id=:sb AND account_id=:account "
						+ "ORDER BY created_at DESC,id DESC LIMIT 1 FOR UPDATE")
				.bind("sb", plan.storyboardId()).bind("account", plan.accountId()).map(row -> row.get(0, UUID.class))
				.one().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()).flatMap(task -> {
					if ("initial".equals(mode)) {
						if (project.storyboard().isCommitted() || task.isPresent())
							return Mono.error(
									new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "已存在初始制作任务，请使用已有任务或派生方案"));
						return Mono.empty();
					}
					if (task.isEmpty())
						return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED", "请先创建制作任务"));
					return tasks.validatePreparation(task.get(), plan.accountId(), UUID.fromString(shotId), mode);
				});
		Map<String, Object> prepared = new LinkedHashMap<>();
		prepared.put("mode", mode);
		prepared.put("shotId", shotId);
		return guard.thenReturn(result(plan, plan.baseEditVersion(), List.of(), null, prepared));
	}

	private static Set<String> selectedShots(CanvasAgentPlanRepository.AgentPlanRow plan, String document) {
		try {
			Map<String, JsonNode> nodes = new LinkedHashMap<>();
			JSON.readTree(document).path("nodes").forEach(n -> nodes.put(n.path("id").asText(), n));
			JsonNode ids = JSON.readTree(plan.selectedNodeIdsJson());
			if (!ids.isArray())
				throw invalid("计划选择无效");
			Set<String> seen = new HashSet<>();
			Set<String> shots = new HashSet<>();
			for (JsonNode id : ids) {
				if (!id.isTextual() || !seen.add(id.asText()) || !nodes.containsKey(id.asText()))
					throw invalid("计划选择已不存在");
				JsonNode node = nodes.get(id.asText());
				if ("shot".equals(node.path("kind").asText())) {
					String ref = node.path("refId").asText();
					if (!id.asText().equals("shot:" + ref))
						throw invalid("镜头引用无效");
					shots.add(ref);
				}
			}
			return shots;
		} catch (IntelligenceException error) {
			throw error;
		} catch (Exception error) {
			throw invalid("计划选择不可读");
		}
	}
	private static JsonNode action(String json) {
		try {
			return JSON.readTree(CanvasAgentPlan.decodeStoredAction(json));
		} catch (Exception error) {
			throw invalid("计划动作不可读");
		}
	}
	private static String string(JsonNode node, String field) {
		return node.has(field) ? node.path(field).asText() : null;
	}
	private static Integer integer(JsonNode node, String field) {
		return node.has(field) ? node.path(field).asInt() : null;
	}
	private static String json(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalStateException("计划结果无法保存", error);
		}
	}
	private static Map<String, Object> result(CanvasAgentPlanRepository.AgentPlanRow plan, long version,
			List<String> shots, Map<String, Object> variant, Map<String, Object> prepared) {
		Map<String, Object> result = new LinkedHashMap<>(
				Map.of("planId", plan.id().toString(), "storyboardId", plan.storyboardId().toString(), "draftId",
						plan.draftId().toString(), "editVersion", version, "affectedShotIds", shots));
		if (variant != null) {
			variant = new LinkedHashMap<>(variant);
			variant.computeIfPresent("createdAt",
					(key, value) -> value instanceof java.time.OffsetDateTime date
							? date.toInstant().toString()
							: value.toString());
		}
		result.put("variant", variant);
		result.put("preparedGeneration", prepared);
		return result;
	}
	private static Map<String, Object> readResult(String json) {
		try {
			return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
			});
		} catch (Exception error) {
			throw new IntelligenceException(409, "CANVAS_OPERATION_CONFLICT", "已应用计划的历史结果不可读");
		}
	}
	private static IntelligenceException invalid(String message) {
		return new IntelligenceException(502, "CANVAS_AGENT_INVALID_PLAN", message);
	}
}
