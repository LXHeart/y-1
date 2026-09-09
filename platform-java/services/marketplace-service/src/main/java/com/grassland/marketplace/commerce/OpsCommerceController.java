package com.grassland.marketplace.commerce;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 * 任务书 #98 C98-05（§6）：治理台经营看板与异常订单暂扣队列（客服/财务/风控）。
 *
 * <p>看板指标带数据来源与统计窗口标注（D98-06：不把归因销售额宣称为增量收益）；暂扣队列
 * flagged/held 分组 + 确认（带处理期限）/解除/驳回——自动标记永不直接碰钱（flagged ≠ held）。
 */
@RestController
public class OpsCommerceController {

	private final MarketplaceCallerResolver callers;
	private final OpsOrderHoldService holds;

	public OpsCommerceController(MarketplaceCallerResolver callers, OpsOrderHoldService holds) {
		this.callers = callers;
		this.holds = holds;
	}

	@GetMapping("/api/admin/commerce/ops-dashboard")
	public Mono<ResponseEntity<Map<String, Object>>> dashboard(@RequestParam(defaultValue = "30") int days,
			ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.then(holds.dashboard(days))
				.map(body -> ResponseEntity.ok(Map.of("success", true, "data", body)));
	}

	@GetMapping("/api/admin/commerce/order-holds")
	public Mono<ResponseEntity<Map<String, Object>>> queue(@RequestParam(required = false) String status,
			ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMapMany(operator -> holds.queue(status)).map(OpsCommerceController::holdBody).collectList()
				.map(items -> ResponseEntity.ok(success(items)));
	}

	/** 处理期限超时视图（§5.4：72h 到期未处理进超时视图，复用 #96 C96-06 机制语义）。 */
	@GetMapping("/api/admin/commerce/order-holds/overdue")
	public Mono<ResponseEntity<Map<String, Object>>> overdue(ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMapMany(operator -> holds.overdueQueue()).map(OpsCommerceController::holdBody).collectList()
				.map(items -> ResponseEntity.ok(success(items)));
	}

	/** 人工确认暂扣：flagged → held（结算挂起 + 处理期限）；重复处理 409。 */
	@PostMapping("/api/admin/commerce/order-holds/{id}/confirm")
	public Mono<ResponseEntity<Map<String, Object>>> confirm(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMap(operator -> holds.confirm(operator, UUID.fromString(id)))
				.map(hold -> ResponseEntity.ok(success(holdBody(hold))));
	}

	@PostMapping(value = "/api/admin/commerce/order-holds/{id}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> release(@PathVariable String id,
			@RequestBody(required = false) ReleaseRequest body, ServerHttpRequest request) {
		String note = body == null ? null : body.note();
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMap(operator -> holds.release(operator, UUID.fromString(id), note))
				.map(hold -> ResponseEntity.ok(success(holdBody(hold))));
	}

	@PostMapping("/api/admin/commerce/order-holds/{id}/dismiss")
	public Mono<ResponseEntity<Map<String, Object>>> dismiss(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMap(operator -> holds.dismiss(operator, UUID.fromString(id)))
				.map(hold -> ResponseEntity.ok(success(holdBody(hold))));
	}

	private static Map<String, Object> holdBody(OpsOrderHoldRepository.HoldRow hold) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", hold.id());
		body.put("orderId", hold.orderId());
		body.put("rule", hold.rule());
		body.put("reason", hold.reason());
		body.put("status", hold.status());
		body.put("flaggedAt", hold.flaggedAt());
		body.put("confirmedAt", hold.confirmedAt());
		body.put("holdDeadlineAt", hold.holdDeadlineAt());
		body.put("releasedAt", hold.releasedAt());
		body.put("releasedReason", hold.releasedReason());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	public record ReleaseRequest(String note) {
	}
}
