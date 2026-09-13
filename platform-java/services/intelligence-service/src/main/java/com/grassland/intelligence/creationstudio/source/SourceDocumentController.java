package com.grassland.intelligence.creationstudio.source;

import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 任务书 #101 API101-03/04：{@code POST /api/creation-studio/sources}（首次 201、重放
 * 200）与 {@code GET /api/creation-studio/sources/{id}}。
 *
 * <p>
 * wire 层经 {@link StudioRequestValidator} 先检真实 JSON 类型再构造 DTO：拒绝未知字段、
 * 浮点整数、字符串布尔；sourceRefs 元素为封闭形状（id/title/url/location/accessedAt），
 * 解析错误只返回字段名与原因，不回显原值。
 */
@RestController
public class SourceDocumentController {

	private static final Set<String> CREATE_FIELDS = Set.of("requestId", "draftId", "expectedDraftVersion", "kind",
			"title", "text", "sourceRefs");
	private static final Set<String> SOURCE_REF_FIELDS = Set.of("id", "title", "url", "location", "accessedAt");
	private static final Set<String> KINDS = Set.of("plain-text", "markdown", "draft-content");
	private static final int MAX_TITLE_CODE_POINTS = 200;
	private static final int MAX_SOURCE_REFS = 20;
	private static final int MAX_REF_TEXT = 300;

	private final IntelligenceCallerResolver callers;
	private final SourceDocumentService service;

	public SourceDocumentController(IntelligenceCallerResolver callers, SourceDocumentService service) {
		this.callers = callers;
		this.service = service;
	}

	@PostMapping("/api/creation-studio/sources")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body,
			ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, CREATE_FIELDS);
		UUID requestId = StudioRequestValidator.requireUuid(body, "requestId");
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int expectedDraftVersion = StudioRequestValidator.requireInt(body, "expectedDraftVersion");
		String kind = StudioRequestValidator.requireEnum(body, "kind", KINDS);
		String title = StudioRequestValidator.optionalString(body, "title", MAX_TITLE_CODE_POINTS);
		String text = StudioRequestValidator.optionalString(body, "text", SourceDocumentParser.MAX_CODE_POINTS);
		List<Map<String, Object>> sourceRefs = parseSourceRefs(body.get("sourceRefs"));
		SourceDocumentService.CreateCommand command = new SourceDocumentService.CreateCommand(requestId, draftId,
				expectedDraftVersion, kind, title, text, sourceRefs);
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.create(caller, command))
				.map(result -> ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
						.body(success(toBody(result.document()))));
	}

	@GetMapping("/api/creation-studio/sources/{id}")
	public Mono<Map<String, Object>> load(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.loadOwned(id, caller))
				.map(document -> success(toBody(document)));
	}

	private static List<Map<String, Object>> parseSourceRefs(Object raw) {
		if (raw == null) {
			return List.of();
		}
		if (!(raw instanceof List<?> list)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "sourceRefs 必须是数组");
		}
		if (list.size() > MAX_SOURCE_REFS) {
			throw new IntelligenceException(400, "STUDIO_LIMIT_EXCEEDED", "sourceRefs 至多 " + MAX_SOURCE_REFS + " 条");
		}
		List<Map<String, Object>> refs = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?>)) {
				throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "sourceRefs 元素必须是对象");
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> refRaw = (Map<String, Object>) item;
			StudioRequestValidator.rejectUnknownFields(refRaw, SOURCE_REF_FIELDS);
			Map<String, Object> ref = new LinkedHashMap<>();
			ref.put("id", StudioRequestValidator.requireString(refRaw, "id", 64));
			for (String field : List.of("title", "url", "location", "accessedAt")) {
				String value = StudioRequestValidator.optionalString(refRaw, field, MAX_REF_TEXT);
				if (value != null) {
					ref.put(field, value);
				}
			}
			refs.add(ref);
		}
		return refs;
	}

	static Map<String, Object> toBody(SourceDocument document) {
		List<Map<String, Object>> blocks = new ArrayList<>();
		for (SourceDocument.Block block : document.blocks()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("id", block.id());
			item.put("kind", block.kind());
			item.put("position", block.position());
			item.put("startCodePoint", block.startCodePoint());
			item.put("endCodePoint", block.endCodePoint());
			item.put("text", block.text());
			item.put("textHash", block.textHash());
			blocks.add(item);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", document.id().toString());
		body.put("draftId", document.draftId().toString());
		body.put("schemaVersion", document.schemaVersion());
		body.put("kind", document.kind());
		body.put("title", document.title());
		body.put("rawText", document.rawText());
		body.put("normalizedMarkdown", document.normalizedMarkdown());
		body.put("contentHash", document.contentHash());
		body.put("blocks", blocks);
		body.put("sourceRefs", document.sourceRefs());
		body.put("warnings", document.warnings());
		body.put("createdAt", document.createdAt() == null ? null : document.createdAt().toString());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
