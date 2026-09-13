package com.grassland.intelligence.creationstudio.plan;

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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-08～12：视觉计划的生成、读取、编辑、确认与估算。
 *
 * <p>
 * API101-08 ready 200／同键 preparing 202；API101-10 返回新 revision 的计划； API101-12 返回
 * VisualQuote（无模型调用、无资金预留）。wire 层经 StudioRequestValidator 检真实 JSON
 * 类型，字段白名单封闭（Jackson 3 环境不接收 JsonNode）。
 */
@RestController
public class VisualPlanController {

	private static final Set<String> PREPARE_FIELDS = Set.of("requestId", "draftId", "expectedDraftVersion", "recipe",
			"source", "selectedBlockIds", "strategy", "itemCount", "style", "targetAspect");
	private static final Set<String> RECIPE_FIELDS = Set.of("id", "version");
	private static final Set<String> SOURCE_FIELDS = Set.of("id", "contentHash");
	private static final Set<String> STYLE_FIELDS = Set.of("styleId", "layoutId", "paletteId");
	private static final Set<String> PATCH_FIELDS = Set.of("requestId", "expectedRevision", "document");
	private static final Set<String> CONFIRM_FIELDS = Set.of("requestId", "draftId", "expectedDraftVersion",
			"expectedRevision", "sourceContentHash");
	private static final Set<String> ESTIMATE_FIELDS = Set.of("requestId", "expectedRevision", "selectedItemIds",
			"consistencyMode", "anchorArtifactId");
	private static final Set<String> STRATEGIES = Set.of("story", "information", "visual");
	private static final Set<String> ASPECTS = Set.of("3:4", "9:16", "1:1", "16:9", "2.35:1");
	private static final int MAX_SELECTED_BLOCKS = 200;

	private final IntelligenceCallerResolver callers;
	private final VisualPlanService plans;
	private final VisualQuoteService quotes;

	public VisualPlanController(IntelligenceCallerResolver callers, VisualPlanService plans,
			VisualQuoteService quotes) {
		this.callers = callers;
		this.plans = plans;
		this.quotes = quotes;
	}

	// ---- API101-08 ----

	@PostMapping("/api/creation-studio/visual-plans")
	public Mono<ResponseEntity<Map<String, Object>>> prepare(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, PREPARE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int expectedDraftVersion = StudioRequestValidator.requireInt(body, "expectedDraftVersion");
		Map<String, Object> recipe = requireObjectField(body, "recipe", RECIPE_FIELDS);
		String recipeId = StudioRequestValidator.requireString(recipe, "id", 64);
		String recipeVersion = StudioRequestValidator.requireString(recipe, "version", 32);
		Map<String, Object> source = requireObjectField(body, "source", SOURCE_FIELDS);
		UUID sourceDocumentId = StudioRequestValidator.requireUuid(source, "id");
		String sourceContentHash = StudioRequestValidator.requireString(source, "contentHash", 64);
		List<String> selectedBlockIds = parseBlockIds(body.get("selectedBlockIds"));
		String strategy = StudioRequestValidator.optionalEnum(body, "strategy", STRATEGIES);
		Integer itemCount = StudioRequestValidator.optionalInt(body, "itemCount");
		String styleId = null;
		String layoutId = null;
		String paletteId = null;
		Object styleRaw = body.get("style");
		if (styleRaw != null) {
			if (!(styleRaw instanceof Map<?, ?> styleMap)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "style 必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) styleMap;
			StudioRequestValidator.rejectUnknownFields(cast, STYLE_FIELDS);
			styleId = StudioRequestValidator.optionalString(cast, "styleId", 64);
			layoutId = StudioRequestValidator.optionalString(cast, "layoutId", 64);
			paletteId = StudioRequestValidator.optionalString(cast, "paletteId", 64);
		}
		String targetAspect = StudioRequestValidator.optionalEnum(body, "targetAspect", ASPECTS);
		var command = new VisualPlanService.PrepareCommand(requestId, draftId, expectedDraftVersion, recipeId,
				recipeVersion, sourceDocumentId, sourceContentHash, selectedBlockIds, strategy, itemCount, styleId,
				layoutId, paletteId, targetAspect);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> plans.prepare(exchange, caller, command))
				.map(outcome -> ResponseEntity.status(outcome.preparing() ? HttpStatus.ACCEPTED : HttpStatus.OK)
						.body(success(toBody(outcome))));
	}

	// ---- API101-09 ----

