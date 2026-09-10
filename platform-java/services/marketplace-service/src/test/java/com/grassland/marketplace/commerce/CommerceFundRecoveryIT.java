package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 支付退避、领取租约和分账未知结果的永久回归。真实 HTTP / PostgreSQL 路径， 仅控制远端 Finance
 * 的返回时序；保护退款互斥和批次前进性。
 */
class CommerceFundRecoveryIT extends MarketplaceItSupport {
	@MockitoBean
	FinanceCommerceClient finance;
	@Autowired
	CommerceService service;
	@Autowired
	CommerceRepository repository;
	@Autowired
	CommerceFundOperationRepository fundOperations;
	private final List<String> createdIds = new ArrayList<>();

	@BeforeEach
	void financeDefaults() {
		when(finance.pay(any(Order.class))).thenAnswer(i -> Mono.just("audit:" + ((Order) i.getArgument(0)).id()));
		when(finance.refund(any(Order.class), any())).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), any())).thenReturn(Mono.empty());
	}

	@AfterEach
	void isolatePendingRows() {
		for (String id : createdIds) {
			// 本类自己创建的未收口行不留给其他套件的全局 dispatcher；不修改其他测试的数据。
			db.sql("UPDATE consumer_order SET status = 'cancelled' WHERE id = CAST(:id AS uuid) AND split_completed_at IS NULL")
					.bind("id", id).then().block();
			db.sql("UPDATE commerce_fund_operation SET status = 'needs_review' WHERE order_id = CAST(:id AS uuid) AND status <> 'succeeded'")
					.bind("id", id).then().block();
		}
	}

	@Test
	void pendingPaymentHonorsBackoffAndResumesWhenDue() {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture fixture = createFixture();
		var operation = paymentOperation(fixture);
		assertThat(operation.nextAttemptAt()).isAfter(Instant.now());
		assertThat(operation.attempts()).isEqualTo(1);
		clearInvocations(finance);

		// One immediate dispatcher pass, with the database's future retry time
		// untouched.
		var selected = service.pendingDispatch(100).filter(o -> o.id().equals(fixture.id())).collectList().block();
		for (Order order : selected)
			service.attemptPayment(order).block(Duration.ofSeconds(10));

		assertThat(selected).isEmpty();
		// 直接入口同样必须遵守退避，不能仅依赖扫描器过滤。
		service.attemptPayment(repository.findOrder(fixture.id()).block()).block();
		verify(finance, never()).pay(any(Order.class));
		assertThat(paymentOperation(fixture).attempts()).isEqualTo(1);

		makePaymentDue(fixture);
		when(finance.pay(any(Order.class))).thenReturn(Mono.just("recovered-payment"));
		assertThat(service.attemptPayment(repository.findOrder(fixture.id()).block()).block().status())
				.isEqualTo("paid");
		verify(finance, times(1)).pay(any(Order.class));
		assertThat(paymentOperation(fixture).attempts()).isEqualTo(2);
		assertThat(paymentOperation(fixture).status()).isEqualTo("succeeded");
	}

	@ParameterizedTest
	@ValueSource(strings = {"needs_review", "backoff", "leased", "expired"})
	void nonExecutablePaymentDoesNotConsumeDispatchBatch(String blocker) {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture blocked = createFixture();
		if (blocker.equals("needs_review")) {
			db.sql("UPDATE commerce_fund_operation SET status = 'needs_review' WHERE order_id = CAST(:id AS uuid)")
					.bind("id", blocked.id()).then().block();
		} else if (blocker.equals("leased")) {
			makePaymentDue(blocked);
			assertThat(fundOperations.claim(paymentOperation(blocked).operationId(), "another-worker",
					Duration.ofMinutes(1), "pending_payment").block()).isNotNull();
		} else if (blocker.equals("expired")) {
			makePaymentDue(blocked);
			db.sql("UPDATE consumer_order SET payment_deadline = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
					.bind("id", blocked.id()).then().block();
		}
		// 排序早于其他套件按当前时间创建的行，避免共享数据库的无关新数据干扰 LIMIT 验证。
		db.sql("UPDATE consumer_order SET updated_at = TIMESTAMPTZ '1000-01-01 00:00:00+00' WHERE id = CAST(:id AS uuid)")
				.bind("id", blocked.id()).then().block();

		Fixture ready = createFixture();
		makePaymentDue(ready);
		db.sql("UPDATE consumer_order SET updated_at = TIMESTAMPTZ '1000-01-02 00:00:00+00' WHERE id = CAST(:id AS uuid)")
				.bind("id", ready.id()).then().block();
		when(finance.pay(any(Order.class))).thenAnswer(i -> Mono.just("audit:" + ((Order) i.getArgument(0)).id()));
		clearInvocations(finance);

		List<Order> selected = service.pendingDispatch(1).collectList().block();
		assertThat(selected).extracting(Order::id).containsExactly(ready.id());
		service.attemptPayment(selected.getFirst()).block(Duration.ofSeconds(10));
		assertThat(repository.findOrder(ready.id()).block().status())
				.as("a ready order must progress past an ineligible order within a bounded batch").isEqualTo("paid");
	}

	@Test
	void splitSuccessWithLostReplyMustNotPermitARefund() {
		Fixture fixture = createFixture();
		repository
				.markRedeemedWithCooldown(fixture.id(), "commerce-split:" + fixture.id(), Instant.now().minusSeconds(1))
				.block();
		AtomicBoolean remoteSplitCommitted = new AtomicBoolean();
		AtomicInteger remoteRefundCalls = new AtomicInteger();
		when(finance.split(any(Order.class), any())).thenAnswer(i -> Mono.defer(() -> {
			remoteSplitCommitted.set(true);
			return Mono.error(
					new FinanceCommerceClient.FinanceCommerceException(0, "reply lost after remote split commit"));
		}));
		when(finance.refund(any(Order.class), any())).thenAnswer(i -> {
			remoteRefundCalls.incrementAndGet();
			return Mono.empty();
		});
		service.attemptSplit(repository.findOrder(fixture.id()).block()).block(Duration.ofSeconds(10));
		assertThat(remoteSplitCommitted).isTrue();
		Order afterLostReply = repository.findOrder(fixture.id()).block();
		assertThat(afterLostReply.status()).isEqualTo("splitting");
		assertThat(afterLostReply.splitCompletedAt()).isNull();

		int openStatus = client().post().uri("/api/v2/orders/" + fixture.id() + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(fixture.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "audit after unknown settlement result")).exchange()
				.returnResult(String.class).getStatus().value();
		int refundStatus = client().post().uri("/api/v2/orders/" + fixture.id() + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity",
						sign(fixture.merchant(), "merchant", fixture.org(), "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("resolution", "refund", "amountCents", 3000,
						"reason", "audit refund after remote split"))
				.exchange().returnResult(String.class).getStatus().value();

		assertThat(openStatus).isEqualTo(409);
		assertThat(remoteRefundCalls.get()).as("unknown/committed settlement must not enable a new refund").isZero();
		assertThat(refundStatus).isEqualTo(409);

		when(finance.split(any(Order.class), any())).thenReturn(Mono.empty());
		Order settled = service.attemptSplit(afterLostReply).block();
		assertThat(settled.status()).isEqualTo("redeemed");
		assertThat(settled.splitCompletedAt()).isNotNull();
		assertThat(settled.refundedAmountCents()).isZero();
		ArgumentCaptor<Order> calls = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(2)).split(calls.capture(), any());
		assertThat(calls.getAllValues()).extracting(Order::splitOperationId)
				.containsOnly("commerce-split:" + fixture.id());
	}

	@Test
	void overlappingPaymentAttemptsClaimOneLease() throws Exception {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture fixture = createFixture();
		makePaymentDue(fixture);
		clearInvocations(finance);
		Sinks.One<String> reply = Sinks.one();
		CountDownLatch started = new CountDownLatch(1);
		when(finance.pay(any(Order.class))).thenReturn(reply.asMono().doOnSubscribe(s -> started.countDown()));
		Order snapshot = repository.findOrder(fixture.id()).block();
		var first = service.attemptPayment(snapshot).toFuture();
		try {
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(service.attemptPayment(snapshot).block().status()).isEqualTo("pending_payment");
			verify(finance, times(1)).pay(any(Order.class));
			assertThat(paymentOperation(fixture).attempts()).isEqualTo(2);
			reply.tryEmitValue("one-payment").orThrow();
			assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("paid");
			assertThat(paymentOperation(fixture).leaseOwner()).isNull();
		} finally {
			reply.tryEmitError(new IllegalStateException("test cleanup"));
		}
	}

	@Test
	void stalePaymentFailureCannotReleaseNewLeaseOrCountTwice() {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture fixture = createFixture();
		makePaymentDue(fixture);
		String operationId = paymentOperation(fixture).operationId();
		var first = fundOperations.claim(operationId, "old-claim", Duration.ofMinutes(1), "pending_payment").block();
		assertThat(first).isNotNull();
		assertThat(
				fundOperations.claim(operationId, "competing-claim", Duration.ofMinutes(1), "pending_payment").block())
				.isNull();
		db.sql("UPDATE commerce_fund_operation SET lease_expires_at = now() - interval '1 second' WHERE operation_id = :id")
				.bind("id", operationId).then().block();
		var second = fundOperations.claim(operationId, "new-claim", Duration.ofMinutes(1), "pending_payment").block();
		assertThat(second.attempts()).isEqualTo(3);
		assertThat(fundOperations.fail(first, true, "late old rejection").block()).isNull();
		assertThat(paymentOperation(fixture).leaseOwner()).isEqualTo("new-claim");
		assertThat(paymentOperation(fixture).status()).isEqualTo("in_flight");
		var failure = fundOperations.fail(second, false, "retry later").block();
		assertThat(failure.attempts()).isEqualTo(3);
		assertThat(failure.nextAttemptAt()).isAfter(Instant.now());
		assertThat(failure.leaseOwner()).isNull();
		assertThat(fundOperations.fail(second, false, "duplicate failure").block()).isNull();
		assertThat(paymentOperation(fixture).attempts()).isEqualTo(3);
	}

	@Test
	void recoveryClaimsCountOnceAndExhaustedOperationsLeaveTheQueue() {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture fixture = createFixture();
		db.sql("UPDATE consumer_order SET status = 'cancelled' WHERE id = CAST(:id AS uuid)").bind("id", fixture.id())
				.then().block();
		for (int attempt = 2; attempt <= 8; attempt++) {
			db.sql("UPDATE commerce_fund_operation SET next_attempt_at = TIMESTAMPTZ '1000-01-01 00:00:00+00' WHERE order_id = CAST(:id AS uuid)")
					.bind("id", fixture.id()).then().block();
			var claimed = fundOperations.claimRecoverable(1, "recovery-" + attempt, Duration.ofMinutes(1)).collectList()
					.block();
			assertThat(claimed).hasSize(1);
			assertThat(claimed.getFirst().orderId()).isEqualTo(fixture.id());
			assertThat(claimed.getFirst().attempts()).isEqualTo(attempt);
			var failed = fundOperations.fail(claimed.getFirst(), false, "unknown result").block();
			assertThat(failed.attempts()).isEqualTo(attempt);
			assertThat(failed.status()).isEqualTo(attempt == 8 ? "needs_review" : "in_flight");
		}
		makePaymentDue(fixture);
		assertThat(fundOperations
				.claim(paymentOperation(fixture).operationId(), "ninth", Duration.ofMinutes(1), "cancelled").block())
				.isNull();
		// 稳定操作键的迟到成功是资金事实，仍可收口待核对项。
		assertThat(fundOperations.succeed(paymentOperation(fixture).operationId(), "confirmed-late-success").block()
				.status()).isEqualTo("succeeded");
	}

	@Test
	void splitCommitFailureRetainsProtectionAndReplaysTheSameOperation() {
		Fixture fixture = createFixture();
		repository
				.markRedeemedWithCooldown(fixture.id(), "commerce-split:" + fixture.id(), Instant.now().minusSeconds(1))
				.block();
		String fault = "audit_split_commit_" + UUID.randomUUID().toString().replace("-", "");
		// 只在本测试订单写入结算事实后的 COMMIT 阶段报错，外部分账已成功。
		db.sql("CREATE FUNCTION " + fault + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "RAISE EXCEPTION 'injected split commit failure'; END $$").then().block();
		try {
			db.sql("CREATE CONSTRAINT TRIGGER " + fault
					+ " AFTER UPDATE ON consumer_order DEFERRABLE INITIALLY DEFERRED " + "FOR EACH ROW WHEN (NEW.id = '"
					+ fixture.id() + "'::uuid AND NEW.split_completed_at IS NOT NULL) " + "EXECUTE FUNCTION " + fault
					+ "()").then().block();
			Order result = service.attemptSplit(repository.findOrder(fixture.id()).block()).block();
			assertThat(result.status()).isEqualTo("splitting");
			assertThat(result.splitCompletedAt()).isNull();
			assertThat(result.lastError()).isNotBlank();
			verify(finance, times(1)).split(any(Order.class), any());
		} finally {
			db.sql("DROP TRIGGER IF EXISTS " + fault + " ON consumer_order").then().block();
			db.sql("DROP FUNCTION IF EXISTS " + fault + "()").then().block();
		}
		Order settled = service.attemptSplit(repository.findOrder(fixture.id()).block()).block();
		assertThat(settled.status()).isEqualTo("redeemed");
		assertThat(settled.splitCompletedAt()).isNotNull();
		assertThat(settled.lastError()).isNull();
		ArgumentCaptor<Order> calls = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(2)).split(calls.capture(), any());
		assertThat(calls.getAllValues()).extracting(Order::splitOperationId)
				.containsOnly("commerce-split:" + fixture.id());
	}

	@Test
	void laterRejectionDoesNotUndoAnEarlierUnknownSplit() {
		Fixture fixture = createFixture();
		repository
				.markRedeemedWithCooldown(fixture.id(), "commerce-split:" + fixture.id(), Instant.now().minusSeconds(1))
				.block();
		when(finance.split(any(Order.class), any()))
				.thenReturn(Mono.error(new FinanceCommerceClient.FinanceCommerceException(0, "reply lost"))).thenReturn(
						Mono.error(new FinanceCommerceClient.FinanceCommerceException(409, "requires reconciliation")));
		Order unknown = service.attemptSplit(repository.findOrder(fixture.id()).block()).block();
		assertThat(unknown.status()).isEqualTo("splitting");
		Order rejected = service.attemptSplit(unknown).block();
		assertThat(rejected.status()).isEqualTo("splitting");
		assertThat(rejected.splitCompletedAt()).isNull();
		assertThat(rejected.lastError()).contains("requires reconciliation");
	}

	@Test
	void lateSplitFailureReturnsTheAlreadyCommittedSettlement() throws Exception {
		Fixture fixture = createFixture();
		repository
				.markRedeemedWithCooldown(fixture.id(), "commerce-split:" + fixture.id(), Instant.now().minusSeconds(1))
				.block();
		Sinks.One<Void> firstReply = Sinks.one();
		CountDownLatch started = new CountDownLatch(1);
		when(finance.split(any(Order.class), any()))
				.thenReturn(firstReply.asMono().doOnSubscribe(s -> started.countDown())).thenReturn(Mono.empty());
		var first = service.attemptSplit(repository.findOrder(fixture.id()).block()).toFuture();
		try {
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			Order settled = service.attemptSplit(repository.findOrder(fixture.id()).block()).block();
			assertThat(settled.splitCompletedAt()).isNotNull();
			firstReply.tryEmitError(new IllegalStateException("late connection reset")).orThrow();
			Order lateResult = first.get(10, TimeUnit.SECONDS);
			assertThat(lateResult.splitCompletedAt()).isEqualTo(settled.splitCompletedAt());
			assertThat(lateResult.status()).isEqualTo("redeemed");
			assertThat(lateResult.lastError()).isNull();
		} finally {
			firstReply.tryEmitError(new IllegalStateException("test cleanup"));
		}
	}

	@Test
	void cancelledPaymentCompensationHonorsBackoffAndKeepsItsOperationKey() throws Exception {
		when(finance.pay(any(Order.class))).thenReturn(Mono.error(new IllegalStateException("temporary outage")));
		Fixture fixture = createFixture();
		makePaymentDue(fixture);
		Sinks.One<String> reply = Sinks.one();
		CountDownLatch started = new CountDownLatch(1);
		when(finance.pay(any(Order.class))).thenReturn(reply.asMono().doOnSubscribe(s -> started.countDown()));
		when(finance.refund(any(Order.class), any()))
				.thenReturn(Mono.error(new IllegalStateException("refund reply lost")));
		var payment = service.attemptPayment(repository.findOrder(fixture.id()).block()).toFuture();
		try {
			assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
			client().post().uri("/api/v2/orders/" + fixture.id() + "/cancel")
					.header("X-Grassland-Identity", sign(fixture.consumer(), null)).exchange().expectStatus().isOk();
			reply.tryEmitValue("captured-before-cancel").orThrow();
			assertThat(payment.get(10, TimeUnit.SECONDS).status()).isEqualTo("cancelled");
			var compensation = fundOperations
					.findByOrderAndType(fixture.id(), CommerceFundOperationRepository.TYPE_CANCEL_COMPENSATION).block();
			assertThat(compensation.status()).isEqualTo("in_flight");
			assertThat(compensation.attempts()).isEqualTo(1);
			assertThat(compensation.nextAttemptAt()).isAfter(Instant.now());
			clearInvocations(finance);
			service.attemptCancelCompensation(fixture.id()).block();
			verify(finance, never()).refund(any(Order.class), any());
			db.sql("UPDATE commerce_fund_operation SET next_attempt_at = now() - interval '1 second' WHERE operation_id = :id")
					.bind("id", compensation.operationId()).then().block();
			when(finance.refund(any(Order.class), any())).thenReturn(Mono.empty());
			Order completed = service.attemptCancelCompensation(fixture.id()).block();
			assertThat(completed.status()).isEqualTo("cancelled");
			assertThat(completed.refundedAmountCents()).isEqualTo(completed.priceCents());
			var done = fundOperations.find(compensation.operationId()).block();
			assertThat(done.status()).isEqualTo("succeeded");
			assertThat(done.attempts()).isEqualTo(2);
			ArgumentCaptor<Order> refunded = ArgumentCaptor.forClass(Order.class);
			verify(finance).refund(refunded.capture(), any());
			assertThat(refunded.getValue().refundOperationId()).isEqualTo(compensation.operationId());
		} finally {
			reply.tryEmitError(new IllegalStateException("test cleanup"));
		}
	}

	private CommerceFundOperationRepository.FundOperation paymentOperation(Fixture fixture) {
		return fundOperations.findByOrderAndType(fixture.id(), CommerceFundOperationRepository.TYPE_PAYMENT).block();
	}

	private void makePaymentDue(Fixture fixture) {
		db.sql("UPDATE commerce_fund_operation SET next_attempt_at = now() - interval '1 second'"
				+ " WHERE order_id = CAST(:id AS uuid) AND operation_type = 'payment'").bind("id", fixture.id()).then()
				.block();
	}

	@SuppressWarnings("unchecked")
	private Fixture createFixture() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> response = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "title", "audit fund recovery", "priceCents", 10000,
						"totalStock", 10, "validDaysAfterPurchase", 30, "recommenderShareBps", 0, "platformFeeBps", 500,
						"policyVersion", "commerce-v1"))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String packageId = String.valueOf(((Map<String, Object>) response.get("data")).get("id"));
		client().post().uri("/api/v2/merchant/packages/" + packageId + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		Map<String, Object> created = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId)).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String id = String.valueOf(((Map<String, Object>) created.get("data")).get("id"));
		createdIds.add(id);
		return new Fixture(id, merchant, org, consumer);
	}

	record Fixture(String id, String merchant, String org, String consumer) {
	}
}
