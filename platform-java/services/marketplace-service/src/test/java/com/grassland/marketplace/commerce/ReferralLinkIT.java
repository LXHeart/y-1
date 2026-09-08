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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-01：不透明推广链接（rlid）发放与解析。
 *
 * <p>TC98-001 发放资格与越权（无报名 403、重复发放幂等、id 不透明）；TC98-002 失效链接下单
 * 不归因（422 可解释 + 无归因订单仍可建）；TC98-003 rlid 与旧参数互斥 400；TC98-004 结束推广
 * 联动失效（promotion_ends_at 过点 → 422 promotion_ended）；TC98-005 旧参数兼容期行为不变；
 * 附加：rlid 有效归因（分成/任务快照冻结）、自购零佣（C01 口径一致）、过期读时判定、他人链接 404、
 * 本人重复终止幂等、我的链接列表状态与失效原因。
 */
class ReferralLinkIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	ReferralLinkService referralLinks;

	@BeforeEach
	void sandboxFinance() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), any(String.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class))).thenReturn(Mono.empty());
	}

	/** TC98-001 + 列表/终止语义：资格 403、幂等发放、不透明 id、我的链接列表、本人终止与重复终止。 */
	@Test
	void issuanceRequiresAcceptedApplicationAndIsIdempotent() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 10000, 10);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));

		// 未报名（无 accepted 申请）→ 403 越权（D98-05 §5.1：发放仅限资格成立的推荐官本人）。
		client().post().uri("/api/v2/promotion/links").header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("taskId", task.get("id"))).exchange()
				.expectStatus().isEqualTo(403).expectBody().jsonPath("$.error")
				.value(msg -> assertThat(String.valueOf(msg)).contains("接单资格"));

		accept(recommender, merchant, org, task);

		// 资格成立 → 201；id 不透明（非账号 ID、非 uuid）且带相对 url 与过期时间。
		Map<String, Object> link = issueLink(recommender, (String) task.get("id"));
		String rlid = (String) link.get("referralLinkId");
		assertThat(rlid).hasSize(24).doesNotContain("-").isNotEqualTo(recommender).matches("[A-Za-z0-9]{24}");
		assertThat((String) link.get("url")).isEqualTo("/?view=commerce&package=" + offer.get("id") + "&rlid=" + rlid);
		assertThat(link.get("expiresAt")).isNotNull();
		assertThat(link.get("status")).isEqualTo("active");
		assertThat(link.get("policyVersion")).isEqualTo("last_touch_7d_v1");

		// 重复发放幂等：同任务返回同一现行链接。
		Map<String, Object> again = issueLink(recommender, (String) task.get("id"));
		assertThat(again.get("referralLinkId")).isEqualTo(rlid);

		// 我的链接列表：状态与失效原因字段在位（发放与终止两态可见）。
		client().get().uri("/api/v2/promotion/links").header("X-Grassland-Identity", sign(recommender, "recommender"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data[0].referralLinkId").isEqualTo(rlid)
				.jsonPath("$.data[0].status").isEqualTo("active").jsonPath("$.data[0].endedReason").doesNotExist();

		// 他人终止他人链接 → 404（不可删改他人，D98-05）。
		String stranger = UUID.randomUUID().toString();
		client().post().uri("/api/v2/promotion/links/" + rlid + "/end")
				.header("X-Grassland-Identity", sign(stranger, "recommender")).exchange().expectStatus().isNotFound();

		// 本人终止 → ended + manual；重复终止幂等回显。
		client().post().uri("/api/v2/promotion/links/" + rlid + "/end")
				.header("X-Grassland-Identity", sign(recommender, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("ended").jsonPath("$.data.endedReason")
				.isEqualTo("manual");
		client().post().uri("/api/v2/promotion/links/" + rlid + "/end")
				.header("X-Grassland-Identity", sign(recommender, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("ended");
	}

	/** TC98-002：失效链接下单不归因（422 可解释）——订单仍可无归因创建（重试语义，AC-98 前提）。 */
	@Test
	void endedLinkOrderIsRejectedWithReasonAndOrderStillCreatable() {
		Setup setup = setupAcceptedPromotion();
		String rlid = (String) issueLink(setup.recommender(), (String) setup.task().get("id")).get("referralLinkId");
		client().post().uri("/api/v2/promotion/links/" + rlid + "/end")
				.header("X-Grassland-Identity", sign(setup.recommender(), "recommender")).exchange().expectStatus()
				.isOk();

		createOrderWithRlid(setup.consumer(), (String) setup.offer().get("id"), rlid, 422, "link_ended");

		// 无归因订单照常创建（前端捕获 422 后去 rlid 重试的同一语义）。
		// taskId 为任务归属快照（下单时有进行中推广任务即落），「无归因」判据是 recommenderAccountId。
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), null, null);
		assertThat(order.get("recommenderAccountId")).isNull();
	}

	/** TC98-003：referralLinkId 与旧 recommenderAccountId 同传 400。 */
	@Test
	void referralLinkIdAndLegacyParamAreMutuallyExclusive() {
		Setup setup = setupAcceptedPromotion();
		String rlid = (String) issueLink(setup.recommender(), (String) setup.task().get("id")).get("referralLinkId");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("packageId", setup.offer().get("id"));
		body.put("recommenderAccountId", setup.recommender());
		body.put("referralLinkId", rlid);
		client().post().uri("/api/v2/orders").header("X-Grassland-Identity", sign(setup.consumer(), null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error").value(msg -> assertThat(String.valueOf(msg)).contains("不能同时提供"));
	}

	/** TC98-004：结束推广联动失效——promotion_ends_at 过点后新下单 422 promotion_ended。 */
	@Test
	void promotionEndInvalidatesLinkForNewOrders() {
		Setup setup = setupAcceptedPromotion();
		String rlid = (String) issueLink(setup.recommender(), (String) setup.task().get("id")).get("referralLinkId");

		// 结束推广（promotion_ends_at 已落且过点 = 推广结束，与归因资格闸同判据）。
		db.sql("UPDATE task SET promotion_ends_at = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", (String) setup.task().get("id")).then().block();

		createOrderWithRlid(setup.consumer(), (String) setup.offer().get("id"), rlid, 422, "promotion_ended");
	}

	/** rlid 有效路径：归因落行、任务快照与分成金额按下单冻结规则（AC-98-02 与 C01 一致）。 */
	@Test
	void activeLinkAttributesOrderWithFrozenSnapshot() {
		Setup setup = setupAcceptedPromotion();
		String rlid = (String) issueLink(setup.recommender(), (String) setup.task().get("id")).get("referralLinkId");

		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"), null, rlid);
		assertThat(order.get("recommenderAccountId")).isEqualTo(setup.recommender());
		assertThat(order.get("taskId")).isEqualTo(setup.task().get("id"));
		// 10000 分、recommenderShareBps=1000 → 佣金 1000 分。
		assertThat(((Number) order.get("recommenderAmountCents")).longValue()).isEqualTo(1000L);

		// 链接过期（读时判定）：SQL 拨过期 → 422 link_expired。
		db.sql("UPDATE referral_link SET expires_at = now() - interval '1 second' WHERE id = :id").bind("id", rlid)
				.then().block();
		createOrderWithRlid(setup.consumer(), (String) setup.offer().get("id"), rlid, 422, "link_expired");
	}

	/** 自购经本人 rlid：归因照落（审计可见）、佣金 0（D1 派生 4，与旧参数口径一致）。 */
	@Test
	void selfPurchaseViaOwnLinkAttributedWithZeroCommission() {
		Setup setup = setupAcceptedPromotion();
		String rlid = (String) issueLink(setup.recommender(), (String) setup.task().get("id")).get("referralLinkId");

		Map<String, Object> order = createOrder(setup.recommender(), (String) setup.offer().get("id"), null, rlid);
		assertThat(order.get("recommenderAccountId")).isEqualTo(setup.recommender());
		assertThat(((Number) order.get("recommenderAmountCents")).longValue()).isZero();
	}

	/** TC98-005：旧 recommenderAccountId 参数兼容期行为不变（资格归因、金额口径照旧）。 */
	@Test
	void legacyRecommenderParamBehaviourUnchanged() {
		Setup setup = setupAcceptedPromotion();
		Map<String, Object> order = createOrder(setup.consumer(), (String) setup.offer().get("id"),
				setup.recommender(), null);
		assertThat(order.get("recommenderAccountId")).isEqualTo(setup.recommender());
		assertThat(((Number) order.get("recommenderAmountCents")).longValue()).isEqualTo(1000L);
	}

	/** 无效 rlid（不存在）：422 link_invalid，订单可无归因另行创建。 */
	@Test
	void unknownLinkIsRejectedAsInvalid() {
		Setup setup = setupAcceptedPromotion();
		createOrderWithRlid(setup.consumer(), (String) setup.offer().get("id"), "noSuchLink00000000000", 422,
				"link_invalid");
	}

	// ---------- 造数（模式同 CommercePromotionTaskIT：API 造任务 + 报名 + 接单） ----------

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
		Map<String, Object> body = Map.of("organizationId", org, "title", "双人到店套餐", "description", "测试套餐",
				"priceCents", priceCents, "totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps",
				1000, "platformFeeBps", 500, "policyVersion", "commerce-v1");
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
	private Map<String, Object> issueLink(String recommender, String taskId) {
		return (Map<String, Object>) client().post().uri("/api/v2/promotion/links")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("taskId", taskId)).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("data");
	}

	/** 下单（可携 rlid）；预期 4xx 时断言状态与 blockedReason 后返回 null。 */
	private Map<String, Object> createOrder(String consumer, String packageId, String legacyRecommender, String rlid) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("packageId", packageId);
		if (legacyRecommender != null) {
			body.put("recommenderAccountId", legacyRecommender);
		}
		if (rlid != null) {
			body.put("referralLinkId", rlid);
		}
		return (Map<String, Object>) client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody().get("data");
	}

	private void createOrderWithRlid(String consumer, String packageId, String rlid, int expectedStatus,
			String expectedBlockedReason) {
		client().post().uri("/api/v2/orders").header("X-Grassland-Identity", sign(consumer, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId, "referralLinkId", rlid)).exchange()
				.expectStatus().isEqualTo(expectedStatus).expectBody().jsonPath("$.blockedReason")
				.isEqualTo(expectedBlockedReason);
	}
}