	@GetMapping("/api/creation-studio/visual-plans/{id}")
	public Mono<Map<String, Object>> load(@PathVariable String id,
			@RequestParam(value = "revision", required = false) Integer revision, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> plans.loadOwned(parseId(id), caller, revision))
				.map(outcome -> success(toBody(outcome)));
	}

	// ---- API101-10 ----

	@PatchMapping("/api/creation-studio/visual-plans/{id}")
	public Mono<Map<String, Object>> patch(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, PATCH_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedRevision = StudioRequestValidator.requireInt(body, "expectedRevision");
		Object documentRaw = body.get("document");
		if (!(documentRaw instanceof Map<?, ?> documentMap)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "document 必须是对象");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> document = (Map<String, Object>) documentMap;
		var command = new VisualPlanService.PatchCommand(requestId, expectedRevision, document);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> plans.patch(caller, parseId(id), command))
				.map(outcome -> success(toBody(outcome)));
	}

	// ---- API101-11 ----

	@PostMapping("/api/creation-studio/visual-plans/{id}/confirm")
	public Mono<Map<String, Object>> confirm(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CONFIRM_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int expectedDraftVersion = StudioRequestValidator.requireInt(body, "expectedDraftVersion");
		int expectedRevision = StudioRequestValidator.requireInt(body, "expectedRevision");
		String sourceContentHash = StudioRequestValidator.requireString(body, "sourceContentHash", 64);
		var command = new VisualPlanService.ConfirmCommand(requestId, draftId, expectedDraftVersion, expectedRevision,
				sourceContentHash);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> plans.confirm(caller, parseId(id), command))
				.map(outcome -> success(toBody(outcome)));
	}

	// ---- API101-12 ----

	@PostMapping("/api/creation-studio/visual-plans/{id}/estimate")
	public Mono<Map<String, Object>> estimate(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, ESTIMATE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedRevision = StudioRequestValidator.requireInt(body, "expectedRevision");
		List<String> selectedItemIds = parseBlockIds(body.get("selectedItemIds"));
		String consistencyMode = StudioRequestValidator.requireEnum(body, "consistencyMode",
				Set.of("reference-image", "prompt-only"));
		UUID anchorArtifactId = StudioRequestValidator.optionalUuid(body, "anchorArtifactId");
		var command = new VisualQuoteService.EstimateCommand(requestId, expectedRevision, selectedItemIds,
				consistencyMode, anchorArtifactId);
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> quotes.estimate(caller, parseId(id), command)).map(result -> {
					// §6.3 VisualQuote：响应含 id（API101-13 创建任务按 quoteId 回读核对快照）
					Map<String, Object> quoteBody = new LinkedHashMap<>(result.quote().quote());
					quoteBody.put("id", result.quote().id().toString());
					return success(quoteBody);
				});
	}

	// ---- helpers ----

	private static UUID parseId(String value) {
		try {
			return UUID.fromString(value);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "计划不存在");
		}
	}

	private static Map<String, Object> requireObjectField(Map<String, Object> body, String field, Set<String> allowed) {
		Object value = body.get(field);
		if (!(value instanceof Map<?, ?> map)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", field + " 必须是对象");
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> cast = (Map<String, Object>) map;
		StudioRequestValidator.rejectUnknownFields(cast, allowed);
		return cast;
	}

	private static List<String> parseBlockIds(Object raw) {
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list) || list.size() > MAX_SELECTED_BLOCKS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "列表至多 " + MAX_SELECTED_BLOCKS + " 项");
		}
		List<String> ids = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String text) || text.isBlank() || text.length() > 64) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "列表元素无效");
			}
			ids.add(text);
		}
		if (new LinkedHashSet<>(ids).size() != ids.size()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "列表不允许重复");
		}
		return ids;
	}

	/** VisualPlan 视图（§6.2）：document 为空 map 视为 null（preparing／failed）。 */
	static Map<String, Object> toBody(VisualPlanService.Outcome outcome) {
		VisualPlan.PlanRow row = outcome.plan();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("draftId", row.draftId().toString());
		body.put("status", row.status());
		body.put("revision", row.currentRevision());
		body.put("confirmedRevision", row.confirmedRevision());
		body.put("source", Map.of("id", row.sourceDocumentId().toString(), "contentHash", row.sourceContentHash()));
		body.put("baseDraftVersion", row.baseDraftVersion());
		body.put("baseContentHash", row.baseContentHash());
		body.put("stale", outcome.stale());
		body.put("document", outcome.document() == null || outcome.document().isEmpty() ? null : outcome.document());
		body.put("runId", row.runId() == null ? null : row.runId().toString());
		body.put("error", row.errorCode() == null ? null : Map.of("code", row.errorCode(), "message", "计划生成失败"));
		body.put("createdAt", row.createdAt() == null ? null : row.createdAt().toInstant().toString());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
