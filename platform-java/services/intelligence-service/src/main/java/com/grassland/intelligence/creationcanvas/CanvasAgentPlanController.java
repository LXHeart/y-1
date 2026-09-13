package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-13/14（任务书 #100 C100-16）：画布 AI 计划创建与读取。
 *
 * <p>
 * API-13：首次生成 200；同键 preparing 202；同键终态 200 既有计划（不第二次收费）。 API-14：GET 权威行（僵尸
 * preparing 由服务层收口）。apply（API-15）在 C100-17。
 */
@RestController
public class CanvasAgentPlanController {

	private final IntelligenceCallerResolver callers;
	private final CanvasAgentPlanService service;
	private final CanvasAgentActionService actions;

	public CanvasAgentPlanController(IntelligenceCallerResolver callers, CanvasAgentPlanService service,
			CanvasAgentActionService actions) {
		this.callers = callers;
		this.service = service;
		this.actions = actions;
	}

	/** API-15（C100-17）：POST /canvas/plans/{id}/apply——planId 唯一应用结果，重放返回该结果。 */
	@PostMapping("/api/creation-assistant/canvas/plans/{id}/apply")
	public Mono<ResponseEntity<Map<String, Object>>> apply(@PathVariable String id, ServerWebExchange exchange) {
		UUID planId;
		try {
			planId = UUID.fromString(id);
		} catch (Exception e) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效"));
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> actions.apply(caller.accountId(), planId))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
	}

	@PostMapping("/api/creation-assistant/canvas/plans")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		if (!body.keySet().equals(java.util.Set.of("operationId", "draftId", "storyboardId", "instruction",
				"selectedNodeIds", "expectedEditVersion", "expectedCanvasRevision")))
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "请求字段缺失或包含未知字段"));
		UUID operationId = uuidOf(body.get("operationId"), "operationId");
		UUID draftId = uuidOf(body.get("draftId"), "draftId");
		UUID storyboardId = uuidOf(body.get("storyboardId"), "storyboardId");
		if (!(body.get("instruction") instanceof String instruction) || instruction.isBlank()) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "instruction 必填"));
		}
		if (instruction.trim().codePoints().count() > 2000)
			return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "指令最多 2000 字符"));
		if (!(body.get("selectedNodeIds") instanceof java.util.List<?> rawNodes)) {
			return Mono
					.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "selectedNodeIds 必填（可为空数组——返回澄清）"));
		}
		if (rawNodes.size() > 20)
			return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED", "最多选择 20 个节点"));
		if (rawNodes.stream().anyMatch(item -> !(item instanceof String text) || !text.matches("[A-Za-z0-9:_-]{1,96}"))
				|| rawNodes.stream().distinct().count() != rawNodes.size())
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "节点 ID 类型无效或重复"));
		java.util.List<String> selectedNodeIds = rawNodes.stream().map(String.class::cast).toList();
		Long expectedEditVersion = longOf(body.get("expectedEditVersion"), "expectedEditVersion");
		Long expectedCanvasRevision = longOf(body.get("expectedCanvasRevision"), "expectedCanvasRevision");
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.create(exchange, caller,
						new CanvasAgentPlanService.CreatePlanRequest(operationId, draftId, storyboardId,
								selectedNodeIds, expectedEditVersion, expectedCanvasRevision, instruction)))
				.map(outcome -> ResponseEntity.status(outcome.preparing() ? HttpStatus.ACCEPTED : HttpStatus.OK)
						.body(Map.of("success", true, "data", view(outcome.plan()))));
	}

	@GetMapping("/api/creation-assistant/canvas/plans/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable String id, ServerWebExchange exchange) {
		UUID planId;
		try {
			planId = UUID.fromString(id);
		} catch (Exception e) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效"));
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> service.get(caller.accountId(), planId))
				.map(plan -> ResponseEntity.ok(Map.of("success", true, "data", view(plan))));
	}

	private static Map<String, Object> view(CanvasAgentPlanRepository.AgentPlanRow row) {
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", row.id().toString());
		view.put("status", row.status());
		view.put("draftId", row.draftId().toString());
		view.put("storyboardId", row.storyboardId().toString());
		view.put("baseDraftVersion", row.baseDraftVersion());
		view.put("baseEditVersion", row.baseEditVersion());
		view.put("baseCanvasRevision", row.baseCanvasRevision());
		view.put("summary", row.summary());
		view.put("clarification", row.clarification());
		view.put("action", null);
		view.put("runId", row.runId() == null ? null : row.runId().toString());
		view.put("errorCode", row.errorCode());
		if (row.actionJson() != null) {
			try {
				view.put("action", readJson(CanvasAgentPlan.decodeStoredAction(row.actionJson())));
			} catch (IllegalArgumentException invalid) {
				if (!"applied".equals(row.status()))
					view.put("status", "failed");
				view.put("errorCode", "CANVAS_AGENT_INVALID_PLAN");
			}
		}
		view.put("expiresAt", row.expiresAt().toString());
		return view;
	}

	private static Object readJson(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
		} catch (Exception e) {
			return null;
		}
	}

	private static UUID uuidOf(Object value, String field) {
		if (!(value instanceof String text)) {
			throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", field + " 必填");
		}
		try {
			if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
				throw new IllegalArgumentException();
			return UUID.fromString(text);
		} catch (Exception e) {
			throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", field + " 必须是 UUID");
		}
	}

	private static Long longOf(Object value, String field) {
		if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1
				|| ((Number) value).longValue() > 9_007_199_254_740_991L)
			throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", field + " 必须是正安全整数");
		if (value instanceof Integer number) {
			return number.longValue();
		}
		if (value instanceof Long number) {
			return number;
		}
		throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", field + " 必须是整数");
	}
}
