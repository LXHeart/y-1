package com.grassland.marketplace.commerce;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.reporting.ReportFormat;
import com.grassland.reporting.ReportRenderer;
import com.grassland.reporting.TabularReport;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Consumer, merchant and operations HTTP surface for the commerce MVP. */
@RestController
public class CommerceController {

	private final MarketplaceCallerResolver callers;
	private final CommerceService commerce;
	private final ReferralLinkService referralLinks;

	public CommerceController(MarketplaceCallerResolver callers, CommerceService commerce,
			ReferralLinkService referralLinks) {
		this.callers = callers;
		this.commerce = commerce;
		this.referralLinks = referralLinks;
	}

	/**
	 * Public referral landing-page data. Authentication is only required when the
	 * user orders.
	 */
	@GetMapping("/api/v2/packages/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> packageDetail(@PathVariable String id,
			@RequestParam(required = false) String rlid, ServerHttpRequest request) {
		// 任务书 #98 D98-02：经 rlid 进入购买页即记触达（未登录也记，consumer 为 NULL；
		// 公开端点对失效/不存在链接静默容错——触达是事实，归因与否由下单解析裁决）。
		Mono<Void> touch = referralLinks.recordLandingTouch(rlid,
				callers.resolve(request).onErrorResume(error -> Mono.empty()));
		return touch.then(commerce.publicOffer(id)).map(value -> ResponseEntity.ok(success(offerBody(value))));
	}

	@PostMapping(value = "/api/v2/orders", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> createOrder(@RequestBody CommerceService.CreateOrderCommand body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.createOrder(caller, body))
				.map(order -> ResponseEntity.status(201).body(success(orderBody(order))));
	}

