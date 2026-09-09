package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-05：经营看板与异常订单人工确认暂扣。
 *
 * <p>TC98-020 自动标记进队列（三规则）；TC98-021 flagged 不影响结算；TC98-022 人工确认才挂起且带
 * 原因期限（重复确认 409 幂等）；TC98-023 解除后结算恢复；TC98-024 看板指标带来源与窗口标注；
 * 附加：驳回标记不构成暂扣、处理期限超时视图、非治理台角色 403。
 */
class OpsOrderHoldIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceService commerce;

	@Autowired
	OpsOrderHoldService holds;

	@Autowired
	CommerceRepository repository;

	@BeforeEach
	void sandboxFinance() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), any(String.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), any(List.class))).thenReturn(Mono.empty());
	}

	@Test
	void tc98_020RulesFlagCandidatesIntoQueue() {
		Setup setup = setupAttributedOrders(6);
		// 规则 1（退款率）：6 单中 4 单退款（67% ≥ 40%，样本 ≥ 5）→ 标记剩余 2 单未退款在途单。
		for (int i = 0; i < 4; i++) {
			db.sql("UPDATE consumer_order SET status = 'refunded', refunded_amount_cents = price_cents"
					+ " WHERE id = CAST(:id AS uuid)").bind("id", setup.orderId(i)).then().block();
		}
		holds.evaluateRules(50).collectList().block();
		// 队列为全局视图：断言本用例的两条未退款在途单被标记（rule/状态/原因可解释）。
		assertThat(holdIdOf(setup.orderId(4), "referral_refund_rate")).isNotNull();
		assertThat(holdIdOf(setup.orderId(5), "referral_refund_rate")).isNotNull();
		String reason = holdReasonOf(setup.orderId(4), "referral_refund_rate");
		assertThat(reason).contains("退款率");

		// 幂等：重复扫描不产生新行（uq_ops_order_hold_open）。
		Integer count = db.sql("SELECT count(*) AS c FROM ops_order_hold").map(r -> r.get("c", Integer.class))
				.one().block();
		holds.evaluateRules(50).collectList().block();
		Integer countAgain = db.sql("SELECT count(*) AS c FROM ops_order_hold").map(r -> r.get("c", Integer.class))
				.one().block();
		assertThat(countAgain).isEqualTo(count);
	}

	@Test
	void appealBurstAndRlidBurstRulesFlagOrders() {
		// 规则 2（申诉集中度）：同推荐官近窗 3 次被申诉（≥3）→ 标记其未退款在途单。
		Setup setup = setupAttributedOrders(1);
		for (int i = 0; i < 3; i++) {
			String appealOrderId = i == 0 ? setup.orderId(0) : createOrder(UUID.randomUUID().toString(), setup.offerId());
			db.sql("INSERT INTO consumer_order_attribution_appeal(id, order_id, consumer_account_id,"
					+ " claimed_recommender_account_id, reason, status)"
					+ " VALUES (CAST(:id AS uuid), CAST(:order AS uuid), CAST(:consumer AS uuid),"
					+ " CAST(:rec AS uuid), '实际经另一位推荐官链接购买', 'open')")
				.bind("id", UUID.randomUUID().toString()).bind("order", appealOrderId)
					.bind("consumer", UUID.randomUUID().toString()).bind("rec", setup.recommender()).then().block();
		}
		// 规则 3（rlid 激增）：同 rlid 近窗归因 11 单（>10）——SQL 直补归因事实行到既有订单。
		for (int i = 0; i < 11; i++) {
			String filler = createOrder(UUID.randomUUID().toString(), setup.offerId());
			db.sql("INSERT INTO consumer_order_attribution(id, order_id, recommender_account_id, recommender_share_bps,"
					+ " source, reason, actor_account_id, referral_link_id)"
					+ " VALUES (CAST(:id AS uuid), CAST(:order AS uuid), CAST(:rec AS uuid), 1000,"
					+ " 'referral_link', 'last_touch', CAST(:actor AS uuid), :link)")
					.bind("id", UUID.randomUUID().toString()).bind("order", filler)
					.bind("rec", setup.recommender()).bind("actor", setup.recommender()).bind("link", setup.rlid())
					.then().block();
		}
		holds.evaluateRules(50).collectList().block();
		List<Map<String, Object>> queue = queueRows();
		assertThat(queue.stream().filter(row -> row.get("rule").equals("appeal_burst")).count()).isGreaterThanOrEqualTo(1);
		assertThat(queue.stream().filter(row -> row.get("rule").equals("rlid_order_burst")).count()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void tc98_021FlaggedDoesNotAffectSettlement() {
		Setup setup = setupAttributedOrders(5);
		String orderId = setup.orderId(0);
		for (int i = 1; i < 4; i++) {
			db.sql("UPDATE consumer_order SET status = 'refunded', refunded_amount_cents = price_cents"
					+ " WHERE id = CAST(:id AS uuid)").bind("id", setup.orderId(i)).then().block();
		}
		holds.evaluateRules(50).collectList().block();
		redeemAndMakeEligible(setup, orderId);

		// flagged 未确认：结算照常执行（自动标记永不直接碰钱）。
		Order snapshot = findOrder(orderId);
		commerce.attemptSplit(snapshot).block();
		verify(finance, times(1)).split(any(Order.class), any(List.class));
	}

	@Test
	void tc98_022ConfirmHoldsSettlementAndIsIdempotent() {
		Setup setup = setupAttributedOrders(5);
		String orderId = setup.orderId(0);
		for (int i = 1; i < 4; i++) {
			db.sql("UPDATE consumer_order SET status = 'refunded', refunded_amount_cents = price_cents"
					+ " WHERE id = CAST(:id AS uuid)").bind("id", setup.orderId(i)).then().block();
		}
		holds.evaluateRules(50).collectList().block();
		String holdId = holdIdOf(orderId, "referral_refund_rate");
		redeemAndMakeEligible(setup, orderId);

		// 确认暂扣：held + 原因 + 处理期限（默认 72h）。
		client().post().uri("/api/admin/commerce/order-holds/" + holdId + "/confirm").header("X-Grassland-Identity",
				admin()).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("held")
				.jsonPath("$.data.holdDeadlineAt").isNotEmpty();
		// 重复确认 409（幂等键=状态单边胜出）。
		client().post().uri("/api/admin/commerce/order-holds/" + holdId + "/confirm").header("X-Grassland-Identity",
				admin()).exchange().expectStatus().isEqualTo(409);

		// held：分账被挂起（finance.split 不执行、split_completed_at 不落）。
		Order snapshot = findOrder(orderId);
		commerce.attemptSplit(snapshot).block();
		verify(finance, never()).split(any(Order.class), any(List.class));
		assertThat(findOrder(orderId).splitCompletedAt()).isNull();

		// 处理期限超时视图：拨过期限后可见（复用超时机制语义）。
		db.sql("UPDATE ops_order_hold SET hold_deadline_at = now() - interval '1 hour' WHERE id = CAST(:id AS uuid)")
				.bind("id", holdId).then().block();
		client().get().uri("/api/admin/commerce/order-holds/overdue").header("X-Grassland-Identity", admin())
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].id").isEqualTo(holdId);

		// TC98-023 解除后结算恢复（带解除说明）。
		client().post().uri("/api/admin/commerce/order-holds/" + holdId + "/release")
				.header("X-Grassland-Identity", admin()).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "核实为正常集中退款")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("released");
		Order fresh = findOrder(orderId);
		commerce.attemptSplit(fresh).block();
		verify(finance, times(1)).split(any(Order.class), any(List.class));
		assertThat(findOrder(orderId).splitCompletedAt()).isNotNull();
	}

	@Test
	void dismissFlagDoesNotHoldSettlement() {
		Setup setup = setupAttributedOrders(5);
		String orderId = setup.orderId(0);
		for (int i = 1; i < 4; i++) {
			db.sql("UPDATE consumer_order SET status = 'refunded', refunded_amount_cents = price_cents"
					+ " WHERE id = CAST(:id AS uuid)").bind("id", setup.orderId(i)).then().block();
		}
		holds.evaluateRules(50).collectList().block();
		String holdId = holdIdOf(orderId, "referral_refund_rate");
		client().post().uri("/api/admin/commerce/order-holds/" + holdId + "/dismiss")
				.header("X-Grassland-Identity", admin()).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("dismissed");
		redeemAndMakeEligible(setup, orderId);
		commerce.attemptSplit(findOrder(orderId)).block();
		verify(finance, times(1)).split(any(Order.class), any(List.class));
	}

	@Test
	void tc98_024DashboardCarriesSourceAndWindowAnnotations() {
		// 非治理台角色 403（看板仅治理台可见）。
		client().get().uri("/api/admin/commerce/ops-dashboard")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null)).exchange().expectStatus()
				.isForbidden();

		client().get().uri("/api/admin/commerce/ops-dashboard?days=14").header("X-Grassland-Identity", admin())
				.exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.windowDays").isEqualTo(14)
				.jsonPath("$.data.metrics[0].key").isEqualTo("attributedSalesCents")
				.jsonPath("$.data.metrics[0].source").exists()
				.jsonPath("$.data.metrics[0].window").isEqualTo("近 14 天")
				.jsonPath("$.data.metrics[0].note").value(note -> assertThat(String.valueOf(note)).contains("不宣称增量收益"))
				.jsonPath("$.data.metrics[?(@.key=='netCommissionCents')].source").exists();
	}

	// ---------- 造数与工具 ----------

	private record Setup(String merchant, String org, String recommender, String consumer, String offerId,
			String taskId, String rlid, List<Map<String, Object>> orders) {

		String orderId(int index) {
			return String.valueOf(orders.get(index).get("id"));
		}
	}

	private Setup setupAttributedOrders(int count) {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		accept(recommender, merchant, org, task);
		// rlid 发放（rlid 激增规则的归因载体）。
		@SuppressWarnings("unchecked")
		Map<String, Object> link = (Map<String, Object>) client().post().uri("/api/v2/promotion/links")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("taskId", task.get("id"))).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("data");
		String rlid = (String) link.get("referralLinkId");
		java.util.List<Map<String, Object>> orders = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) {
			orders.add(createOrderWithRlid(consumer, (String) offer.get("id"), rlid));
		}
		return new Setup(merchant, org, recommender, consumer, (String) offer.get("id"), (String) task.get("id"),
				rlid, orders);
	}

	private Map<String, Object> createAndPublish(String merchant, String org) {
		Map<String, Object> body = Map.of("organizationId", org, "title", "双人到店套餐", "description", "测试套餐",
				"priceCents", 10000, "totalStock", 100, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000,
				"platformFeeBps", 500, "policyVersion", "commerce-v1");
		@SuppressWarnings("unchecked")
		Map<String, Object> offer = (Map<String, Object>) client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		return offer;
	}

	private Map<String, Object> createPromotionTask(String merchant, String org, String packageId) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", org);
		body.put("title", "套餐推广-" + UUID.randomUUID());
		body.put("platform", "xiaohongshu");
		body.put("storeId", UUID.randomUUID().toString());
		body.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		body.put("commercePackageId", packageId);
		@SuppressWarnings("unchecked")
		Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		client().post().uri("/api/admin/tasks/" + task.get("id") + "/review/approve")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "content_reviewer"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", ((Number) task.get("version")).intValue())).exchange()
				.expectStatus().isOk();
		return task;
	}

	private void accept(String recommender, String merchant, String org, Map<String, Object> task) {
		@SuppressWarnings("unchecked")
		String appId = String.valueOf(((Map<String, Object>) client().post()
				.uri("/api/tasks/" + task.get("id") + "/applications")
				.header("X-Grassland-Identity", sign(recommender, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "带客到店")).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody().get("data")).get("id"));
		client().post().uri("/api/tasks/" + task.get("id") + "/applications/" + appId + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createOrderWithRlid(String consumer, String packageId, String rlid) {
		return (Map<String, Object>) client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId, "referralLinkId", rlid)).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private String createOrder(String consumer, String packageId) {
		return (String) ((Map<String, Object>) client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId)).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody().get("data")).get("id");
	}

	private void redeemAndMakeEligible(Setup setup, String orderId) {
		// 核销码只存哈希——明文取自下单响应（订单 Map 由 Setup 持有）。
		String code = setup.orders().stream().filter(order -> String.valueOf(order.get("id")).equals(orderId))
				.map(order -> String.valueOf(order.get("redeemCode"))).findFirst().orElseThrow();
		client().post().uri("/api/v2/merchant/redemptions")
				.header("X-Grassland-Identity", sign(setup.merchant(), "merchant", setup.org(), "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code)).exchange()
				.expectStatus().isOk();
		db.sql("UPDATE consumer_order SET split_eligible_at = now() - interval '1 second'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", orderId).then().block();
	}

	private Order findOrder(String orderId) {
		return repository.findOrder(orderId).block();
	}

	private String holdReasonOf(String orderId, String rule) {
		return db.sql("SELECT reason FROM ops_order_hold WHERE order_id = CAST(:order AS uuid) AND rule = :rule")
				.bind("order", orderId).bind("rule", rule).map(row -> row.get("reason", String.class)).one().block();
	}

	private String holdIdOf(String orderId, String rule) {
		return db.sql("SELECT id::text AS id FROM ops_order_hold WHERE order_id = CAST(:order AS uuid)"
				+ " AND rule = :rule").bind("order", orderId).bind("rule", rule)
				.map(row -> row.get("id", String.class)).one().block();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> queueRows() {
		return (List<Map<String, Object>>) client().get().uri("/api/admin/commerce/order-holds")
				.header("X-Grassland-Identity", admin()).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody().get("data");
	}

	private String admin() {
		return signWithRole(UUID.randomUUID().toString(), "finance");
	}
}
