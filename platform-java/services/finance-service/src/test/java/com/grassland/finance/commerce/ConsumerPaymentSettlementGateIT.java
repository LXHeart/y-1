package com.grassland.finance.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 支付父锁与结算后退款禁入 IT（任务书 #103 C103-06 / TC103-06-01～06，D103-03 Finance 防线）。
 *
 * <p>
 * 退款与分账共用 {@code consumer_payment} 父行排他锁：两种胜出顺序下净额守恒、
 * 分账先完成 → 新增退款 409（已结算事实拒绝）；同键重放参数校验；facts 只读端点仅
 * marketplace principal 可读且不触发资金变动。并发用例以真实 PG 行锁在双线程同时起跑下
 * 验证单边胜出（TC103-06-06）。
 */
class ConsumerPaymentSettlementGateIT extends FinanceItSupport {

	@Autowired
	private ConsumerPaymentService service;

	@Autowired
	private ConsumerPaymentFactsService facts;

	// ---------- TC103-06-05：分账后新增退款拒绝；历史同键回读兼容 ----------

	@Test
	void postSplitNewRefundsAreRejectedWhileSameKeyReplayStillWorks() {
		String order = "gate-" + UUID.randomUUID();
		String consumer = UUID.randomUUID().toString();
		String recommender = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		postPayment(order, consumer, org, 10_000);
		postRefund(order, org, 3_000, "commerce-refund:" + order + ":pre").expectStatus().isOk();

		// 分账完成（净额 7000 = 0+6650+350，申报累计退款 3000 一致）。
		client().post().uri("/internal/commerce/payments/" + order + "/split")
				.header("X-Grassland-Identity", signService(org, "marketplace"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "totalAmountCents", 10_000, "refundedAmountCents", 3_000,
						"recommenderAccountId", recommender, "recommenderAmountCents", 0, "merchantAmountCents", 6_650,
						"platformFeeCents", 350, "operationId", "commerce-split:" + order))
				.exchange().expectStatus().isOk();

		// 新增退款 → 409（Finance 防线；不建追偿）。
		postRefund(order, org, 1_000, "commerce-refund:" + order + ":post").expectStatus().isEqualTo(409);
		// 退款总额不被推动。
		Long refunded = refundedCents(order);
		assertThat(refunded).isEqualTo(3_000L);

		// 同键不同金额 → 409 幂等冲突；同键同事实 → 原样回读（含分账前发生的历史退款）。
		postRefund(order, org, 9_999, "commerce-refund:" + order + ":pre").expectStatus().isEqualTo(409);
		postRefund(order, org, 3_000, "commerce-refund:" + order + ":pre").expectStatus().isOk();
		assertThat(refundedCents(order)).isEqualTo(3_000L);
	}

	// ---------- TC103-06-01：facts 只读端点 ----------