	@GetMapping("/api/v2/orders")
	public Mono<ResponseEntity<Map<String, Object>>> consumerOrders(@RequestParam(defaultValue = "100") int limit,
			ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.listConsumerOrders(caller, limit).map(this::orderBody).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	@GetMapping("/api/v2/orders/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> consumerOrder(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.findConsumerOrder(caller, id))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	/** 消费者主动取消未支付订单（任务书 #41 尾巴）：仅 pending_payment 可取消，409/404 fail-closed。 */
	@PostMapping("/api/v2/orders/{id}/cancel")
	public Mono<ResponseEntity<Map<String, Object>>> cancelOrder(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.cancelByConsumer(caller, id))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	@PostMapping(value = "/api/v2/orders/{id}/refund", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> refund(@PathVariable String id,
			@RequestBody(required = false) RefundRequest body, ServerHttpRequest request) {
		String reason = body == null || body.reason() == null ? "consumer_request" : body.reason();
		Long amount = body == null ? null : body.amountCents();
		return callers.requireUser(request).flatMap(caller -> commerce.requestRefund(caller, id, amount, reason))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	/**
	 * 消费者归因申诉（业务审查 2026-09-07 C01，替代原买家直接改绑）：只提交主张的推荐官与说明，
	 * 不含任何分成比例；处置经运营纠错通道（POST /api/admin/commerce/orders/{id}/attribution-correction）。
	 */
	@PostMapping(value = "/api/v2/orders/{id}/attribution-appeals", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> submitAttributionAppeal(@PathVariable String id,
			@RequestBody CommerceService.AppealCommand body, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.submitAttributionAppeal(caller, id, body))
				.map(appeal -> ResponseEntity.status(201).body(success(appealBody(appeal))));
	}

	/** 消费者查看本人订单最新归因申诉进度（无申诉 → data null）。 */
	@GetMapping("/api/v2/orders/{id}/attribution-appeals")
	public Mono<ResponseEntity<Map<String, Object>>> attributionAppeal(@PathVariable String id,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.attributionAppeal(caller, id))
				.map(appeal -> ResponseEntity.ok(success(appealBody(appeal))))
				// 装配期求值坑：defaultIfEmpty 参数在组装时构造，须 defer（Reactor 惯例）。
				.switchIfEmpty(Mono.defer(() -> Mono.just(ResponseEntity.ok(nullableData(null)))));
	}

	@GetMapping("/api/v2/orders/{id}/attribution")
	public Mono<ResponseEntity<Map<String, Object>>> attribution(@PathVariable String id, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.attributionAllocations(caller, id).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	/**
	 * 归因解释（任务书 #98 §6）：三端（消费者本人/被归因推荐官/客服·财务·风控）同一读模型——
	 * rlid 短码、触达时间、窗口口径、归因成立依据或不可归因原因。
	 */
	@GetMapping("/api/v2/orders/{id}/attribution-explain")
	public Mono<ResponseEntity<Map<String, Object>>> attributionExplain(@PathVariable String id,
			ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.findOrderForAttributionExplain(caller, id))
				.flatMap(referralLinks::explain)
				.map(explain -> ResponseEntity.ok(success(explainBody(explain))));
	}

	/** 运营归因纠错（业务审查 2026-09-07 C01）：按订单冻结规则重算金额，权限=客服/财务/风控。 */
	@PostMapping(value = "/api/admin/commerce/orders/{id}/attribution-correction", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> correctAttribution(@PathVariable String id,
			@RequestBody CommerceService.CorrectionCommand body, ServerHttpRequest request) {
		return callers
				.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMap(caller -> commerce.correctAttribution(caller, id, body))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	/** 运营驳回归因申诉。 */
	@PostMapping(value = "/api/admin/commerce/attribution-appeals/{appealId}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> rejectAttributionAppeal(@PathVariable String appealId,
			@RequestBody(required = false) CommerceService.RejectionCommand body, ServerHttpRequest request) {
		return callers
				.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.flatMap(caller -> commerce.rejectAttributionAppeal(caller, appealId, body))
				.map(appeal -> ResponseEntity.ok(success(appealBody(appeal))));
	}

	/** 归因申诉队列（运营）：默认待处理，status=all 看全量；信封分页同订单/核销列表。 */
	@GetMapping("/api/admin/commerce/attribution-appeals")
	public Mono<ResponseEntity<Map<String, Object>>> adminAttributionAppeals(
			@RequestParam(defaultValue = "open") String status,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
			ServerHttpRequest request) {
		String effectiveStatus = "all".equals(status) ? null : status;
		int safeLimit = clampLimit(limit);
		int safeOffset = Math.max(0, offset);
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.then(Mono.zip(commerce.listAdminAttributionAppeals(effectiveStatus, safeLimit, safeOffset)
						.map(this::appealBody).collectList(), commerce.countAdminAttributionAppeals(effectiveStatus)))
				.map(tuple -> ResponseEntity
						.ok(success(envelope(tuple.getT1(), tuple.getT2(), safeLimit, safeOffset))));
	}

	@PostMapping(value = "/api/v2/orders/{id}/after-sales-dispute", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> afterSalesDispute(@PathVariable String id,
			@RequestBody DisputeRequest body, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.openAfterSalesDispute(caller, id, body == null ? null : body.reason()))
				.map(order -> ResponseEntity.status(201).body(success(orderBody(order))));
	}

	@GetMapping("/api/v2/orders/{id}/after-sales-dispute")
	public Mono<ResponseEntity<Map<String, Object>>> afterSalesDisputeDetail(@PathVariable String id,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.afterSalesDispute(caller, id))
				.map(dispute -> ResponseEntity.ok(success(disputeBody(dispute))));
	}

	@PostMapping(value = "/api/v2/orders/{id}/after-sales-dispute/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> resolveAfterSalesDispute(@PathVariable String id,
			@RequestBody CommerceService.DisputeResolutionCommand body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.resolveAfterSalesDispute(caller, id, body))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	@PostMapping(value = "/api/v2/orders/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> review(@PathVariable String id,
			@RequestBody CommerceService.ReviewCommand body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.review(caller, id, body))
				.map(review -> ResponseEntity.status(201).body(success(review)));
	}

	@PostMapping(value = "/api/v2/merchant/packages", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> createPackage(@RequestBody CommerceService.OfferCommand body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.createOffer(caller, body))
				.map(value -> ResponseEntity.status(201).body(success(offerBody(value))));
	}

	@PutMapping(value = "/api/v2/merchant/packages/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> revisePackage(@PathVariable String id,
			@RequestBody CommerceService.OfferCommand body, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.reviseOffer(caller, id, body))
				.map(value -> ResponseEntity.ok(success(offerBody(value))));
	}

	@PostMapping("/api/v2/merchant/packages/{id}/publish")
	public Mono<ResponseEntity<Map<String, Object>>> publishPackage(@PathVariable String id,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.publishOffer(caller, id))
				.map(value -> ResponseEntity.ok(success(offerBody(value))));
	}

	@PostMapping("/api/v2/merchant/packages/{id}/off-sale")
	public Mono<ResponseEntity<Map<String, Object>>> offSalePackage(@PathVariable String id,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.offSaleOffer(caller, id))
				.map(value -> ResponseEntity.ok(success(offerBody(value))));
	}

