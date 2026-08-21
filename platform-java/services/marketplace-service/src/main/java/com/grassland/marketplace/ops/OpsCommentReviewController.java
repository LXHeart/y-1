package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.CommentSafetyReviewRepository;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 评论人工复核队列 API（缺口清偿之九遗留清偿）：评论提交时词库 low/medium 命中（advisory， 不拦截）落
 * {@code comment_safety_review} open 行，运营在此复核。
 *
 * <p>
 * 鉴权对齐 {@link OpsCaseController}：全部
 * {@code requireOpsOperator}（customer_service/admin）。 复核结论：confirmed=无问题 /
 * violation=违规（必填 note）；violation 经交付物列表 commentFlagged
 * 透出给商家——平台只标记内容违规，接不接受履约仍是商家的 confirm/reject （分工同 verification_override：平台内容安全
 * ≠ 业务验收）。
 *
 * <p>
 * 无 outbox 事件：交付物列表实时 JOIN 本表（findViolations），无下游消费者需要解耦； 行由提交链路创建，decide 只
 * UPDATE（可重判修正，对齐 override upsert 口径）。
 */
@RestController
public class OpsCommentReviewController {

	private static final int MAX_LIMIT = 200;

	private final MarketplaceCallerResolver callers;
	private final CommentSafetyReviewRepository reviews;

	public OpsCommentReviewController(MarketplaceCallerResolver callers, CommentSafetyReviewRepository reviews) {
		this.callers = callers;
		this.reviews = reviews;
	}

	/** 队列：默认 open；confirmed/violation 查复核史。 */
	@GetMapping("/api/ops/comment-reviews")
	public Mono<ResponseEntity<Map<String, Object>>> list(
			@RequestParam(name = "status", defaultValue = "open") String status,
			@RequestParam(name = "limit", defaultValue = "50") int limit, ServerHttpRequest request) {
		String target = normalizeStatus(status);
		int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
		return callers.requireOpsOperator(request)
				.then(reviews.listQueue(target, capped).map(OpsCommentReviewController::toBody).collectList())
				.map(items -> ResponseEntity
						.ok(Map.of("success", true, "data", Map.of("status", target, "items", items))));
	}

	/** 复核：field ∈ comment/note（V48：每提交×字段一行）；decision ∈ confirmed/violation；violation 必填 note（≤500）。可重判修正。 */
	@PostMapping(value = "/api/ops/comment-reviews/{submissionId}/{field}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> review(@PathVariable String submissionId,
			@PathVariable String field, @RequestBody ReviewRequest body, ServerHttpRequest request) {
		String targetField = normalizeField(field);
		String decision = body == null ? null : body.decision();
		String status = normalizeStatus(decision);
		String note = body.note() == null ? "" : body.note().trim();
		if (note.length() > 500) {
			throw new MarketplaceException(400, "复核原因过长（上限 500 字）");
		}
		if ("violation".equals(status) && note.isEmpty()) {
			throw new MarketplaceException(400, "判定违规必须填写原因");
		}
		return callers.requireOpsOperator(request)
				.flatMap(caller -> reviews.find(submissionId, targetField)
						.switchIfEmpty(Mono.error(new MarketplaceException(404, "该字段无复核记录")))
						.then(reviews.decide(submissionId, targetField, status, caller.accountId(),
								note.isEmpty() ? null : note)))
				.map(row -> ResponseEntity.ok(Map.of("success", true, "data", toRowBody(row))));
	}

	private static String normalizeStatus(String raw) {
		if ("open".equals(raw) || "confirmed".equals(raw) || "violation".equals(raw)) {
			return raw;
		}
		throw new MarketplaceException(400, "status 仅支持 open/confirmed/violation");
	}

	private static String normalizeField(String raw) {
		if (CommentSafetyReviewRepository.FIELD_COMMENT.equals(raw)
				|| CommentSafetyReviewRepository.FIELD_NOTE.equals(raw)) {
			return raw;
		}
		throw new MarketplaceException(400, "field 仅支持 comment/note");
	}

	private static Map<String, Object> toBody(CommentSafetyReviewRepository.QueueRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("submissionId", row.submissionId().toString());
		body.put("field", row.field());
		body.put("commentText", row.commentText());
		body.put("findings", findingsBody(row.findingsJson()));
		body.put("status", row.status());
		body.put("taskId", row.taskId());
		body.put("taskTitle", row.taskTitle());
		body.put("platform", row.platform());
		body.put("recommenderAccountId", row.recommenderAccountId());
		body.put("submissionStatus", row.submissionStatus());
		body.put("submittedAt", row.submittedAt());
		body.put("createdAt", row.createdAt());
		body.put("reviewerAccountId", row.reviewerAccountId());
		body.put("reviewNote", row.reviewNote());
		body.put("reviewedAt", row.reviewedAt());
		return body;
	}

	private static Map<String, Object> toRowBody(CommentSafetyReviewRepository.ReviewRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("submissionId", row.submissionId().toString());
		body.put("field", row.field());
		body.put("status", row.status());
		body.put("reviewerAccountId", row.reviewerAccountId());
		body.put("reviewNote", row.reviewNote());
		body.put("reviewedAt", row.reviewedAt());
		return body;
	}

	/** findings JSON → 纯 List<Map>（服务内无 ObjectMapper bean，响应体不塞 Jackson 节点）。 */
	private static List<Map<String, Object>> findingsBody(String findingsJson) {
		try {
			return MAPPER.readValue(findingsJson == null ? "[]" : findingsJson,
					new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
					});
		} catch (Exception error) {
			return List.of();
		}
	}

	/** 复核请求：decision ∈ confirmed/violation；note 违规必填。 */
	public record ReviewRequest(String decision, String note) {
	}

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
}
