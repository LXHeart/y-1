package com.grassland.intelligence.creationstudio.visual;

import com.grassland.intelligence.creationstudio.CreationStudioProperties;
import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-13～16（C101-10）：视觉任务的创建、读取、列表与取消。 首次/进行中 202，终态重放
 * 200；取消只请求，不假报全部停止。
 */
@RestController
public class VisualJobController {

	private static final Set<String> CREATE_FIELDS = Set.of("requestId", "plan", "quoteId", "selectedItemIds",
			"consistencyMode", "anchorArtifactId", "acknowledgedUnknownAttemptIds");
	private static final Set<String> PLAN_FIELDS = Set.of("id", "revision");
	private static final Set<String> CANCEL_FIELDS = Set.of("requestId", "expectedVersion");

	private final IntelligenceCallerResolver callers;
	private final VisualJobService jobs;
	private final CreationStudioProperties properties;

	public VisualJobController(IntelligenceCallerResolver callers, VisualJobService jobs,
			CreationStudioProperties properties) {
		this.callers = callers;
		this.jobs = jobs;
		this.properties = properties;
	}

	// ---- API101-13 ----

	@PostMapping("/api/creation-studio/visual-jobs")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CREATE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID quoteId = StudioRequestValidator.requireUuid(body, "quoteId");
		if (!(body.get("plan") instanceof Map<?, ?> planMap)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "plan 必须是对象");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> plan = (Map<String, Object>) planMap;
		StudioRequestValidator.rejectUnknownFields(plan, PLAN_FIELDS);
		UUID planId = StudioRequestValidator.requireUuid(plan, "id");
		int planRevision = StudioRequestValidator.requireInt(plan, "revision");
		List<String> selectedItemIds = parseIds(body.get("selectedItemIds"), 36, "selectedItemIds");
		String consistencyMode = StudioRequestValidator.requireEnum(body, "consistencyMode",
				Set.of("reference-image", "prompt-only"));
		UUID anchorArtifactId = StudioRequestValidator.optionalUuid(body, "anchorArtifactId");
		List<UUID> acknowledged = parseUuidList(body.get("acknowledgedUnknownAttemptIds"));
		var command = new VisualJobService.CreateCommand(requestId, planId, quoteId, selectedItemIds, consistencyMode,
				anchorArtifactId, acknowledged);
		if (planRevision < 1) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "plan.revision 必须是正整数");
		}
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> jobs.create(caller, command))
				.map(outcome -> ResponseEntity.status(
						outcome.created() && !isTerminal(outcome.job().status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
						.body(success(toBody(outcome, outcome.artifactsByAttempt()))));
	}

	// ---- API101-14 ----

	@GetMapping("/api/creation-studio/visual-jobs/{id}")
	public Mono<Map<String, Object>> load(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> jobs.loadJob(parseId(id), caller))
				.map(view -> success(toBody(view.toOutcome(), view.artifactsByAttempt())));
	}

	// ---- API101-15 ----

	@GetMapping("/api/creation-studio/visual-jobs")
	public Mono<Map<String, Object>> list(@RequestParam String draftId,
			@RequestParam(value = "limit", defaultValue = "20") int limit,
			@RequestParam(value = "cursor", required = false) String cursor, ServerWebExchange exchange) {
		if (limit < 1 || limit > 50) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "limit 范围 1~50");
		}
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> jobs.listJobs(caller, parseId(draftId), limit, cursor)).map(page -> {
					List<Map<String, Object>> items = new ArrayList<>();
					for (var job : page.jobs()) {
						Map<String, Object> body = new LinkedHashMap<>();
						body.put("id", job.id().toString());
						body.put("state", job.status());
						body.put("version", job.jobVersion());
						body.put("createdAt", job.createdAt() == null ? null : job.createdAt().toString());
						body.put("updatedAt", job.updatedAt() == null ? null : job.updatedAt().toString());
						items.add(body);
					}
					return success(
							Map.of("items", items, "nextCursor", page.nextCursor() == null ? "" : page.nextCursor()));
				}).map(data -> {
					// nextCursor null 语义（§5.1）：空串规范化为 null
					if (data.get("data") instanceof Map<?, ?> map && "".equals(map.get("nextCursor"))) {
						Map<String, Object> fixed = new LinkedHashMap<>((Map<String, ?>) map);
						fixed.put("nextCursor", null);
						return success(fixed);
					}
					return data;
				});
	}

	// ---- API101-16 ----

	@PostMapping("/api/creation-studio/visual-jobs/{id}/cancel")
	public Mono<Map<String, Object>> cancel(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CANCEL_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedVersion = StudioRequestValidator.requireInt(body, "expectedVersion");
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> jobs.cancel(caller, parseId(id), requestId, expectedVersion))
				.map(view -> success(toBody(view.toOutcome(), view.artifactsByAttempt())));
	}

	// ---- helpers ----

	private static boolean isTerminal(String status) {
		return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status)
				|| "partial".equals(status) || "unknown".equals(status);
	}

	static Map<String, Object> toBody(VisualJobService.CreateOutcome outcome,
			Map<UUID, com.grassland.intelligence.creationstudio.visual.VisualArtifact> artifactsByAttempt) {
		var job = outcome.job();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", job.id().toString());
		body.put("requestId", job.requestId());
		body.put("state", job.status());
		body.put("version", job.jobVersion());
		body.put("draftId", job.draftId().toString());
		body.put("plan",
				Map.of("id", job.planId().toString(), "revision", job.planRevision() == null ? 1 : job.planRevision()));
		body.put("quoteId", job.quoteId().toString());
		body.put("cancelRequested", job.cancelRequested());
		body.put("createdAt", job.createdAt() == null ? null : job.createdAt().toInstant().toString());
		body.put("updatedAt", job.updatedAt() == null ? null : job.updatedAt().toInstant().toString());
		List<Map<String, Object>> items = new ArrayList<>();
		for (VisualItemRepository.ItemRow item : outcome.itemRows()) {
			Map<String, Object> view = new LinkedHashMap<>();
			view.put("attemptId", item.id().toString());
			view.put("itemId", item.itemId());
			view.put("position", item.position());
			view.put("state", item.state());
			view.put("runId", item.runId() == null ? null : item.runId().toString());
			view.put("error",
					item.errorCode() == null ? null : Map.of("code", item.errorCode(), "message", "生成失败，详见错误码"));
			view.put("artifact", artifactBody(artifactsByAttempt.get(item.id())));
			items.add(view);
		}
		body.put("items", items);
		return body;
	}

	/**
	 * §6.2 VisualArtifact 完整字段集——候选预览经 originalMediaRef/deliveryMediaRef 取签名 URL。
	 */
	private static Map<String, Object> artifactBody(VisualArtifact artifact) {
		if (artifact == null) {
			return null;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", artifact.id().toString());
		body.put("itemId", artifact.itemId());
		body.put("attemptId", artifact.attemptId().toString());
		body.put("plan", Map.of("id", artifact.planId().toString(), "revision", artifact.planRevision()));
		body.put("originalMediaRef", mediaRef(artifact.originalMediaId()));
		body.put("deliveryMediaRef", mediaRef(artifact.deliveryMediaId()));
		body.put("runId", artifact.runId() == null ? null : artifact.runId().toString());
		body.put("width", artifact.width());
		body.put("height", artifact.height());
		body.put("contentHash", artifact.contentHash());
		body.put("anchorArtifactId",
				artifact.anchorArtifactId() == null ? null : artifact.anchorArtifactId().toString());
		body.put("createdAt", artifact.createdAt() == null ? null : artifact.createdAt().toInstant().toString());
		return body;
	}

	private static Map<String, Object> mediaRef(UUID mediaId) {
		return mediaId == null ? null : Map.of("id", mediaId.toString(), "refType", "media");
	}

	private static List<String> parseIds(Object raw, int maxLength, String field) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 不能为空");
		}
		if (list.size() > 36) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", field + " 至多 36 项");
		}
		List<String> ids = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String text) || text.isBlank() || text.length() > maxLength) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 元素无效");
			}
			ids.add(text);
		}
		if (new LinkedHashSet<>(ids).size() != ids.size()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 不允许重复");
		}
		return ids;
	}

	private static List<UUID> parseUuidList(Object raw) {
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list) || list.size() > 36) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "acknowledgedUnknownAttemptIds 至多 36 项");
		}
		List<UUID> ids = new ArrayList<>();
		for (Object item : list) {
			try {
				ids.add(UUID.fromString(String.valueOf(item)));
			} catch (Exception error) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "acknowledgedUnknownAttemptIds 元素无效");
			}
		}
		return ids;
	}

	private static UUID parseId(String value) {
		try {
			return UUID.fromString(value);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "视觉任务不存在");
		}
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
