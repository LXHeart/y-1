package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-02：7 天 last-touch 归因窗口与可解释归因。
 *
 * <p>
 * TC98-006 窗口内跨访问归因（隔日触达下单）；TC98-007 窗口外不归因且 422 可解释；TC98-008 多链接 last-touch
 * 后触达胜出（即使订单携带旧 rlid）；未登录触达落行 + 订单时触达兜底（context=order）； TC98-009
 * 三端（消费者/被归因推荐官/治理台）解释字段一致 + 无关第三方 403；TC98-010 治理台按 rlid 查全生命周期（触达/订单/失效原因）。
 */
class ReferralAttributionWindowIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@BeforeEach
	void sandboxFinance() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), any(String.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class))).thenReturn(Mono.empty());
	}

	/** TC98-006：触达（登录态）隔日下单——窗口内跨访问归因成立，触达时间随行走。 */
	@Test
	void inWindowCrossVisitAttributionCarriesTouchTime() {
		Setup setup = setupAcceptedPromotion();
		String rlid = issueRlid(setup.recommender(), setup.task());
		// 消费者经链接进入购买页（登录态触达），隔日再下单（SQL 拨过 1 天模拟跨访问）。
		touch(setup.consumer(), (String) setup.offer().get("id"), rlid);
		backdateTouch(rlid, 1);

		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), rlid);
		assertThat(order.get("recommenderAccountId")).isEqualTo(setup.recommender());

		client().get().uri("/api/v2/orders/" + order.get("id") + "/attribution-explain")
				.header("X-Grassland-Identity", sign(setup.consumer(), null)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.attributed").isEqualTo(true).jsonPath("$.data.basis")
				.isEqualTo("last_touch").jsonPath("$.data.shortCode").isNotEmpty().jsonPath("$.data.windowDays")
				.isEqualTo(7).jsonPath("$.data.policyVersion").isEqualTo("last_touch_7d_v1");
		// 触达时间是隔日的旧行（跨访问），不是下单时刻。
		java.time.Instant touchedAt = touchTimeOf(order.get("id"));
		assertThat(touchedAt.isBefore(java.time.Instant.now().minusSeconds(20 * 3600))).isTrue();
	}

	/** TC98-007：窗口外触达不归因——422 attribution_window_expired 可解释，无归因订单仍可建。 */
	@Test
	void outOfWindowTouchDoesNotAttributeWithExplainableReason() {
		Setup setup = setupAcceptedPromotion();
		String rlid = issueRlid(setup.recommender(), setup.task());
		touch(setup.consumer(), (String) setup.offer().get("id"), rlid);
		backdateTouch(rlid, 8);

		client().post().uri("/api/v2/orders").header("X-Grassland-Identity", sign(setup.consumer(), null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", setup.offer().get("id"), "referralLinkId", rlid)).exchange()
				.expectStatus().isEqualTo(422).expectBody().jsonPath("$.blockedReason")
				.isEqualTo("attribution_window_expired").jsonPath("$.error")
				.value(msg -> assertThat(String.valueOf(msg)).contains("归因窗口"));

		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), null);
		assertThat(order.get("recommenderAccountId")).isNull();
	}

	/** TC98-008：同一消费者先后经两条 rlid——后触达胜出（订单携带旧 rlid 也归因后触达推荐官）。 */
	@Test
	void lastTouchWinsAcrossMultipleLinks() {
		Setup setup = setupAcceptedPromotion();
		String anotherRecommender = UUID.randomUUID().toString();
		accept(anotherRecommender, setup.merchant(), setup.org(), setup.task());
		String rlidOld = issueRlid(setup.recommender(), setup.task());
		String rlidNew = issueRlid(anotherRecommender, setup.task());

		touch(setup.consumer(), (String) setup.offer().get("id"), rlidOld);
		backdateTouch(rlidOld, 3);
		touch(setup.consumer(), (String) setup.offer().get("id"), rlidNew);
		backdateTouch(rlidNew, 1);

		// 订单携带旧 rlid（旧标签页场景）：last-touch 归因到后触达的 rlidNew → 另一位推荐官。
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), rlidOld);
		assertThat(order.get("recommenderAccountId")).isEqualTo(anotherRecommender);

		client().get().uri("/api/v2/orders/" + order.get("id") + "/attribution-explain")
				.header("X-Grassland-Identity", sign(setup.consumer(), null)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.referralLinkId").isEqualTo(rlidNew).jsonPath("$.data.basis")
				.isEqualTo("last_touch");
	}

	/** 未登录触达也记行（consumer NULL）；下单（无登录态触达）以订单请求为触达事实（context=order）。 */
	@Test
	void anonymousTouchRecordedAndOrderTimeTouchFallsBack() {
		Setup setup = setupAcceptedPromotion();
		String rlid = issueRlid(setup.recommender(), setup.task());

		// 未登录进入购买页：触达行落库，consumer NULL。
		client().get().uri("/api/v2/packages/" + setup.offer().get("id") + "?rlid=" + rlid).exchange().expectStatus()
				.isOk();
		Long anonymous = db.sql(
				"SELECT count(*) AS c FROM referral_touch WHERE referral_link_id = :link AND consumer_account_id IS NULL")
				.bind("link", rlid).map(row -> row.get("c", Long.class)).one().block();
		assertThat(anonymous).isEqualTo(1L);

		// 登录下单（无登录态触达）：订单请求即触达事实，归因成立（touched_at=下单时刻）。
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), rlid);
		assertThat(order.get("recommenderAccountId")).isEqualTo(setup.recommender());
		client().get().uri("/api/v2/orders/" + order.get("id") + "/attribution-explain")
				.header("X-Grassland-Identity", sign(setup.consumer(), null)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.basis").isEqualTo("order_time").jsonPath("$.data.attributed")
				.isEqualTo(true);
	}

	/** TC98-009：三端解释字段一致（消费者/被归因推荐官/治理台角色）；无关第三方 403。 */
	@Test
	void explainVisibleToThreePartiesWithIdenticalFields() {
		Setup setup = setupAcceptedPromotion();
		String rlid = issueRlid(setup.recommender(), setup.task());
		touch(setup.consumer(), (String) setup.offer().get("id"), rlid);
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), rlid);
		String path = "/api/v2/orders/" + order.get("id") + "/attribution-explain";

		Map<String, Object> consumerView = explain(sign(setup.consumer(), null), path);
		Map<String, Object> recommenderView = explain(sign(setup.recommender(), "recommender"), path);
		Map<String, Object> opsView = explain(signWithRole(UUID.randomUUID().toString(), "customer_service"), path);
		assertThat(recommenderView).isEqualTo(consumerView);
		assertThat(opsView).isEqualTo(consumerView);

		// 无关第三方不可见（§6：403 越权）。
		client().get().uri(path).header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null)).exchange()
				.expectStatus().isEqualTo(403);
	}

	/** TC98-010：治理台按 rlid 查全生命周期——发放/触达/归因订单/失效原因。 */
	@Test
	void adminLifecycleShowsLinkTouchesOrdersAndEndReason() {
		Setup setup = setupAcceptedPromotion();
		String rlid = issueRlid(setup.recommender(), setup.task());
		touch(setup.consumer(), (String) setup.offer().get("id"), rlid);
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), rlid);
		client().post().uri("/api/v2/promotion/links/" + rlid + "/end")
				.header("X-Grassland-Identity", sign(setup.recommender(), "recommender")).exchange().expectStatus()
				.isOk();

		client().get().uri("/api/admin/commerce/referral-links/" + rlid)
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.link.referralLinkId").isEqualTo(rlid)
				.jsonPath("$.data.link.status").isEqualTo("ended").jsonPath("$.data.link.endedReason")
				.isEqualTo("manual").jsonPath("$.data.touchCount").isEqualTo(1).jsonPath("$.data.orders[0].orderId")
				.isEqualTo(order.get("id")).jsonPath("$.data.orders[0].recommenderAmountCents").isEqualTo(1000);

		// 非治理台角色不可查。
		client().get().uri("/api/admin/commerce/referral-links/" + rlid)
				.header("X-Grassland-Identity", sign(setup.consumer(), null)).exchange().expectStatus().isForbidden();
	}

	// ---------- 造数与工具 ----------

	private record Setup(String merchant, String org, String recommender, String consumer, Map<String, Object> offer,
			Map<String, Object> task) {
	}

	private Setup setupAcceptedPromotion() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 10000, 10);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		accept(recommender, merchant, org, task);
		return new Setup(merchant, org, recommender, consumer, offer, task);
	}

	private Map<String, Object> createAndPublishPackage(String merchant, String org, long priceCents, int stock) {
		Map<String, Object> body = Map.of("organizationId", org, "title", "双人到店套餐", "description", "测试套餐", "priceCents",
				priceCents, "totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000,
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
		String appId = String
				.valueOf(((Map<String, Object>) client().post().uri("/api/tasks/" + task.get("id") + "/applications")
						.header("X-Grassland-Identity", sign(recommender, "recommender"))
						.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "带客到店")).exchange()
						.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("data"))
						.get("id"));
		client().post().uri("/api/tasks/" + task.get("id") + "/applications/" + appId + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private String issueRlid(String recommender, Map<String, Object> task) {
		Map<String, Object> link = (Map<String, Object>) client().post().uri("/api/v2/promotion/links")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("taskId", task.get("id"))).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("data");
		return (String) link.get("referralLinkId");
	}

	/** 登录态触达：GET 套餐详情带 rlid。 */
	private void touch(String consumer, String packageId, String rlid) {
		client().get().uri("/api/v2/packages/" + packageId + "?rlid=" + rlid)
				.header("X-Grassland-Identity", sign(consumer, null)).exchange().expectStatus().isOk();
	}

	/** IT 直造跨访问时序（仓库惯例：SQL 置位模拟时间流逝）。 */
	private void backdateTouch(String rlid, long days) {
		db.sql("UPDATE referral_touch SET touched_at = now() - CAST(:days || ' days' AS interval)"
				+ " WHERE referral_link_id = :link").bind("days", String.valueOf(days)).bind("link", rlid).then()
				.block();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createOrder(String consumer, String packageId, String rlid) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("packageId", packageId);
		if (rlid != null) {
			body.put("referralLinkId", rlid);
		}
		return (Map<String, Object>) client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> explain(String auth, String path) {
		return (Map<String, Object>) client().get().uri(path).header("X-Grassland-Identity", auth).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("data");
	}

	private java.time.Instant touchTimeOf(Object orderId) {
		java.time.OffsetDateTime value = db
				.sql("SELECT touched_at FROM consumer_order_attribution WHERE order_id = CAST(:id AS uuid)")
				.bind("id", String.valueOf(orderId)).map(row -> row.get("touched_at", java.time.OffsetDateTime.class))
				.one().block();
		return value == null ? null : value.toInstant();
	}
}
