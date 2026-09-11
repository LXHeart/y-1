package com.grassland.intelligence.creationcanvas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationassistant.DraftStatus;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-08/09（任务书 #100 C100-09）：GET/PUT /api/creation-drafts/{id}/canvas。
 *
 * <p>独立画布文档：draft 唯一、revision CAS（expectedRevision=0 仅创建）、document ≤256KiB /
 * 200 节点 / 500 边；storyboardId 必须与草稿存在 API-07 绑定；归档草稿只读。画布写不提升
 * creation_draft.version（布局与正文分轨，§7.3）。
 */
@RestController
public class CreationCanvasController {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final IntelligenceCallerResolver callers;
	private final CreationDraftRepository drafts;
	private final VideoCanvasWorkspaceRepository bindings;
	private final CreationCanvasRepository canvas;

	public CreationCanvasController(IntelligenceCallerResolver callers, CreationDraftRepository drafts,
			VideoCanvasWorkspaceRepository bindings, CreationCanvasRepository canvas) {
		this.callers = callers;
		this.drafts = drafts;
		this.bindings = bindings;
		this.canvas = canvas;
	}

	/** document 用 Map 承接（WebFlux Jackson 3 编解码对 Jackson 2 JsonNode 无法构造）。 */
	public record SaveCanvasRequest(long expectedRevision, Map<String, Object> document) {
	}

	@GetMapping("/api/creation-drafts/{id}/canvas")
	public Mono<ResponseEntity<Map<String, Object>>> getCanvas(@PathVariable String id,
			ServerWebExchange exchange) {
		UUID draftId = parseId(id);
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> requireOwnedDraft(caller.accountId(), draftId))
				.flatMap(draft -> canvas.findByDraftId(draftId)
						.map(row -> ResponseEntity.ok(envelope(view(row))))
						.defaultIfEmpty(ResponseEntity.ok(envelope(null))));
	}

	@PutMapping("/api/creation-drafts/{id}/canvas")
	public Mono<ResponseEntity<Map<String, Object>>> putCanvas(@PathVariable String id,
			@RequestBody SaveCanvasRequest body, ServerWebExchange exchange) {
		UUID draftId = parseId(id);
		if (body == null || body.document() == null || body.document().isEmpty()) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "document 必须是对象"));
		}
		if (body.expectedRevision() < 0) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
					"expectedRevision 必须为 ≥0 的安全整数"));
		}
		String documentJson;
		try {
			documentJson = MAPPER.writeValueAsString(body.document());
		} catch (Exception e) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "document 序列化失败"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> requireOwnedDraft(caller.accountId(), draftId)
						.flatMap(draft -> {
							if (DraftStatus.ARCHIVED.equals(draft.status())) {
								return Mono.error(new IntelligenceException(409, "CANVAS_RESOURCE_LOCKED",
										"归档草稿只读"));
							}
							List<String[]> canonicalRefs = new ArrayList<>();
							String validated = CreationCanvasDocument.validateBody(documentJson, draftId.toString(),
									canonicalRefs);
							String storyboardId = storyboardIdOf(validated);
							Mono<Boolean> crossProjectCheck = canonicalRefs.isEmpty()
									? Mono.just(false)
									: canvas.anyCrossProjectReference(canonicalRefs, storyboardId);
							return requireBoundStoryboard(draftId, storyboardId)
									.then(crossProjectCheck)
									.flatMap(crossProject -> {
										if (crossProject) {
											throw new IntelligenceException(400, "CANVAS_INVALID_INPUT",
													"canonical 引用指向其他项目");
										}
										return save(draftId, caller.accountId(), body.expectedRevision(),
												validated);
									});
						}))
				.map(row -> ResponseEntity.ok(envelope(view(row))));
	}

	private Mono<CreationCanvasRepository.CanvasRow> save(UUID draftId, String accountId, long expectedRevision,
			String documentJson) {
		if (expectedRevision == 0) {
			return canvas.insert(draftId, accountId, documentJson)
					.switchIfEmpty(Mono.defer(() -> canvas.findByDraftId(draftId)
							.flatMap(existing -> Mono.error(new IntelligenceException(409,
									"CANVAS_VERSION_CONFLICT",
									"画布已存在（revision=" + existing.revision() + "），不能以 0 重复创建")))));
		}
		return canvas.findByDraftId(draftId)
				.flatMap(existing -> {
					if (existing.revision() != expectedRevision) {
						return Mono.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT",
								"画布 revision 期望 " + expectedRevision + " 实际 " + existing.revision()));
					}
					return canvas.casUpdate(draftId, expectedRevision, documentJson);
				})
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "CANVAS_VERSION_CONFLICT",
						"画布不存在：expectedRevision>0 只能更新已有文档（首建用 0）")));
	}

	/** 草稿可用性闸（§6.3）：不存在/已删/不归本人 404 同口径（不泄露归属）。 */
	private Mono<com.grassland.intelligence.creationassistant.CreationDraft> requireOwnedDraft(
			String accountId, UUID draftId) {
		return drafts.findById(draftId)
				.filter(draft -> draft.ownerAccountId().equals(accountId) && draft.deletedAt() == null)
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "CANVAS_RESOURCE_NOT_FOUND",
						"草稿不存在")));
	}

	/** document.storyboardId 必须与该草稿存在 API-07 绑定（§6.3）。 */
	private Mono<Void> requireBoundStoryboard(UUID draftId, String storyboardId) {
		return bindings.findByStoryboard(UUID.fromString(storyboardId))
				.filter(binding -> binding.draftId().equals(draftId))
				.switchIfEmpty(Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
						"document.storyboardId 与草稿无绑定关系")))
				.then();
	}

	private static String storyboardIdOf(String documentJson) {
		try {
			return MAPPER.readTree(documentJson).path("storyboardId").asText();
		} catch (Exception e) {
			throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", "document 不是合法 JSON");
		}
	}

	/** LinkedHashMap：data 允许 null（GET 无文档契约），Map.of 不接受 null 值。 */
	private static Map<String, Object> envelope(Object data) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("success", true);
		body.put("data", data);
		return body;
	}

	private static Map<String, Object> view(CreationCanvasRepository.CanvasRow row) {
		Map<String, Object> document;
		try {
			// Map 承载（Jackson 3 编解码对 Jackson 2 JsonNode 无法按树序列化）
			document = MAPPER.readValue(row.documentJson(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
					});
		} catch (Exception e) {
			throw new IllegalStateException("库内画布文档损坏", e);
		}
		return Map.of("id", row.id().toString(), "draftId", row.draftId().toString(),
				"revision", row.revision(), "updatedAt", row.updatedAt().toString(), "document", document);
	}

	private static UUID parseId(String id) {
		try {
			return UUID.fromString(id);
		} catch (Exception e) {
			throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", "id 格式无效");
		}
	}
}