	@Test
	void factsExposeAuthoritativeRowsToMarketplaceOnly() {
		String order = "facts-" + UUID.randomUUID();
		String consumer = UUID.randomUUID().toString();
		String first = UUID.randomUUID().toString();
		String second = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		postPayment(order, consumer, org, 10_000);
		postRefund(order, org, 3_000, "commerce-refund:" + order).expectStatus().isOk();
		client().post().uri("/internal/commerce/payments/" + order + "/split")
				.header("X-Grassland-Identity", signService(org, "marketplace"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "totalAmountCents", 10_000, "refundedAmountCents", 3_000,
						"recommenderAmountCents", 700, "merchantAmountCents", 5_950, "platformFeeCents", 350,
						"operationId", "commerce-split:" + order,
						"allocations", List.of(Map.of("recommenderAccountId", first, "amountCents", 400),
								Map.of("recommenderAccountId", second, "amountCents", 300))))
				.exchange().expectStatus().isOk();

		client().get().uri("/internal/commerce/payments/" + order + "/facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk()
				.expectBody()
				.jsonPath("$.data.payment.amountCents").isEqualTo(10_000)
				.jsonPath("$.data.payment.status").isEqualTo("partially_refunded")
				.jsonPath("$.data.refunds.length()").isEqualTo(1)
				.jsonPath("$.data.refunds[0].amountCents").isEqualTo(3_000)
				.jsonPath("$.data.split.status").isEqualTo("completed")
				.jsonPath("$.data.split.netTotalCents").isEqualTo(7_000)
				.jsonPath("$.data.split.allocations.length()").isEqualTo(2);

		// 只读：再次查询不改变退款/分账事实。
		assertThat(refundedCents(order)).isEqualTo(3_000L);
		// 非 marketplace principal / 无事实组织 → 403/404。
		client().get().uri("/internal/commerce/payments/" + order + "/facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "trust")).exchange().expectStatus().is4xxClientError();
		// 组织范围不符（断言组织 ≠ 查询组织）→ 4xx（403/404 均为拒绝，不泄露他组织事实存在性）。
		client().get()
				.uri("/internal/commerce/payments/" + order + "/facts?organizationId=" + UUID.randomUUID())
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().is4xxClientError();
		// 无事实订单 → 404。
		client().get()
				.uri("/internal/commerce/payments/none-" + UUID.randomUUID() + "/facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isNotFound();
	}

	// ---------- TC103-06-06：退款与分账父锁竞态（两种胜出顺序） ----------

	@Test
	void refundAndSplitSerializeOnPaymentParentLock() throws Exception {
		int rounds = 16;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				String order = "race-" + round + "-" + UUID.randomUUID();
				String consumer = UUID.randomUUID().toString();
				String recommender = UUID.randomUUID().toString();
				String org = UUID.randomUUID().toString();
				postPayment(order, consumer, org, 10_000);

				CountDownLatch start = new CountDownLatch(1);
				// 分账参数按「退款 3000 后的净额 7000」构造：分账先胜（净额=10000）时按净额校验合法拒绝，
				// 退款先胜时串行完成——父行锁保证两种顺序都单边推进、钱守恒。
				Future<Boolean> refundWon = pool.submit(() -> {
					if (!await(start)) return false;
					try {
						return service.refund(order, new ConsumerPaymentService.RefundCommand(org, 3_000,
								"commerce-refund:" + order, "race")).block() != null;
					} catch (Exception raceLost) {
						return false;
					}
				});
				Future<Boolean> splitWon = pool.submit(() -> {
					if (!await(start)) return false;
					try {
						return service.split(order, new ConsumerPaymentService.SplitCommand(org, 10_000,
								recommender, 700, 5_950, 350, "commerce-split:" + order, null, null))
								.block() != null;
					} catch (Exception netTotalMismatchOrInFlight) {
						return false;
					}
				});
				start.countDown();
				boolean refunded = refundWon.get(30, TimeUnit.SECONDS);
				boolean splitCompleted = splitWon.get(30, TimeUnit.SECONDS);
				// 退款在本轮参数下必然胜出（分账拒绝不写任何事实、不阻挡后续退款）。
				assertThat(refunded).as("round %d refund wins", round).isTrue();

				// 退款先胜 → 分账仍可完成但必须按锁内新累计退款（净额 7000 校验通过）；
				// 分账先胜 → 退款 409。两者可以先后都成功（合法串行链），但钱守恒：
				long refundedCents = refundedCents(order);
				assertThat(refundedCents).as("round %d refunded", round).isLessThanOrEqualTo(3_000L);
				if (splitCompleted) {
					// 分账完成后退款绝不允许新增（若退款在后完成则必为分账前预留）。
					assertThat(refundedCents).as("round %d final refunded", round).isLessThanOrEqualTo(3_000L);
					Long splitStatus = db.sql(
							"SELECT COUNT(*)::bigint AS c FROM consumer_payment_split"
									+ " WHERE order_ref = :o AND status = 'completed'")
							.bind("o", order).map(r -> r.get("c", Long.class)).one().block();
					assertThat(splitStatus).isEqualTo(1L);
				}
				// 守恒：累计退款 ×（若分账完成则三方=净额）。
				if (splitCompleted && refunded) {
					Long merchantPlusPlatform = db.sql(
							"SELECT merchant_amount_cents + platform_fee_cents AS mp FROM consumer_payment_split"
									+ " WHERE order_ref = :o")
							.bind("o", order).map(r -> r.get("mp", Long.class)).one().block();
					assertThat(merchantPlusPlatform).isEqualTo(6_300L);
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private static boolean await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private Long refundedCents(String order) {
		return db.sql("SELECT refunded_amount_cents FROM consumer_payment WHERE order_ref = :o")
				.bind("o", order).map(r -> r.get("refunded_amount_cents", Long.class)).one().block();
	}

	private void postPayment(String order, String consumer, String org, long amountCents) {
		client().post().uri("/internal/commerce/payments")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("orderRef", order, "consumerAccountId", consumer, "organizationId", org,
						"amountCents", amountCents, "operationId", "commerce-payment:" + order))
				.exchange().expectStatus().isCreated();
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postRefund(String order,
			String org, long amount, String operationId) {
		return client().post().uri("/internal/commerce/payments/" + order + "/refund")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "amountCents", amount, "operationId", operationId, "reason",
						"customer_request"))
				.exchange();
	}
}
