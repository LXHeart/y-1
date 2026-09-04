package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.time.Instant;
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
 * 任务书 #75 套餐推广任务化主链 IT：卡 A（任务-套餐关联/契约三分支/回填）、卡 B（末次点击单归因 + 佣金二形态 + promotions
 * 端点）、卡 C（核销 48h 冷静期解耦分账）、卡 D（下架联动 + 商家统计）。
 *
 * <p>
 * finance 保持 HTTP 边界替身（照 {@link CommerceControllerIT} 惯例）；dispatcher 默认关， 分账触发按
 * PaymentTimeoutCloseIT 惯例直调 service seam（同包可见，行为与 dispatcher 链一致）。
 */
class CommercePromotionTaskIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceService service;

	@BeforeEach
	void sandboxFinance() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), anyString())).thenReturn(Mono.empty());
		when(finance.split(any(Order.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), anyList())).thenReturn(Mono.empty());
	}

	// ---------- 卡 A：任务模型 ----------

	@Test
	void commercePromotionTaskCreationRulesAndBackfill() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);

		// 契约三分支：套餐推广与赏金互斥。
		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(with(with(taskBody(org), "commercePackageId", offer.get("id")), "bountyCents", 100))
				.exchange().expectStatus().isBadRequest();

		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		assertThat(task.get("commercePackageId")).isEqualTo(offer.get("id"));
		assertThat(task.get("bountyCents")).isNull();
		assertThat(task.get("freebieDepositCents")).isNull();

		// 回填：commerce_package.task_id 指向进行中任务。
		assertThat(packageBackfillTaskId((String) offer.get("id"))).isEqualTo(task.get("id"));

		// 每套餐同时至多一个进行中推广任务（draft/pending_review/published 均占用）。
		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(with(taskBody(org), "commercePackageId", offer.get("id"))).exchange().expectStatus()
				.isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("该套餐已有进行中的推广任务");

		// 非本主体套餐 → 400。
		Map<String, Object> otherOrgOffer = createAndPublishPackage(UUID.randomUUID().toString(),
				UUID.randomUUID().toString(), 1000, 5, null);
		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(with(taskBody(org), "commercePackageId", otherOrgOffer.get("id"))).exchange().expectStatus()
				.isBadRequest();

		// 未上架（draft）套餐 → 400。
		Map<String, Object> draftOffer = createPackage(merchant, org, 1000, 5, null);
		client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(with(taskBody(org), "commercePackageId", draftOffer.get("id"))).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("套餐未上架，不能关联推广任务");

		// 审核流照走（全审政策），通过后 published 且 feed 带套餐摘要块。
		approve(task);
		Map<String, Object> published = getTask(merchant, org, (String) task.get("id"));
		assertThat(published.get("status")).isEqualTo("published");
		client().get().uri("/api/tasks/feed?limit=50")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.items[0].commercePackageId")
				.isEqualTo(offer.get("id")).jsonPath("$.data.items[0].commercePackage.priceCents").isEqualTo(1000)
				.jsonPath("$.data.items[0].commercePackage.recommenderShareBps").isEqualTo(1000);
	}

	@Test
	void acceptedApplicationHasNoReservationForCommercePromotionTask() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);

		String appId = apply(recommender, (String) task.get("id"));
		client().post().uri("/api/tasks/" + task.get("id") + "/applications/" + appId + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("accepted");

		// 非资金路径（bounty=0/freebie=0）：台账行直接落 accepted、无 workflowId、金额 0（不进资金 Saga）。
		Map<String, Object> command = db
				.sql("SELECT status, COALESCE(workflow_id::text, '') AS wf, amount_cents"
						+ " FROM task_acceptance_command WHERE task_id = CAST(:task AS uuid)")
				.bind("task", task.get("id"))
				.<Map<String, Object>>map(r -> Map.of("status", (Object) r.get("status", String.class), "wf",
						(Object) r.get("wf", String.class), "amount", (Object) r.get("amount_cents", Long.class)))
				.one().block();
		assertThat(command).isNotNull().containsEntry("status", "accepted").containsEntry("wf", "")
				.containsEntry("amount", 0L);
	}

	// ---------- 卡 B：归因闸 + 佣金形态 ----------

	@Test
	void attributionGateSelfPurchaseAndV37Freeze() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);

		// 接单推荐官带链下单 → 归因落列 + 比例佣金快照 + 订单 task_id 快照。
		Map<String, Object> attributed = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"),
				recommender);
		assertThat(attributed.get("recommenderAccountId")).isEqualTo(recommender);
		assertThat(((Number) attributed.get("recommenderAmountCents")).longValue()).isEqualTo(100L);
		assertThat(((Number) attributed.get("platformFeeCents")).longValue()).isEqualTo(50L);
		assertThat(((Number) attributed.get("merchantAmountCents")).longValue()).isEqualTo(850L);
		assertThat(orderTaskId((String) attributed.get("id"))).isEqualTo(task.get("id"));

		// 未接任务推荐官 → 自然流量：归因不落列、推荐官份额 0。
		Map<String, Object> natural = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"),
				UUID.randomUUID().toString());
		assertThat(natural.get("recommenderAccountId")).isNull();
		assertThat(((Number) natural.get("recommenderAmountCents")).longValue()).isZero();
		assertThat(((Number) natural.get("merchantAmountCents")).longValue()).isEqualTo(950L);

		// 自购不计佣：归因照落、份额 0 归商家（bps 快照照存）。
		Map<String, Object> selfPurchase = createOrder(recommender, (String) offer.get("id"), recommender);
		assertThat(selfPurchase.get("recommenderAccountId")).isEqualTo(recommender);
		assertThat(((Number) selfPurchase.get("recommenderAmountCents")).longValue()).isZero();
		assertThat(((Number) selfPurchase.get("merchantAmountCents")).longValue()).isEqualTo(950L);

		// D5：createOrder 后 V37 无新行。
		Integer allocations = db.sql("SELECT COUNT(*)::int AS c FROM consumer_order_attribution_allocation")
				.map(r -> r.get("c", Integer.class)).one().block();
		assertThat(allocations).isZero();
	}

	@Test
	void fixedCommissionFormAndOverpriceGuard() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		// 固定佣 ¥5/单（recommenderFixedCents=500，bps 形式值 0）。
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 2000, 5, 500L);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);

		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), recommender);
		assertThat(((Number) order.get("recommenderAmountCents")).longValue()).isEqualTo(500L);
		assertThat(((Number) order.get("merchantAmountCents")).longValue()).isEqualTo(1400L);

		// 固定佣与比例互斥 + 超价拦截（价格 500、平台 5% → 可分配 475 < 600）。
		client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(with(offerBody(org, 500, 5, 600L), "recommenderShareBps", 1000)).exchange().expectStatus()
				.isBadRequest();
		client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(offerBody(org, 500, 5, 600L)).exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	void recommenderPromotionsEndpointScopesToCallerAndAggregatesStats() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);

		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), recommender);
		redeem(merchant, org, (String) order.get("redeemCode"));

		client().get().uri("/api/v2/recommender/promotions")
				.header("X-Grassland-Identity", sign(recommender, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.length()").isEqualTo(1).jsonPath("$.data[0].taskId")
				.isEqualTo(task.get("id")).jsonPath("$.data[0].packageId").isEqualTo(offer.get("id"))
				.jsonPath("$.data[0].commission.form").isEqualTo("ratio").jsonPath("$.data[0].commission.shareBps")
				.isEqualTo(1000).jsonPath("$.data[0].stats.orderCount").isEqualTo(1)
				.jsonPath("$.data[0].stats.redeemedCount").isEqualTo(1).jsonPath("$.data[0].stats.pendingSettleCents")
				.isEqualTo(100).jsonPath("$.data[0].stats.settledCents").isEqualTo(0);

		// 非接单推荐官 → 空列表。
		client().get().uri("/api/v2/recommender/promotions")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
	}

	// ---------- 卡 C：核销冷静期 ----------

	@Test
	void redeemEntersCooldownThenSplitSettlesAfterExpiry() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);
		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), recommender);

		// 核销即刻 redeemed（不再被分账 RPC 拦）+ 冷静期 48h 快照 + 无完成标记。
		Map<String, Object> redeemed = redeem(merchant, org, (String) order.get("redeemCode"));
		assertThat(redeemed.get("status")).isEqualTo("redeemed");
		Instant eligibleAt = Instant.parse((String) redeemed.get("splitEligibleAt"));
		assertThat(eligibleAt.isAfter(Instant.now().plusSeconds(47 * 3600))).isTrue();
		assertThat(redeemed.get("splitCompletedAt")).isNull();
		assertThat(redeemed.get("taskId")).isEqualTo(task.get("id"));

		// 未到期不进 dispatcher 扫描（不空转）。
		List<Order> pendingNow = service.pendingDispatch(50).collectList().block();
		assertThat(pendingNow).noneMatch(o -> o.id().equals(order.get("id")));

		// 期满（SQL 拨针）→ dispatcher 扫出 → split 完成（split_completed_at 落行）。
		db.sql("UPDATE consumer_order SET split_eligible_at = now() - interval '1 hour'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", order.get("id")).then().block();
		List<Order> due = service.pendingDispatch(50).collectList().block();
		assertThat(due).anyMatch(o -> o.id().equals(order.get("id")));
		Order dueOrder = due.stream().filter(o -> o.id().equals(order.get("id"))).findFirst().orElseThrow();
		Order settled = service.attemptSplit(dueOrder).block();
		assertThat(settled.splitCompletedAt()).isNotNull();
		assertThat(settled.status()).isEqualTo("redeemed");

		// 期满后重复扫描不再捞出（split_completed_at 已落，SQL 谓词排除）。
		List<Order> after = service.pendingDispatch(50).collectList().block();
		assertThat(after).noneMatch(o -> o.id().equals(order.get("id")));
	}

	@Test
	void legacyRedeemingOrderIsCollectedByDispatcher() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
		// 手工造升级时刻卡在 redeeming 的旧行（无 split_eligible_at）。
		db.sql("UPDATE consumer_order SET status = 'redeeming'," + " split_operation_id = 'commerce-split:' || id::text"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", order.get("id")).then().block();
		List<Order> due = service.pendingDispatch(50).collectList().block();
		assertThat(due).anyMatch(o -> o.id().equals(order.get("id")));
		Order legacy = due.stream().filter(o -> o.id().equals(order.get("id"))).findFirst().orElseThrow();
		Order settled = service.attemptSplit(legacy).block();
		assertThat(settled.status()).isEqualTo("redeemed");
		assertThat(settled.splitCompletedAt()).isNotNull();
	}

	@Test
	void cooldownRefundOnlyThroughAfterSalesDispute() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
		redeem(merchant, org, (String) order.get("redeemCode"));

		// 普通自助退款仅限未核销：核销后 409 引导售后争议。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "change_of_mind")).exchange().expectStatus().isEqualTo(409);

		// 冷静期内（未 split）售后退款 → refundFresh 托管直退：全额退、零佣金、零追扣。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "服务与描述不符")).exchange().expectStatus().isCreated();
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("resolution", "refund", "reason", "协商全额退款"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("refunded")
				.jsonPath("$.data.refundedAmountCents").isEqualTo(1000);
	}

	// ---------- 卡 D：下架联动 + 商家统计 ----------

	@Test
	void offSaleClosesPromotionTaskAndFreesPackageSlot() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);

		// 下架联动：任务转截止态（等价手动 close）+ 回填清空；不 409 打断商家。
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/off-sale")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("off_sale");
		assertThat(getTask(merchant, org, (String) task.get("id")).get("status")).isEqualTo("closed");
		assertThat(packageBackfillTaskId((String) offer.get("id"))).isNull();

		// 重新上架后占用已释放：可再建第二个推广任务。
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		createPromotionTask(merchant, org, (String) offer.get("id"));
	}

	@Test
	void taskEndFallsNewOrdersToNaturalTrafficWhileSnapshotOrdersStillSplit() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);

		// 任务进行中：接单推荐官下单归因。
		Map<String, Object> attributed = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"),
				recommender);
		// 任务截止后：同一链接下单 → 自然流量（推广资格随任务终止）。
		Map<String, Object> current = getTask(merchant, org, (String) task.get("id"));
		client().post().uri("/api/tasks/" + task.get("id") + "/close")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", ((Number) current.get("version")).intValue())).exchange()
				.expectStatus().isOk();
		assertThat(packageBackfillTaskId((String) offer.get("id"))).isNull();
		Map<String, Object> afterEnd = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), recommender);
		assertThat(afterEnd.get("recommenderAccountId")).isNull();
		assertThat(((Number) afterEnd.get("recommenderAmountCents")).longValue()).isZero();
		assertThat(orderTaskId((String) afterEnd.get("id"))).isNull();

		// 结束前已下单的订单核销照常分账（金额快照在单上，派生 3）。
		Map<String, Object> redeemed = redeem(merchant, org, (String) attributed.get("redeemCode"));
		assertThat(redeemed.get("recommenderAccountId")).isEqualTo(recommender);
		db.sql("UPDATE consumer_order SET split_eligible_at = now() - interval '1 hour'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", attributed.get("id")).then().block();
		Order due = service.pendingDispatch(50).collectList().block().stream()
				.filter(o -> o.id().equals(attributed.get("id"))).findFirst().orElseThrow();
		Order settled = service.attemptSplit(due).block();
		assertThat(settled.splitCompletedAt()).isNotNull();
		assertThat(settled.recommenderAmountCents()).isEqualTo(100L);
	}

	@Test
	void merchantPromotionsEndpointAggregatesFunnelAndRejectsForeignOrg() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublishPackage(merchant, org, 1000, 5, null);
		Map<String, Object> task = createPromotionTask(merchant, org, (String) offer.get("id"));
		approve(task);
		accept(recommender, merchant, org, task);

		createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), recommender);
		Map<String, Object> refunded = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
		client().post().uri("/api/v2/orders/" + refunded.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(String.valueOf(refunded.get("consumerAccountId")), null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "change_of_mind")).exchange()
				.expectStatus().isOk();

		client().get().uri("/api/v2/merchant/promotions?organizationId=" + org)
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1)
				.jsonPath("$.data[0].taskId").isEqualTo(task.get("id")).jsonPath("$.data[0].taskStatus")
				.isEqualTo("published").jsonPath("$.data[0].stats.orderCount").isEqualTo(2)
				.jsonPath("$.data[0].stats.refundedCount").isEqualTo(1);
	}

	// ---------- helpers ----------

	private static Map<String, Object> with(Map<String, Object> map, String key, Object value) {
		map.put(key, value);
		return map;
	}

	private static Map<String, Object> taskBody(String org) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("organizationId", org);
		m.put("title", "套餐推广-" + UUID.randomUUID());
		return m;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createPromotionTask(String merchant, String org, String packageId) {
		Map<String, Object> response = client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(with(taskBody(org), "commercePackageId", packageId))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private void approve(Map<String, Object> task) {
		client().post().uri("/api/admin/tasks/" + task.get("id") + "/review/approve")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "content_reviewer"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", ((Number) task.get("version")).intValue())).exchange()
				.expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getTask(String merchant, String org, String taskId) {
		Map<String, Object> response = client().get().uri("/api/tasks/" + taskId)
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private String apply(String recommender, String taskId) {
		Map<String, Object> response = client().post().uri("/api/tasks/" + taskId + "/applications")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "带客到店")).exchange().expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return String.valueOf(((Map<String, Object>) response.get("data")).get("id"));
	}

	private void accept(String recommender, String merchant, String org, Map<String, Object> task) {
		String appId = apply(recommender, (String) task.get("id"));
		client().post().uri("/api/tasks/" + task.get("id") + "/applications/" + appId + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("accepted");
	}

	private static Map<String, Object> offerBody(String org, long priceCents, int stock, Long fixedCents) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("organizationId", org);
		m.put("title", "双人到店套餐");
		m.put("description", "测试套餐");
		m.put("priceCents", priceCents);
		m.put("totalStock", stock);
		m.put("validDaysAfterPurchase", 30);
		m.put("platformFeeBps", 500);
		if (fixedCents != null) {
			m.put("recommenderFixedCents", fixedCents);
			m.put("recommenderShareBps", 0);
		} else {
			m.put("recommenderShareBps", 1000);
		}
		m.put("policyVersion", "commerce-v1");
		return m;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createPackage(String merchant, String org, long priceCents, int stock,
			Long fixedCents) {
		Map<String, Object> response = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(offerBody(org, priceCents, stock, fixedCents))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createAndPublishPackage(String merchant, String org, long priceCents, int stock,
			Long fixedCents) {
		Map<String, Object> offer = createPackage(merchant, org, priceCents, stock, fixedCents);
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		return offer;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createOrder(String consumer, String packageId, String recommender) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("packageId", packageId);
		if (recommender != null) {
			body.put("recommenderAccountId", recommender);
		}
		Map<String, Object> response = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> redeem(String merchant, String org, String code) {
		Map<String, Object> response = client().post().uri("/api/v2/merchant/redemptions")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code)).exchange().expectStatus()
				.isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private String packageBackfillTaskId(String packageId) {
		// COALESCE 防 Reactor map null 直接 NPE；空串归一为 null（无回填）。
		String value = db
				.sql("SELECT COALESCE(task_id::text, '') AS t FROM commerce_package WHERE id = CAST(:id AS uuid)")
				.bind("id", packageId).map(r -> r.get("t", String.class)).one().block();
		return value == null || value.isEmpty() ? null : value;
	}

	private String orderTaskId(String orderId) {
		String value = db
				.sql("SELECT COALESCE(task_id::text, '') AS t FROM consumer_order WHERE id = CAST(:id AS uuid)")
				.bind("id", orderId).map(r -> r.get("t", String.class)).one().block();
		return value == null || value.isEmpty() ? null : value;
	}
}
