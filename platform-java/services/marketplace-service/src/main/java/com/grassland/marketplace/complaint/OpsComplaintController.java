package com.grassland.marketplace.complaint;

import com.grassland.marketplace.complaint.UserComplaintRepository.ComplaintRow;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 投诉工单处置台（PRD §11.8 客服职责）：{@code GET /api/ops/complaints} 队列（默认 open）+
 * {@code POST /api/ops/complaints/{id}/handle} 受理/办结。
 *
 * <p>鉴权对齐 {@code OpsCaseController}/{@code OpsCommentReviewController}：全部
 * {@code requireOpsOperator}（customer_service/admin）。处置动作：processing=受理调查 /
 * resolved=办结 / dismissed=不成立；办结类必填结论 note（≤500）——「读不到结论」的办结对举报人不可解释。
 * 可重判修正（幂等 UPDATE，对齐 comment_safety_review 复核口径）。
 */
@RestController
public class OpsComplaintController {

	private static final int MAX_LIMIT = 200;
	private static final int MAX_NOTE = 500;

	private final MarketplaceCallerResolver callers;
	private final UserComplaintRepository complaints;

	public OpsComplaintController(MarketplaceCallerResolver callers, UserComplaintRepository complaints) {
		this.callers = callers;
		this.complaints = complaints;
	}

	@GetMapping("/api/ops/complaints")
	public Mono<ResponseEntity<Map<String, Object>>> list(
			@RequestParam(name = "status", defaultValue = "open") String status,
			@RequestParam(name = "limit", defaultValue = "50") int limit, ServerHttpRequest request) {
		String target = normalizeStatus(status);
		int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
		return callers.requireOpsOperator(request)
				.then(complaints.listQueue(target, capped).map(OpsComplaintController::toBody).collectList())
				.map(items -> ResponseEntity.ok(Map.of("success", true,
						"data", Map.of("status", target, "items", items))));
	}

	@PostMapping(value = "/api/ops/complaints/{id}/handle", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> handle(@PathVariable String id,
			@RequestBody HandleRequest body, ServerHttpRequest request) {
		java.util.UUID complaintId = parse(id);
		String status = normalizeStatus(body == null ? null : body.action());
		String note = body == null || body.note() == null ? "" : body.note().trim();
		if (note.length() > MAX_NOTE) {
			throw new MarketplaceException(400, "处置结论最长 " + MAX_NOTE + " 字");
		}
		if (("resolved".equals(status) || "dismissed".equals(status)) && note.isEmpty()) {
			throw new MarketplaceException(400, "办结/不成立必须填写结论");
		}
		return callers.requireOpsOperator(request)
				.flatMap(caller -> complaints.find(complaintId)
						.switchIfEmpty(Mono.error(new MarketplaceException(404, "投诉不存在")))
						.then(complaints.handle(complaintId, status, caller.accountId(),
								note.isEmpty() ? null : note)))
				.map(row -> ResponseEntity.ok(Map.of("success", true, "data", toBody(row))));
	}

	private static String normalizeStatus(String raw) {
		if ("open".equals(raw) || "processing".equals(raw) || "resolved".equals(raw)
				|| "dismissed".equals(raw)) {
			return raw;
		}
		throw new MarketplaceException(400, "status 仅支持 open/processing/resolved/dismissed");
	}

	private static java.util.UUID parse(String raw) {
		try {
			return java.util.UUID.fromString(raw);
		} catch (Exception error) {
			throw new MarketplaceException(400, "投诉标识无效");
		}
	}

	private static Map<String, Object> toBody(ComplaintRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("reporterAccountId", row.reporterAccountId());
		body.put("targetType", row.targetType());
		body.put("targetId", row.targetId());
		body.put("reason", row.reason());
		body.put("description", row.description());
		body.put("status", row.status());
		body.put("handlerAccountId", row.handlerAccountId());
		body.put("resolutionNote", row.resolutionNote());
		body.put("createdAt", row.createdAt());
		body.put("handledAt", row.handledAt());
		return body;
	}

	/** 处置请求：action ∈ processing/resolved/dismissed；办结类 note 必填（≤500）。 */
	public record HandleRequest(String action, String note) {
	}
}
