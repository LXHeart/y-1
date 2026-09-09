package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.ReferralLinkService.ReferralLinkView;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-01（§6）：推广链接发放/列表/本人失效端点。
 *
 * <p>
 * 归因解析不走本控制器——下单 {@code POST /api/v2/orders} 携带 {@code referralLinkId} 由
 * {@link CommerceService#createOrder} 服务端解析（客户端输入不得改变归因结果，D98-04 §4.2）。 Edge
 * 路由经既有 {@code /api/v2} 前缀（ADR-D07 消费者契约组）天然覆盖。
 */
@RestController
public class ReferralLinkController {

	private final MarketplaceCallerResolver callers;
	private final ReferralLinkService referralLinks;

	public ReferralLinkController(MarketplaceCallerResolver callers, ReferralLinkService referralLinks) {
		this.callers = callers;
		this.referralLinks = referralLinks;
	}

	/** 发放：返回 {referralLinkId, url, expiresAt}；url 为站内相对路径，前端拼接 origin 后展示/复制。 */
	@PostMapping(value = "/api/v2/promotion/links", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> issue(@RequestBody IssueLinkRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> referralLinks.issue(caller, body.taskId()))
				.map(link -> ResponseEntity.status(201).body(success(linkBody(link))));
	}

	/** 我的链接列表（含生效状态与失效原因；过期为读时判定）。 */
	@GetMapping("/api/v2/promotion/links")
	public Mono<ResponseEntity<Map<String, Object>>> myLinks(ServerHttpRequest request) {
		return callers.requireUser(request).flatMapMany(referralLinks::listMine).map(this::linkBody).collectList()
				.map(values -> ResponseEntity.ok(success(values)));
	}

	/** 本人失效（ended）：重复终止幂等回显，他人链接 404。 */
	@PostMapping("/api/v2/promotion/links/{id}/end")
	public Mono<ResponseEntity<Map<String, Object>>> end(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> referralLinks.endMine(caller, id))
				.map(link -> ResponseEntity.ok(success(linkBody(link))));
	}

	/**
	 * 治理台按 rlid 查全生命周期（任务书 #98 §5.2/AC-98-10）：发放/触达/归因订单/失效原因； 权限=客服/财务/风控（同 admin
	 * commerce 家族）。
	 */
	@GetMapping("/api/admin/commerce/referral-links/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> lifecycle(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireRole(request, com.grassland.identity.assertion.BackendRole.CUSTOMER_SERVICE,
				com.grassland.identity.assertion.BackendRole.FINANCE, com.grassland.identity.assertion.BackendRole.RISK)
				.then(referralLinks.lifecycle(id))
				.map(lifecycle -> ResponseEntity.ok(success(lifecycleBody(lifecycle))));
	}

	private Map<String, Object> lifecycleBody(ReferralLinkService.ReferralLifecycle lifecycle) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("link", linkBody(lifecycle.link()));
		body.put("touchCount", lifecycle.touchCount());
		body.put("recentTouches", lifecycle.recentTouches().stream().map(touch -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("touchedAt", touch.touchedAt());
			row.put("consumerAccountId",
					touch.consumerAccountId() == null ? null : touch.consumerAccountId().toString());
			row.put("context", touch.context());
			return row;
		}).toList());
		body.put("orders", lifecycle.orders().stream().map(order -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("orderId", order.orderId());
			row.put("status", order.status());
			row.put("priceCents", order.priceCents());
			row.put("recommenderAmountCents", order.recommenderAmountCents());
			row.put("createdAt", order.createdAt());
			return row;
		}).toList());
		return body;
	}

	private Map<String, Object> linkBody(ReferralLinkView link) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("referralLinkId", link.referralLinkId());
		body.put("shortCode", link.shortCode());
		body.put("taskId", link.taskId());
		if (link.packageId() != null) {
			body.put("packageId", link.packageId());
		}
		body.put("url", link.url());
		body.put("status", link.status());
		body.put("endedReason", link.endedReason());
		body.put("createdAt", link.createdAt());
		body.put("expiresAt", link.expiresAt());
		body.put("policyVersion", link.policyVersion());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	public record IssueLinkRequest(String taskId) {
	}
}