	@GetMapping("/api/v2/merchant/packages")
	public Mono<ResponseEntity<Map<String, Object>>> merchantPackages(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce
				.listManagedOffers(caller, organizationId, storeId).map(this::offerBody).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	@GetMapping("/api/v2/merchant/orders")
	public Mono<ResponseEntity<Map<String, Object>>> merchantOrders(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, @RequestParam(defaultValue = "100") int limit,
			ServerHttpRequest request) {
		return callers
				.requireUser(request).flatMap(caller -> commerce
						.listMerchantOrders(caller, organizationId, storeId, limit).map(this::orderBody).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	/** 任务书 #75 卡 B6：推荐官「我的推广」——本人 accepted 的套餐推广任务 + 归因订单漏斗。 */
	@GetMapping("/api/v2/recommender/promotions")
	public Mono<ResponseEntity<Map<String, Object>>> recommenderPromotions(ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.recommenderPromotions(caller).map(this::promotionBody).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	/** 任务书 #75 卡 D2：商家推广统计——本主体（可选门店）全部套餐推广任务漏斗。 */
	@GetMapping("/api/v2/merchant/promotions")
	public Mono<ResponseEntity<Map<String, Object>>> merchantPromotions(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, ServerHttpRequest request) {
		return callers.requireUser(request)
				.flatMap(caller -> commerce.merchantPromotions(caller, organizationId, storeId)
						.map(this::merchantPromotionBody).collectList())
				.map(values -> ResponseEntity.ok(success(values)));
	}

	private Map<String, Object> explainBody(ReferralLinkService.AttributionExplain explain) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("orderId", explain.orderId());
		body.put("attributed", explain.attributed());
		if (explain.recommenderAccountId() != null) {
			body.put("recommenderAccountId", explain.recommenderAccountId());
		}
		if (explain.referralLinkId() != null) {
			body.put("referralLinkId", explain.referralLinkId());
			body.put("shortCode", explain.shortCode());
		}
		if (explain.touchedAt() != null) {
			body.put("touchedAt", explain.touchedAt());
		}
		body.put("windowDays", explain.windowDays());
		body.put("policy", "last_touch");
		body.put("policyVersion", explain.policyVersion());
		body.put("basis", explain.basis());
		body.put("reason", explain.reason());
		return body;
	}

	private Map<String, Object> promotionBody(CommerceRepository.RecommenderPromotion promotion) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("taskId", promotion.taskId());
		body.put("taskTitle", promotion.taskTitle());
		body.put("taskStatus", promotion.taskStatus());
		body.put("packageId", promotion.packageId());
		body.put("packageTitle", promotion.packageTitle());
		body.put("priceCents", promotion.priceCents());
		body.put("commission", commissionBody(promotion.recommenderShareBps(), promotion.recommenderFixedCents()));
		body.put("promotionEnded", promotion.promotionEnded());
		body.put("stats", promotionStats(promotion.orderCount(), promotion.redeemedCount(),
				promotion.pendingSettleCents(), promotion.settledCents(), null));
		return body;
	}

	private Map<String, Object> merchantPromotionBody(CommerceRepository.MerchantPromotion promotion) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("taskId", promotion.taskId());
		body.put("taskTitle", promotion.taskTitle());
		body.put("taskStatus", promotion.taskStatus());
		body.put("packageId", promotion.packageId());
		body.put("packageTitle", promotion.packageTitle());
		body.put("priceCents", promotion.priceCents());
		body.put("stats", promotionStats(promotion.orderCount(), promotion.redeemedCount(),
				promotion.pendingSettleCents(), promotion.settledCents(), promotion.refundedCount()));
		return body;
	}

	/** 佣金形态（任务书 #75 D2）：form=ratio（bps）或 fixed（每单固定分）；二者互斥。 */
	private static Map<String, Object> commissionBody(Integer recommenderShareBps, Long recommenderFixedCents) {
		Map<String, Object> commission = new LinkedHashMap<>();
		commission.put("form", recommenderFixedCents != null ? "fixed" : "ratio");
		commission.put("shareBps", recommenderShareBps);
		if (recommenderFixedCents != null) {
			commission.put("fixedCents", recommenderFixedCents);
		}
		return commission;
	}

	private static Map<String, Object> promotionStats(int orderCount, int redeemedCount, long pendingSettleCents,
			long settledCents, Integer refundedCount) {
		Map<String, Object> stats = new LinkedHashMap<>();
		stats.put("orderCount", orderCount);
		stats.put("redeemedCount", redeemedCount);
		stats.put("pendingSettleCents", pendingSettleCents);
		stats.put("settledCents", settledCents);
		if (refundedCount != null) {
			stats.put("refundedCount", refundedCount);
		}
		return stats;
	}

	@GetMapping("/api/v2/merchant/orders/export")
	public Mono<ResponseEntity<byte[]>> exportMerchantOrders(@RequestParam String organizationId,
			@RequestParam(required = false) String storeId, @RequestParam(required = false) String status,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "csv") String format, ServerHttpRequest request) {
		if (from != null && to != null && !from.isBefore(to)) {
			throw new MarketplaceException(400, "to 必须晚于 from");
		}
		if (status != null && status.length() > 64) {
			throw new MarketplaceException(400, "status 过长");
		}
		ReportFormat reportFormat = ReportFormat.parse(format);
		return callers.requireUser(request)
				.flatMap(caller -> commerce
						.exportMerchantOrders(caller, organizationId, storeId, blank(status), from, to).collectList())
				.map(orders -> reportResponse("merchant-orders", reportFormat, new TabularReport("Merchant Orders",
						List.of("order_id", "package_title", "status", "consumer_account_id", "store_id", "price_cents",
								"merchant_amount_cents", "platform_fee_cents", "recommender_amount_cents",
								"refunded_amount_cents", "created_at", "paid_at", "redeemed_at", "refunded_at"),
						orders.stream()
								.<List<?>>map(order -> List.of(value(order.id()), value(order.packageTitle()),
										value(order.status()), value(order.consumerAccountId()), value(order.storeId()),
										order.priceCents(), order.merchantAmountCents(), order.platformFeeCents(),
										order.recommenderAmountCents(), order.refundedAmountCents(),
										value(order.createdAt()), value(order.paidAt()), value(order.redeemedAt()),
										value(order.refundedAt())))
								.toList())));
	}

