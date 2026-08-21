package com.grassland.marketplace.complaint;

import com.grassland.marketplace.complaint.UserComplaintRepository.ComplaintRow;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 用户举报/投诉入口（PRD §11.8——客服「处理投诉、争议和申诉」的用户侧通用入口，V49 落地）：
 * {@code POST /api/complaints} 提交、{@code GET /api/complaints/mine} 查进度。
 *
 * <p>与既有能力分工：交易争议走 trust（/api/trust/disputes，审判官流程）；本工单是平台对象
 * （任务/交付物/内容/订单/用户）的通用举报通道，客服在处置台受理——两类互不替代。防重复：同举报人
 * 同对象同原因的未办结投诉 → 409（刷屏闸门，不阻塞不同原因/已办结后的再次举报）。
 */
@RestController
public class UserComplaintController {

	private static final int MAX_DESCRIPTION = 500;
	private static final int MAX_TARGET_ID = 128;
	private static final Set<String> TARGET_TYPES = Set.of("task", "submission", "content", "order", "user", "other");
	private static final Set<String> REASONS =
			Set.of("spam", "fraud", "inappropriate_content", "rights_infringement", "other");

	private final MarketplaceCallerResolver callers;
	private final UserComplaintRepository complaints;

	public UserComplaintController(MarketplaceCallerResolver callers, UserComplaintRepository complaints) {
		this.callers = callers;
		this.complaints = complaints;
	}

	@PostMapping("/api/complaints")
	public Mono<ResponseEntity<Map<String, Object>>> submit(@RequestBody ComplaintRequest body,
			ServerHttpRequest request) {
		validate(body);
		return callers.requireUser(request)
				.flatMap(caller -> complaints
						.findOpenDuplicate(caller.accountId(), body.targetType(), body.targetId(), body.reason())
						.<ComplaintRow>flatMap(existing -> Mono.error(
								new MarketplaceException(409, "已有同对象的在办投诉，请等待处理或补充新的原因")))
						.switchIfEmpty(Mono.defer(() -> complaints.insert(
								caller.accountId(), body.targetType(), body.targetId(), body.reason(),
								body.description().trim()))))
				.map(created -> ResponseEntity.status(HttpStatus.CREATED)
						.body(Map.of("success", true, "data", toBody(created))));
	}

	@GetMapping("/api/complaints/mine")
	public Mono<ResponseEntity<Map<String, Object>>> mine(ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> complaints.findByReporter(caller.accountId(), 50).collectList())
				.map(rows -> ResponseEntity.ok(Map.of("success", true,
						"data", Map.of("items", rows.stream().map(UserComplaintController::toBody).toList()))));
	}

	private static void validate(ComplaintRequest body) {
		if (body == null) {
			throw new MarketplaceException(400, "请求体不能为空");
		}
		if (!TARGET_TYPES.contains(body.targetType())) {
			throw new MarketplaceException(400, "举报对象类型无效");
		}
		if (!REASONS.contains(body.reason())) {
			throw new MarketplaceException(400, "举报原因无效");
		}
		if (body.targetId() != null && body.targetId().length() > MAX_TARGET_ID) {
			throw new MarketplaceException(400, "举报对象标识最长 " + MAX_TARGET_ID + " 字符");
		}
		String description = body.description() == null ? "" : body.description().trim();
		if (description.isEmpty() || description.length() > MAX_DESCRIPTION) {
			throw new MarketplaceException(400, "问题描述需为 1-" + MAX_DESCRIPTION + " 字");
		}
	}

	private static Map<String, Object> toBody(ComplaintRow row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id().toString());
		body.put("targetType", row.targetType());
		body.put("targetId", row.targetId());
		body.put("reason", row.reason());
		body.put("description", row.description());
		body.put("status", row.status());
		body.put("resolutionNote", row.resolutionNote());
		body.put("createdAt", row.createdAt());
		body.put("handledAt", row.handledAt());
		return body;
	}

	/**
	 * 提交契约：targetType ∈ task/submission/content/order/user/other；reason ∈ spam/fraud/
	 * inappropriate_content/rights_infringement/other；targetId 可空（other 类描述即可）；description ≤500 必填。
	 */
	public record ComplaintRequest(String targetType, String targetId, String reason, String description) {
	}
}
