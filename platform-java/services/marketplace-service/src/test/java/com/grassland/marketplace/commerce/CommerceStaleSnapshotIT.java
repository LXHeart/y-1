package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 过期快照退款竞态 IT（任务书 #103 C103-05 / TC103-05-06，R02 反例 → 不变量）。
 *
 * <p>
 * R02 原缺陷：买家退款在 Java 侧用旧快照检查 {@code split_completed_at}，SQL 守卫不含已结算事实——
 * 分账在检查与条件更新之间完成时，旧快照仍推动新增退款。修复后
 * （D103-03）：{@code requestRefund}/{@code requestDisputeRefund} 的条件更新烧入
 * {@code split_completed_at IS NULL}，退款与分账在同一订单行上单边胜出；本 IT 用真实 PG
 * 行锁在两者同时起跑的多轮试验中断言互斥，并在分账先落定后经 HTTP 验证 409 {@code settled_no_refund}
 * 与零 Finance 退款调用。
 */
class CommerceStaleSnapshotIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceRepository repository;

	@Autowired
	CommerceService commerce;

	@BeforeEach
	void sandboxFinance() {
		when(finance.pay(any(Order.class))).thenAnswer(inv -> {
			Order order = inv.getArgument(0);
			return Mono.just("sandbox:payment:" + order.id());
		});
		when(finance.refund(any(Order.class), anyString())).thenReturn(Mono.empty());
		when(finance.split(any(Order.class))).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), any(java.util.List.class))).thenReturn(Mono.empty());
	}

	/**
	 * TC103-05-06：partially_refunded + 已核销（退款与分账同时可达的形态）上，买家退款 claim 与
	 * dispatcher 分账（claimSplit → markSplitCompleted）并发——每组恰好一个胜者，绝不同时成功。
	 */
	@Test
	void refundClaimAndSplitCompletionAreMutuallyExclusiveUnderConcurrency() throws Exception {
		String merchant = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 200);
		int rounds = 20;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
				redeem(sign(merchant, "merchant", org, "finance_transaction"), order);
				// 已核销 + 部分退款（售后形态直造，镜像 settleAfterPartialRefund 但未结算）。
				db.sql("UPDATE consumer_order SET status = 'partially_refunded', refunded_amount_cents = 3000"
						+ " WHERE id = CAST(:id AS uuid)").bind("id", order.get("id")).then().block();

				CountDownLatch start = new CountDownLatch(1);
				final int roundNo = round;
				Future<Boolean> refundWon = pool.submit(() -> await(start)
						&& repository
								.requestRefund(String.valueOf(order.get("id")),
										"commerce-refund:" + order.get("id") + ":" + roundNo, 1000, "consumer_request")
								.block() != null);
				Future<Boolean> splitWon = pool.submit(() -> await(start) && repository
						.claimSplit(String.valueOf(order.get("id"))).block() != null
						&& repository.markSplitCompleted(String.valueOf(order.get("id"))).block() != null);
				start.countDown();
				boolean refunded = refundWon.get(30, TimeUnit.SECONDS);
				boolean splitCompleted = splitWon.get(30, TimeUnit.SECONDS);

				// 互斥不变量：退款成功 ⇒ 分账未完成；分账完成 ⇒ 退款 0 行。两者同时成功即 R02 复现。
				assertThat(refunded).as("round %d refund", round).isNotEqualTo(splitCompleted);
				Map<String, Object> row = orderRow(order.get("id"));
				if (refunded) {
					assertThat(row.get("status")).isEqualTo("refund_pending");
					assertThat(row.get("split_completed_at")).isNull();
					assertThat(((Number) row.get("refunded_amount_cents")).longValue()).isEqualTo(3000);
					assertThat(((Number) row.get("refund_requested_amount_cents")).longValue()).isEqualTo(1000);
				} else {
					assertThat(row.get("split_completed_at")).isNotNull();
					assertThat(row.get("status")).isNotEqualTo("refund_pending");
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** TC103-05-01/06 HTTP 侧：分账先完成后，买家退款 409 settled_no_refund 且零 Finance 调用。 */
	@Test
	void settledOrderBuyerRefundRejectedWithBlockedReasonAndNoFinanceCall() {
		String merchant = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), null);
		redeem(sign(merchant, "merchant", org, "finance_transaction"), order);
		// 分账完成 + 售后部分退款已发生（R02 触达形态）。
		db.sql("UPDATE consumer_order SET status = 'partially_refunded', refunded_amount_cents = 3000,"
				+ " split_completed_at = now() WHERE id = CAST(:id AS uuid)").bind("id", order.get("id")).then()
				.block();

		client().post().uri("/api/v2/orders/" + order.get("id") + "/refund")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("amountCents", 1000)).exchange().expectStatus().isEqualTo(409).expectBody()
				.jsonPath("$.blockedReason").isEqualTo("settled_no_refund");

		Map<String, Object> row = orderRow(order.get("id"));
		assertThat(row.get("status")).isEqualTo("partially_refunded");
		assertThat(((Number) row.get("refunded_amount_cents")).longValue()).isEqualTo(3000);
		assertThat(row.get("refund_requested_amount_cents")).isNull();
		verify(finance, never()).refund(any(Order.class), anyString());
	}

	/** 分账在途（splitting）时退款 → 409 fund_operation_in_progress（败方可解释，不再「状态已变化」）。 */
	@Test
	void splittingOrderRefundClassifiedAsFundOperationInProgress() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		Map<String, Object> offer = createAndPublish(merchant, org, 10000, 5);
		Map<String, Object> order = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
		redeem(sign(merchant, "merchant", org, "finance_transaction"), order);
		db.sql("UPDATE consumer_order SET status = 'splitting' WHERE id = CAST(:id AS uuid)")
				.bind("id", order.get("id")).then().block();

		MarketplaceException failure = (MarketplaceException) catchThrowable(
				() -> new CommerceRefundClaimService(repository)
						.claimConsumerRefund(String.valueOf(order.get("id")), "op-x", 1000, "consumer_request")
						.block());
		assertThat(failure).isNotNull();
		assertThat(failure.getMessage()).contains("结算处理中");
	}

	// ---------- helpers ----------

	private static boolean await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private Map<String, Object> orderRow(Object orderId) {
		return db.sql("SELECT status::text, refunded_amount_cents, refund_requested_amount_cents,"
				+ " split_completed_at::text FROM consumer_order WHERE id = CAST(:id AS uuid)")
				.bind("id", String.valueOf(orderId))
				.map(r -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("status", String.valueOf(r.get("status", String.class)));
					row.put("refunded_amount_cents",
							r.get("refunded_amount_cents", Long.class) == null ? 0L
									: r.get("refunded_amount_cents", Long.class));
					row.put("refund_requested_amount_cents", r.get("refund_requested_amount_cents", Long.class));
					row.put("split_completed_at", r.get("split_completed_at", String.class));
					return row;
				})
				.one().block();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> createAndPublish(String merchant, String org, long priceCents, int stock) {
		Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(offerBody(org, priceCents, stock)).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
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

	private void redeem(String merchantAuth, Map<String, Object> order) {
		client().post().uri("/api/v2/merchant/redemptions").header("X-Grassland-Identity", merchantAuth)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", order.get("redeemCode")))
				.exchange().expectStatus().isOk();
	}

	private static Map<String, Object> offerBody(String org, long priceCents, int stock) {
		return Map.of("organizationId", org, "title", "双人到店套餐", "description", "测试套餐", "priceCents", priceCents,
				"totalStock", stock, "validDaysAfterPurchase", 30, "recommenderShareBps", 1000, "platformFeeBps", 500,
				"policyVersion", "commerce-v1");
	}
}