	@PostMapping(value = "/api/v2/merchant/redemptions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> redeem(@RequestBody RedemptionRequest body,
			ServerHttpRequest request) {
		return callers.requireUser(request).flatMap(caller -> commerce.redeem(caller, body.code()))
				.map(order -> ResponseEntity.ok(success(orderBody(order))));
	}

	/**
	 * 任务书 #53：信封化 {@code {items, total, limit, offset}}；limit 钳 1–200（≤0 归默认），
	 * offset 负数归 0；保留既有 status 筛选与 {@code created_at DESC} 排序。
	 */
	@GetMapping("/api/admin/commerce/orders")
	public Mono<ResponseEntity<Map<String, Object>>> adminOrders(@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset,
			ServerHttpRequest request) {
		int safeLimit = clampLimit(limit);
		int safeOffset = Math.max(0, offset);
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.then(Mono.zip(
						commerce.listAdminOrders(status, safeLimit, safeOffset).map(this::orderBody).collectList(),
						commerce.countAdminOrders(status)))
				.map(tuple -> ResponseEntity
						.ok(success(envelope(tuple.getT1(), tuple.getT2(), safeLimit, safeOffset))));
	}

	/**
	 * 任务书 #53：单条 {@code status IN ('redeeming','redeemed')} SQL
	 * 统一排序分页（替代原两次查询内存拼接）， 信封化 {@code {items, total, limit, offset}}。
	 */
	@GetMapping("/api/admin/commerce/redemptions")
	public Mono<ResponseEntity<Map<String, Object>>> adminRedemptions(@RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") int offset, ServerHttpRequest request) {
		int safeLimit = clampLimit(limit);
		int safeOffset = Math.max(0, offset);
		return callers.requireRole(request, BackendRole.CUSTOMER_SERVICE, BackendRole.FINANCE, BackendRole.RISK)
				.then(Mono.zip(commerce.listAdminRedemptions(safeLimit, safeOffset).map(this::orderBody).collectList(),
						commerce.countAdminRedemptions()))
				.map(tuple -> ResponseEntity
						.ok(success(envelope(tuple.getT1(), tuple.getT2(), safeLimit, safeOffset))));
	}

