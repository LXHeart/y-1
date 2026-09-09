package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
 * 任务书 #97 C97-01：已结算订单退款闸门与结算防御纵深。
 *
 * <p>
 * TC97-001 结算前退款照旧（回归）；TC97-002 结算后买家退款 409 {@code settled_no_refund}； TC97-003
 * 结算后售后裁定退款被阻；TC97-004 开放售后争议阻断结算（attemptSplit 执行前重查）；
 * 边界：部分退款再结算时序、退款在途重放不撞闸门、管理端纠错（资金动作）同守卫。
 */
class CommerceSettlementRefundGateIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceService commerce;

	@Autowired
	CommerceRepository repository;

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

	/** TC97-002/003：结算事实（split_completed_at）落账后，买家退款与售后裁定退款统一 409 且可解释。 */
	@Test
	void settledOrderRefundsAreRejectedOnBuyerAndDisputePaths() {
		String merchant = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
		String merchantAuth = sign(merchant, "merchant", org, "finance_transaction");
		redeem(merchantAuth, order);

		// 结算前回显：splitEligibleAt 存在、无 refundBlockedReason（前端不禁用）。
		client().get().uri("/api/v2/orders/" + order.get("id")).header("X-Grassland-Identity", sign(consumer, null))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.splitEligibleAt").exists()
				.jsonPath("$.data.refundBlockedReason").doesNotExist();

		// dispatcher 分账完成 + 分账后售后部分退款已发生（现状可达态）的落账终态：
		// partially_refunded + split_completed_at——买家退款入口（paid/partially_refunded）唯一能
		// 撞上已结算闸门的形态；IT 直造终态，链路行为由 TC97-004 直调覆盖。
		settleAfterPartialRefund(order.get("id"));

		client().get().uri("/api/v2/orders/" + order.get("id")).header("X-Grassland-Identity", sign(consumer, null))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.splitCompletedAt").exists()
				.jsonPath("$.data.refundBlockedReason").isEqualTo("settled_no_refund");

		// TC97-002 买家退款：409 + blockedReason（不再落到钱包余额裸 409）。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "change_of_mind")).exchange().expectStatus().isEqualTo(409).expectBody()
				.jsonPath("$.blockedReason").isEqualTo("settled_no_refund").jsonPath("$.error")
				.value(msg -> assertThat(String.valueOf(msg)).contains("已结算"));

		// TC97-003 售后争议照常可开（窗口语义不变），但裁定退款被闸门拒绝；驳回不受影响。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "到店后商家无法提供服务")).exchange().expectStatus().isCreated();

		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", merchantAuth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("resolution", "refund", "reason", "协商退款")).exchange().expectStatus().isEqualTo(409)
				.expectBody().jsonPath("$.blockedReason").isEqualTo("settled_no_refund");
		verify(finance, never()).refund(any(Order.class), anyString());

		// 争议未终结（退款未发生），驳回照旧放行且订单金额零变动（有部分退款史 → 回 partially_refunded）。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", merchantAuth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("resolution", "reject", "reason", "商家已履约")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("partially_refunded")
				.jsonPath("$.data.refundedAmountCents").isEqualTo(3000);
	}

	/** TC97-001：结算事实不存在时退款行为与金额与现状一致（部分退款累积，闸门不误伤）。 */
	@Test
	void refundsBeforeSettlementKeepLegacyBehaviour() {
		String merchant = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
		String merchantAuth = sign(merchant, "merchant", org, "finance_transaction");
		redeem(merchantAuth, order);

		// 未结算的已核销单：售后部分退款照旧成功。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "少上一道菜")).exchange().expectStatus().isCreated();
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", merchantAuth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("resolution", "refund", "amountCents", 3000, "reason", "协商部分退款")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("partially_refunded")
				.jsonPath("$.data.refundedAmountCents").isEqualTo(3000);

		// partially_refunded 且未结算：买家继续部分退款不受闸门影响，幂等键路径不变。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("amountCents", 2000, "reason", "追加退款")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("partially_refunded").jsonPath("$.data.refundedAmountCents")
				.isEqualTo(5000);
	}

	/** TC97-004：开放售后争议阻断结算——attemptSplit 执行前重查最新状态，捞单后的开案竞态被挡。 */
	@Test
	void openAfterSalesDisputeHoldsSettlementUntilResolved() {
		String merchant = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
		String merchantAuth = sign(merchant, "merchant", org, "finance_transaction");
		redeem(merchantAuth, order);
		makeSplitEligible(order.get("id"));

		// 模拟 dispatcher 捞单（快照 redeemed），捞单后、执行前消费者开案成功。
		Order snapshot = repository.findOrder((String) order.get("id")).block();
		assertThat(snapshot).isNotNull();
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "到店后商家无法提供服务")).exchange().expectStatus().isCreated();

		commerce.attemptSplit(snapshot).block();
		verify(finance, never()).split(any(Order.class), anyList());
		assertThat(repository.findOrder((String) order.get("id")).block().splitCompletedAt()).isNull();

		// 争议驳回后订单回 redeemed：分账恢复执行（时序：售后先于结算，退款/结算互斥成立）。
		client().post().uri("/api/v2/orders/" + order.get("id") + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", merchantAuth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("resolution", "reject", "reason", "商家已履约")).exchange().expectStatus().isOk();
		Order fresh = repository.findOrder((String) order.get("id")).block();
		commerce.attemptSplit(fresh).block();
		verify(finance, times(1)).split(any(Order.class), anyList());
		assertThat(repository.findOrder((String) order.get("id")).block().splitCompletedAt()).isNotNull();
	}

	/** 边界：分账后售后退款留下的 partially_refunded + settled 单，管理端纠错（资金动作）同守卫拒绝。 */
	@Test
	void settledOrderBlocksAdminAttributionCorrection() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
		redeem(sign(merchant, "merchant", org, "finance_transaction"), order);

		db.sql("UPDATE consumer_order SET status = 'partially_refunded', refunded_amount_cents = 3000,"
				+ " split_completed_at = now() WHERE id = CAST(:id AS uuid)").bind("id", order.get("id")).then()
				.block();

		client().post().uri("/api/admin/commerce/orders/" + order.get("id") + "/attribution-correction")
				.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "customer_service"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("recommenderAccountId", UUID.randomUUID().toString(), "reason", "纠错")).exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.blockedReason").isEqualTo("settled_no_refund");
	}

	/** 边界：退款在途（refund_pending）重放走既有 attemptRefund 分支，不被闸门拦截。 */
	@Test
	void refundPendingReplayIsNotBlockedBySettlementGate() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);

		when(finance.refund(any(Order.class), anyString()))
				.thenReturn(Mono.error(new RuntimeException("sandbox downstream down")));
		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(String.valueOf(order.get("consumerAccountId")), null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "change_of_mind")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("refund_pending");

		when(finance.refund(any(Order.class), anyString())).thenReturn(Mono.empty());
		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(String.valueOf(order.get("consumerAccountId")), null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "retry")).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.status").isEqualTo("refunded");
	}

	// ---------- 造数与直置终态（IT 直造是本仓库惯例：状态由 SQL 置位模拟 dispatcher 已完成的落账） ----------

	private void settleAfterPartialRefund(Object orderId) {
		db.sql("UPDATE consumer_order SET status = 'partially_refunded', refunded_amount_cents = 3000,"
				+ " split_completed_at = now() WHERE id = CAST(:id AS uuid)").bind("id", String.valueOf(orderId)).then()
				.block();
	}

	private void makeSplitEligible(Object orderId) {
		db.sql("UPDATE consumer_order SET split_eligible_at = now() - interval '1 second'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", String.valueOf(orderId)).then().block();
	}

	private void redeem(String merchantAuth, Map<String, Object> order) {
		client().post().uri("/api/v2/merchant/redemptions").header("X-Grassland-Identity", merchantAuth)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", order.get("redeemCode"))).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("redeemed");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createAndPublish(String merchant, String org, long priceCents, int stock) {
		Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(offerBody(org, priceCents, stock)).exchange()
				.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> offer = (Map<String, Object>) created.get("data");
		client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		return offer;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createOrder(String consumer, String packageId, String recommender) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("packageId", packageId);
		if (recommender != null)
			body.put("recommenderAccountId", recommender);
		Map<String, Object> response = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private static Map<String, Object> offerBody(String org, long priceCents, int stock) {
		return Map.of("organizationId", org, "title", "双人到店套餐", "description", "测试套餐", "priceCents", priceCents,
				"totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000, "platformFeeBps", 500,
				"policyVersion", "commerce-v1");
	}
}
