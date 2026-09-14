package com.grassland.intelligence.creationstudio.text;

import com.grassland.intelligence.creationassistant.CreationDraftView;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-05～07：文本建议的发起、读取与应用。
 *
 * <p>
 * API101-05 ready 200／同键 preparing 202；API101-07 返回 StudioApplyResult （project
 * + appliedVersion + alreadyApplied）。wire 层经 StudioRequestValidator 检真实 JSON
 * 类型；字段白名单封闭。
 */
@RestController
public class TextProposalController {

	private static final Set<String> PREPARE_FIELDS = Set.of("requestId", "draftId", "expectedDraftVersion", "action",
			"source", "selectedBlockIds", "instructions");
	private static final Set<String> APPLY_FIELDS = Set.of("requestId", "expectedDraftVersion", "fields");
	private static final Set<String> ACTIONS = Set.of("adapt-body", "suggest-metadata");
	private static final Set<String> SOURCE_FIELDS = Set.of("id", "contentHash");
	private static final int MAX_INSTRUCTIONS = 2_000;
	private static final int MAX_SELECTED_BLOCKS = 200;

	private final IntelligenceCallerResolver callers;
	private final TextProposalService service;

	public TextProposalController(IntelligenceCallerResolver callers, TextProposalService service) {
		this.callers = callers;
		this.service = service;
	}

	@PostMapping("/api/creation-studio/text-proposals")
	public Mono<ResponseEntity<Map<String, Object>>> prepare(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, PREPARE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int expectedDraftVersion = StudioRequestValidator.requirePositiveInt(body, "expectedDraftVersion");
		String action = StudioRequestValidator.requireEnum(body, "action", ACTIONS);
		UUID sourceDocumentId = null;
		String sourceContentHash = null;
		Object source = body.get("source");
		if (source == null)
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "source 不能为空");
		if (source != null) {
			if (!(source instanceof Map<?, ?> sourceMap)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "source 必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> cast = (Map<String, Object>) sourceMap;
			StudioRequestValidator.rejectUnknownFields(cast, SOURCE_FIELDS);
			sourceDocumentId = StudioRequestValidator.requireUuid(cast, "id");
			sourceContentHash = StudioRequestValidator.requireString(cast, "contentHash", 64);
		}
		List<String> selectedBlockIds = parseBlockIds(body.get("selectedBlockIds"));
		String instructions = StudioRequestValidator.optionalString(body, "instructions", MAX_INSTRUCTIONS);
		var command = new TextProposalService.PrepareCommand(requestId, draftId, expectedDraftVersion, action,
				sourceDocumentId, sourceContentHash, selectedBlockIds, instructions);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.prepare(exchange, caller, command))
				.map(outcome -> ResponseEntity.status(outcome.preparing() ? HttpStatus.ACCEPTED : HttpStatus.OK)
						.body(success(toBody(outcome.proposal()))));
	}

	@GetMapping("/api/creation-studio/text-proposals/{id}")
	public Mono<Map<String, Object>> load(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.loadOwned(parseId(id), caller))
				.map(row -> success(toBody(row)));
	}

	@PostMapping("/api/creation-studio/text-proposals/{id}/apply")
	public Mono<Map<String, Object>> apply(@PathVariable String id, @RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, APPLY_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		int expectedDraftVersion = StudioRequestValidator.requirePositiveInt(body, "expectedDraftVersion");
		Set<String> fields = parseFields(body.get("fields"));
		var command = new TextProposalService.ApplyCommand(requestId, expectedDraftVersion, fields);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.apply(caller, parseId(id), command))
				.map(outcome -> success(Map.of("project", CreationDraftView.of(outcome.draft()).toMap(),
						"appliedVersion", outcome.appliedDraftVersion() == null ? 0 : outcome.appliedDraftVersion(),
						"alreadyApplied", outcome.alreadyApplied())));
	}

	private static UUID parseId(String value) {
		try {
			return UUID.fromString(value);
		} catch (Exception error) {
			throw new IntelligenceException(404, "STUDIO_NOT_FOUND", "建议不存在");
		}
	}

	private static List<String> parseBlockIds(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > MAX_SELECTED_BLOCKS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED",
					"selectedBlockIds 至多 " + MAX_SELECTED_BLOCKS + " 块");
		}
		List<String> ids = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String text) || text.isBlank() || text.length() > 64) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selectedBlockIds 元素无效");
			}
			ids.add(text);
		}
		if (new LinkedHashSet<>(ids).size() != ids.size()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "selectedBlockIds 不允许重复");
		}
		return ids;
	}

	private static Set<String> parseFields(Object raw) {
		if (!(raw instanceof List<?> list) || list.isEmpty()) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "fields 必须是非空数组");
		}
		Set<String> fields = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof String text) || !Set.of("title", "body", "summary").contains(text)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "应用字段不合法");
			}
			if (!fields.add(text))
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "fields 不允许重复");
		}
		return fields;
	}

	static Map<String, Object> toBody(TextProposalRepository.ProposalRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("draftId", row.draftId().toString());
		body.put("requestId", row.requestId());
		body.put("action", row.action());
		body.put("status", row.status());
		body.put("baseDraftVersion", row.baseDraftVersion());
		body.put("baseContentHash", row.baseContentHash());
		Map<String, Object> snapshot = com.grassland.intelligence.creationstudio.plan.PlanJson
				.readJson(row.inputSnapshotJson());
		body.put("source",
				row.sourceDocumentId() == null
						? null
						: Map.of("id", row.sourceDocumentId().toString(), "contentHash",
								snapshot.getOrDefault("sourceContentHash", "")));
		body.put("safety", null);
		body.put("result", row.result().isEmpty() ? null : row.result());
		body.put("runId", row.runId() == null ? null : row.runId().toString());
		body.put("error", row.errorCode() == null ? null : Map.of("code", row.errorCode(), "message", "建议生成失败"));
		body.put("appliedDraftVersion", row.appliedDraftVersion());
		body.put("createdAt", row.createdAt() == null ? null : row.createdAt().toInstant().toString());
		body.put("expiresAt", row.expiresAt() == null ? null : row.expiresAt().toInstant().toString());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