	/** 统一分页信封（任务书 #53）；LinkedHashMap 保序。 */
	private static Map<String, Object> envelope(List<Map<String, Object>> items, int total, int limit, int offset) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("items", items);
		data.put("total", total);
		data.put("limit", limit);
		data.put("offset", offset);
		return data;
	}

	/** 任务书 #53 钳制：limit ≤0 归默认 50，上限 200。 */
	private static int clampLimit(int limit) {
		return limit <= 0 ? 50 : Math.min(limit, 200);
	}

	private Map<String, Object> offerBody(OfferDetail detail) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", detail.offer().id());
		body.put("organizationId", detail.offer().organizationId());
		if (detail.offer().storeId() != null)
			body.put("storeId", detail.offer().storeId());
		if (detail.offer().taskId() != null)
			body.put("taskId", detail.offer().taskId());
		body.put("status", detail.offer().status());
		body.put("version", detail.version().version());
		body.put("title", detail.version().title());
		body.put("description", detail.version().description() == null ? "" : detail.version().description());
		body.put("priceCents", detail.version().priceCents());
		body.put("totalStock", detail.version().totalStock());
		body.put("remainingStock", detail.remainingStock());
		if (!detail.inventorySlots().isEmpty())
			body.put("inventorySlots", detail.inventorySlots());
		if (detail.version().fixedRedeemDeadline() != null) {
			body.put("fixedRedeemDeadline", detail.version().fixedRedeemDeadline());
		}
		if (detail.version().validDaysAfterPurchase() != null) {
			body.put("validDaysAfterPurchase", detail.version().validDaysAfterPurchase());
		}
		body.put("recommenderShareBps", detail.version().recommenderShareBps());
		body.put("platformFeeBps", detail.version().platformFeeBps());
		body.put("merchantShareBps", detail.version().merchantShareBps());
		// 任务书 #75 D2：固定佣形态回显（null=比例形态）。
		if (detail.version().recommenderFixedCents() != null) {
			body.put("recommenderFixedCents", detail.version().recommenderFixedCents());
		}
		body.put("policyVersion", detail.version().policyVersion());
		body.put("promotionPath", "/?view=commerce&package=" + detail.offer().id());
		body.put("createdAt", detail.offer().createdAt());
		body.put("updatedAt", detail.offer().updatedAt());
		return body;
	}

	private Map<String, Object> orderBody(Order order) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", order.id());
		body.put("consumerAccountId", order.consumerAccountId());
		body.put("organizationId", order.organizationId());
		if (order.storeId() != null)
			body.put("storeId", order.storeId());
		body.put("packageId", order.packageId());
		body.put("packageVersion", order.packageVersion());
		body.put("packageTitle", order.packageTitle());
		// 任务书 #75：订单的任务归属快照（套餐推广任务期间下单才有值）。
		if (order.taskId() != null)
			body.put("taskId", order.taskId());
		if (order.recommenderAccountId() != null)
			body.put("recommenderAccountId", order.recommenderAccountId());
		body.put("priceCents", order.priceCents());
		body.put("recommenderAmountCents", order.recommenderAmountCents());
		body.put("merchantAmountCents", order.merchantAmountCents());
		body.put("platformFeeCents", order.platformFeeCents());
		body.put("refundedAmountCents", order.refundedAmountCents());
		if (order.refundRequestedAmountCents() != null)
			body.put("refundRequestedAmountCents", order.refundRequestedAmountCents());
		if (order.refundReason() != null)
			body.put("refundReason", order.refundReason());
		body.put("status", order.status());
		body.put("redeemDeadline", order.redeemDeadline());
		// 任务书 #41（D7）：支付截止回显；终态/历史 NULL 行原样 null。
		if (order.paymentDeadline() != null)
			body.put("paymentDeadline", order.paymentDeadline());
		// 任务书 #75 D3：冷静期回显——商家/推荐官据此展示「待结算」与分账时点。
		if (order.splitEligibleAt() != null)
			body.put("splitEligibleAt", order.splitEligibleAt());
		if (order.splitCompletedAt() != null) {
			body.put("splitCompletedAt", order.splitCompletedAt());
			// 任务书 #97 C97-01：已结算退款闸门的服务端驱动字段——前端禁用态据此渲染，不自行推断。
			body.put("refundBlockedReason", CommerceService.SETTLED_NO_REFUND);
		}
		if (order.inventorySlotId() != null) {
			body.put("inventorySlotId", order.inventorySlotId());
			if (order.slotStart() != null)
				body.put("slotStart", order.slotStart());
			if (order.slotEnd() != null)
				body.put("slotEnd", order.slotEnd());
		}
		String code = commerce.redeemCode(order);
		if (code != null)
			body.put("redeemCode", code);
		if (order.providerRef() != null)
			body.put("providerRef", order.providerRef());
		if (order.lastError() != null)
			body.put("lastError", order.lastError());
		body.put("createdAt", order.createdAt());
		if (order.paidAt() != null)
			body.put("paidAt", order.paidAt());
		if (order.redeemedAt() != null)
			body.put("redeemedAt", order.redeemedAt());
		if (order.refundedAt() != null)
			body.put("refundedAt", order.refundedAt());
		return body;
	}

	private Map<String, Object> disputeBody(CommerceModels.AfterSalesDispute dispute) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", dispute.id());
		body.put("orderId", dispute.orderId());
		body.put("consumerAccountId", dispute.consumerAccountId());
		body.put("reason", dispute.reason());
		body.put("status", dispute.status());
		if (dispute.resolution() != null)
			body.put("resolution", dispute.resolution());
		if (dispute.resolutionAmountCents() != null)
			body.put("resolutionAmountCents", dispute.resolutionAmountCents());
		if (dispute.resolutionReason() != null)
			body.put("resolutionReason", dispute.resolutionReason());
		if (dispute.refundOperationId() != null)
			body.put("refundOperationId", dispute.refundOperationId());
		body.put("createdAt", dispute.createdAt());
		if (dispute.resolvedAt() != null)
			body.put("resolvedAt", dispute.resolvedAt());
		return body;
	}

	private Map<String, Object> appealBody(CommerceModels.AttributionAppeal appeal) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", appeal.id());
		body.put("orderId", appeal.orderId());
		body.put("consumerAccountId", appeal.consumerAccountId());
		body.put("claimedRecommenderAccountId", appeal.claimedRecommenderAccountId());
		body.put("reason", appeal.reason());
		body.put("status", appeal.status());
		if (appeal.resolutionNote() != null)
			body.put("resolutionNote", appeal.resolutionNote());
		if (appeal.reviewedBy() != null)
			body.put("reviewedBy", appeal.reviewedBy());
		if (appeal.reviewedAt() != null)
			body.put("reviewedAt", appeal.reviewedAt());
		body.put("createdAt", appeal.createdAt());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	/** data 可为 null 的信封（Map.of 不接受 null 值）。 */
	private static Map<String, Object> nullableData(Object data) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("success", true);
		envelope.put("data", data);
		return envelope;
	}

	private static Object value(Object value) {
		return value == null ? "" : value;
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static ResponseEntity<byte[]> reportResponse(String filename, ReportFormat format, TabularReport report) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(format.mediaType()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(filename + "." + format.extension()).build().toString())
				.body(ReportRenderer.render(report, format));
	}

	public record RefundRequest(Long amountCents, String reason) {
	}
	public record DisputeRequest(String reason) {
	}
	public record RedemptionRequest(String code) {
	}
}
