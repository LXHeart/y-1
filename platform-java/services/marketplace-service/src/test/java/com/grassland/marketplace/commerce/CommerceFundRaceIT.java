package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 审查修复 01 永久回归（R01/R02/R03/R07 → TC01-01..TC01-11）。
 *
 * <p>
 * 迁移自 scripts/local/project-audit-2026-09-09 的四个审查探针（断言从「缺陷证据」翻转为
 * 「业务不变量成立」），并补齐任务书 §6 的其余用例。远端延迟一律用受控 Sinks/计数器屏障安排， 不用长 sleep 碰运气。
 */
class CommerceFundRaceIT extends MarketplaceItSupport {

	@MockitoBean
	FinanceCommerceClient finance;

	@Autowired
	CommerceService service;

	@Autowired
	CommerceRepository repository;

	@Autowired
	OpsOrderHoldRepository holds;

	@Autowired
	CommerceFundOperationRepository fundOperations;

	@BeforeEach
	void sandboxFinanceDefaults() {
		when(finance.pay(any(Order.class)))
				.thenAnswer(i -> Mono.just("sandbox:payment:" + ((Order) i.getArgument(0)).id()));
		when(finance.refund(any(Order.class), any())).thenReturn(Mono.empty());
		when(finance.split(any(Order.class), any())).thenReturn(Mono.empty());
	}

	// ---------- TC01-01：支付在途 → 关单胜出 → 成功返回 → 补偿退款闭环 ----------

	@Test
	@DisplayName("TC01-01 支付未过期发出、在途时关单、最后成功返回：取消后完成补偿，库存只释放一次")
	void paymentRacingTimeoutEndsInCompensatedCancellation() throws Exception {
		Fixture f = fixtureWithFailingPay();
		Order snapshot = order(f);
		Sinks.One<String> reply = Sinks.one();
		AtomicLong netCaptured = new AtomicLong();
		AtomicInteger refundCalls = new AtomicInteger();
		when(finance.pay(any(Order.class))).thenReturn(reply.asMono().doOnNext(ref -> netCaptured.addAndGet(10_000)));
		when(finance.refund(any(Order.class), any())).thenAnswer(i -> {
			refundCalls.incrementAndGet();
			netCaptured.addAndGet(-((Order) i.getArgument(0)).refundRequestedAmountCents());
			return Mono.empty();
		});
		var pending = service.attemptPayment(snapshot).toFuture();

		db.sql("UPDATE consumer_order SET payment_deadline = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", f.id()).then().block();
		assertThat(service.cancelExpired(100).collectList().block()).extracting(Order::id).contains(f.id());

		reply.tryEmitValue("audit-captured").orThrow();
		Order result = pending.get(10, TimeUnit.SECONDS);

		// 取消终态 + 已完成退款的取消结果（不变量 1）；净收款归零（取消订单不保留未补偿的成功支付）。
		assertThat(result.status()).isEqualTo("cancelled");
		assertThat(netCaptured.get()).as("成功支付必须被补偿退款对冲").isZero();
		assertThat(refundCalls.get()).isEqualTo(1);
		ArgumentCaptor<Order> refundCaptor = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(1)).refund(refundCaptor.capture(), any());
		assertThat(refundCaptor.getValue().refundOperationId())
				.isEqualTo(CommerceFundOperationRepository.compensationOperationId(f.id()));
		assertThat(refundCaptor.getValue().refundRequestedAmountCents()).isEqualTo(10_000L);
		Order finalState = order(f);
		assertThat(finalState.refundedAmountCents()).isEqualTo(10_000L);
		assertThat(finalState.refundedAt()).isNotNull();
		assertThat(finalState.lastError()).isEqualTo("compensated_after_capture");
		// 库存只释放一次（关单路径）：remaining 回到 total。
		assertThat(packageRemaining(f)).isEqualTo(10);
		// 操作收尾：支付与补偿操作都 succeeded（恢复驱动不再重放）。
		assertThat(fundOperations.findByOrderAndType(f.id(), CommerceFundOperationRepository.TYPE_PAYMENT).block()
				.status()).isEqualTo("succeeded");
		assertThat(fundOperations.findByOrderAndType(f.id(), CommerceFundOperationRepository.TYPE_CANCEL_COMPENSATION)
				.block().status()).isEqualTo("succeeded");
	}

	// ---------- TC01-02：支付先成功 → 取消扫描不误伤、无补偿 ----------

	@Test
	@DisplayName("TC01-02 支付先成功再超时扫描：保持有效支付结果，不误退、不回补库存、无补偿操作")
	void paidOrderThenTimeoutScanKeepsPaymentWithoutCompensation() {
		Fixture f = fixtureWithInstantPay();
		assertThat(order(f).status()).isEqualTo("paid");
		assertThat(packageRemaining(f)).isEqualTo(9);

		db.sql("UPDATE consumer_order SET payment_deadline = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", f.id()).then().block();
		assertThat(service.cancelExpired(100).collectList().block()).isEmpty();

		assertThat(order(f).status()).isEqualTo("paid");
		assertThat(packageRemaining(f)).isEqualTo(9);
		verify(finance, never()).refund(any(Order.class), any());
		assertThat(fundOperations.findByOrderAndType(f.id(), CommerceFundOperationRepository.TYPE_CANCEL_COMPENSATION)
				.block()).as("未取消的订单不应登记补偿").isNull();
		assertThat(fundOperations.findByOrderAndType(f.id(), CommerceFundOperationRepository.TYPE_PAYMENT).block()
				.status()).isEqualTo("succeeded");
	}

	// ---------- TC01-03：成功回复丢失 + 取消 + 重启恢复 ----------

	@Test
	@DisplayName("TC01-03 支付成功回复丢失后关单：恢复驱动按同一操作键重放，不重复资金动作，补偿被接管")
	void lostPaymentReplyRecoveryReplaysSameOperationKeyAndCompensates() {
		Fixture f = fixtureWithFailingPay();
		Order snapshot = order(f);
		// 第一次回复丢失（finance 实际已入账），重试按同一 operationId 幂等重放成功。
		AtomicInteger payCalls = new AtomicInteger();
		when(finance.pay(any(Order.class))).thenAnswer(i -> payCalls.incrementAndGet() == 1
				? Mono.error(new IllegalStateException("reply lost after finance commit"))
				: Mono.just("sandbox:payment:" + ((Order) i.getArgument(0)).id()));
		service.attemptPayment(snapshot).block(Duration.ofSeconds(10));
		// 回复丢失：订单停留在 pending_payment（finance 幂等行已存在，本地零更新不当成功收尾）。
		assertThat(order(f).status()).isEqualTo("pending_payment");
		db.sql("UPDATE consumer_order SET payment_deadline = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", f.id()).then().block();
		assertThat(service.cancelExpired(100).collectList().block()).extracting(Order::id).contains(f.id());
		assertThat(order(f).status()).isEqualTo("cancelled");
		// 只统计恢复阶段的资金调用（此前已发生：下单一次 + 丢失回复一次）。
		org.mockito.Mockito.clearInvocations(finance);

		// 退避时间已过（fail 登记 +120s 退避，测试拨快）=「重启后」。
		db.sql("UPDATE commerce_fund_operation SET next_attempt_at = now() - interval '1 second'").then().block();
		service.recoverFundOperations(10).collectList().block(Duration.ofSeconds(10));

		assertThat(payCalls.get()).as("支付按同一操作键重放（不二次扣款由 finance 幂等保证）").isEqualTo(2);
		ArgumentCaptor<Order> payCaptor = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(1)).pay(payCaptor.capture());
		assertThat(payCaptor.getValue().paymentOperationId()).isEqualTo(snapshot.paymentOperationId());
		verify(finance, times(1)).refund(any(Order.class), any());
		ArgumentCaptor<Order> refundCaptor = ArgumentCaptor.forClass(Order.class);
		verify(finance).refund(refundCaptor.capture(), any());
		assertThat(refundCaptor.getValue().refundOperationId())
				.isEqualTo(CommerceFundOperationRepository.compensationOperationId(f.id()));
		Order finalState = order(f);
		assertThat(finalState.status()).isEqualTo("cancelled");
		assertThat(finalState.refundedAmountCents()).isEqualTo(10_000L);
	}

	// ---------- TC01-04：分账与售后两种先后顺序 ----------

	@Test
	@DisplayName("TC01-04a 分账先占位：在途开案 409 冲突；财务事实落库；结算后退款被 #97 闸门拒绝")
	void splitClaimFirstConflictsDisputeAndPersistsSettlementFact() throws Exception {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		Sinks.Empty<Void> reply = Sinks.empty();
		CountDownLatch called = new CountDownLatch(1);
		when(finance.split(any(Order.class), any())).thenReturn(Mono.defer(() -> {
			called.countDown();
			return reply.asMono();
		}));
		var pending = service.attemptSplit(order(f)).toFuture();
		assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();

		// 在途开案：按互斥契约返回冲突（处理中），不谎称已阻断资金。
		client().post().uri("/api/v2/orders/" + f.id() + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "audit dispute")).exchange().expectStatus().isEqualTo(409);

		reply.tryEmitEmpty().orThrow();
		pending.get(10, TimeUnit.SECONDS);
		Order settled = order(f);
		assertThat(settled.splitCompletedAt()).as("finance 已完成分账，结算事实必须落库").isNotNull();
		assertThat(settled.status()).isEqualTo("redeemed");

		// 结算后：争议仍可开（窗口语义），但裁定退款撞 #97 闸门。
		client().post().uri("/api/v2/orders/" + f.id() + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "post settle")).exchange().expectStatus().isCreated();
		resolveRefund(f, 3000).expectStatus().isEqualTo(409).expectBody().jsonPath("$.blockedReason")
				.isEqualTo(CommerceService.SETTLED_NO_REFUND);
	}

	@Test
	@DisplayName("TC01-04b 售后先开案：分账占位 0 行跳过，不发资金（既有 TC97-004 的占位路径回归）")
	void disputeFirstPreventsSplitClaim() {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		client().post().uri("/api/v2/orders/" + f.id() + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "first")).exchange().expectStatus().isCreated();
		service.attemptSplit(order(f)).block(Duration.ofSeconds(10));
		verify(finance, never()).split(any(Order.class), any());
		assertThat(order(f).splitCompletedAt()).isNull();
	}

	// ---------- TC01-05：分账与确认暂扣两种先后顺序 ----------

	@Test
	@DisplayName("TC01-05a 分账在途确认暂扣 409（不虚报阻断）；完成后确认可补登，资金只出一次")
	void holdConfirmDuringSplitClaimConflicts() throws Exception {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		Sinks.Empty<Void> reply = Sinks.empty();
		CountDownLatch called = new CountDownLatch(1);
		when(finance.split(any(Order.class), any())).thenReturn(Mono.defer(() -> {
			called.countDown();
			return reply.asMono();
		}));
		var pending = service.attemptSplit(order(f)).toFuture();
		assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();

		var flag = holds.insertFlagged(UUID.fromString(f.id()), "rlid_order_burst", "audit hold").block();
		String opsAuth = signWithRole(UUID.randomUUID().toString(), "risk");
		client().post().uri("/api/admin/commerce/order-holds/" + flag.id() + "/confirm")
				.header("X-Grassland-Identity", opsAuth).exchange().expectStatus().isEqualTo(409);

		reply.tryEmitEmpty().orThrow();
		pending.get(10, TimeUnit.SECONDS);
		assertThat(order(f).splitCompletedAt()).isNotNull();
		verify(finance, times(1)).split(any(Order.class), any());

		// 占位释放后确认可补登（标记追踪语义），但不阻断已完成的结算（split 已落账）。
		client().post().uri("/api/admin/commerce/order-holds/" + flag.id() + "/confirm")
				.header("X-Grassland-Identity", opsAuth).exchange().expectStatus().isOk();
	}

	@Test
	@DisplayName("TC01-05b 暂扣先确认：分账 claim 排除 held，不发资金；解除后恢复分账")
	void confirmedHoldFirstBlocksSplitUntilReleased() {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		var flag = holds.insertFlagged(UUID.fromString(f.id()), "rlid_order_burst", "audit hold").block();
		holds.confirm(flag.id(), UUID.randomUUID(), java.time.Instant.now().plusSeconds(3600)).block();

		service.attemptSplit(order(f)).block(Duration.ofSeconds(10));
		verify(finance, never()).split(any(Order.class), any());
		assertThat(order(f).splitCompletedAt()).isNull();
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id).doesNotContain(f.id());

		holds.release(flag.id(), UUID.randomUUID(), "复核无误，解除暂扣").block();
		service.attemptSplit(order(f)).block(Duration.ofSeconds(10));
		verify(finance, times(1)).split(any(Order.class), any());
		assertThat(order(f).splitCompletedAt()).isNotNull();
	}

	// ---------- TC01-06：已核销 100 元退 30 → 剩余 70 净额可结算 ----------

	@Test
	@DisplayName("TC01-06 已核销 100 元退 30：剩余 70 元净额分账，三方守恒，partially_refunded 仍被调度")
	void partialRefundAfterRedemptionLeavesNetDispatchable() {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		client().post().uri("/api/v2/orders/" + f.id() + "/after-sales-dispute")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "audit partial refund")).exchange().expectStatus().isCreated();
		resolveRefund(f, 3000).expectStatus().isOk();
		Order refunded = order(f);
		assertThat(refunded.status()).isEqualTo("partially_refunded");
		assertThat(refunded.refundedAmountCents()).isEqualTo(3000L);
		assertThat(refunded.splitCompletedAt()).isNull();
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id)
				.as("剩余 7000 分的已核销订单必须仍在结算队列").contains(f.id());

		service.attemptSplit(refunded).block(Duration.ofSeconds(10));

		ArgumentCaptor<Order> splitCaptor = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(1)).split(splitCaptor.capture(), any());
		Order sent = splitCaptor.getValue();
		// 冻结 0/9500/500（自然流量），累计退 3000 → 净额 0/6650/350。
		assertThat(sent.recommenderAmountCents()).isZero();
		assertThat(sent.merchantAmountCents()).isEqualTo(6_650L);
		assertThat(sent.platformFeeCents()).isEqualTo(350L);
		assertThat(sent.priceCents()).as("原支付额不变（不改写原金额让校验通过）").isEqualTo(10_000L);
		Order settled = order(f);
		assertThat(settled.splitCompletedAt()).isNotNull();
		assertThat(settled.status()).isEqualTo("partially_refunded");
		// 不变量 6：原支付额 = 累计退款 + 净分账三方之和。
		assertThat(settled.refundedAmountCents() + sent.recommenderAmountCents() + sent.merchantAmountCents()
				+ sent.platformFeeCents()).isEqualTo(settled.priceCents());
	}

	// ---------- TC01-07：未核销部分退款 → 核销可继续 / 到期只退余款 ----------

	@Test
	@DisplayName("TC01-07a 未核销部分退款后仍可核销（剩余履约义务保留）")
	void unredeemedPartialRefundCanStillBeRedeemed() {
		Fixture f = fixtureWithInstantPay();
		client().post().uri("/api/v2/orders/" + f.id() + "/refund")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("amountCents", 3000, "reason", "部分退")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("partially_refunded");

		redeem(f).expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("redeemed");
		assertThat(order(f).refundedAmountCents()).isEqualTo(3000L);
	}

	@Test
	@DisplayName("TC01-07b 未核销部分退款后到期：自动退剩余 7000，全额退款并补库存一次")
	void unredeemedPartialRefundExpiryRefundsOnlyRemainder() {
		Fixture f = fixtureWithInstantPay();
		client().post().uri("/api/v2/orders/" + f.id() + "/refund")
				.header("X-Grassland-Identity", sign(f.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("amountCents", 3000, "reason", "部分退")).exchange().expectStatus().isOk();

		db.sql("UPDATE consumer_order SET redeem_deadline = now() - interval '1 second' WHERE id = CAST(:id AS uuid)")
				.bind("id", f.id()).then().block();
		List<Order> claimed = service.claimExpired(100).collectList().block();
		assertThat(claimed).extracting(Order::id).contains(f.id());
		assertThat(claimed.get(0).refundRequestedAmountCents()).isEqualTo(7_000L);

		Order refunded = service.attemptRefund(claimed.get(0), "automatic_expiry").block(Duration.ofSeconds(10));
		assertThat(refunded.status()).isEqualTo("refunded");
		assertThat(refunded.refundedAmountCents()).isEqualTo(10_000L);
		assertThat(packageRemaining(f)).as("全额退款补库存一次").isEqualTo(10);
	}

	// ---------- TC01-08：重复部分退款与一次退满的累计一致性（IT 侧） ----------

	@Test
	@DisplayName("TC01-08 重复部分退款 3000+2000 后净额分账 = 一次退 5000 的净额（不超退不超分）")
	void repeatedPartialRefundsConvergeToSameNetSplit() {
		Fixture oneShot = fixtureWithInstantPay();
		Fixture incremental = fixtureWithInstantPay();
		for (Fixture target : List.of(oneShot, incremental)) {
			makeRedeemedAndDue(target);
			client().post().uri("/api/v2/orders/" + target.id() + "/after-sales-dispute")
					.header("X-Grassland-Identity", sign(target.consumer(), null))
					.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "r")).exchange().expectStatus()
					.isCreated();
		}
		resolveRefund(oneShot, 5000).expectStatus().isOk();
		resolveRefund(incremental, 3000).expectStatus().isOk();
		// 追加退款：第二次争议开案（第一次已 resolved 后可再开——UNIQUE(order_id) 一行终身制，
		// 这里改走消费者退款入口完成第二次部分退款）。
		client().post().uri("/api/v2/orders/" + incremental.id() + "/refund")
				.header("X-Grassland-Identity", sign(incremental.consumer(), null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 2000, "reason", "追加"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.refundedAmountCents").isEqualTo(5000);

		for (Fixture target : List.of(oneShot, incremental)) {
			service.attemptSplit(order(target)).block(Duration.ofSeconds(10));
		}
		ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
		verify(finance, times(2)).split(captor.capture(), any());
		Order first = captor.getAllValues().get(0);
		Order second = captor.getAllValues().get(1);
		assertThat(second.merchantAmountCents()).isEqualTo(first.merchantAmountCents()).isEqualTo(4_750L);
		assertThat(second.platformFeeCents()).isEqualTo(first.platformFeeCents()).isEqualTo(250L);
		assertThat(second.recommenderAmountCents()).isEqualTo(first.recommenderAmountCents()).isZero();
		// 超退防护：可退余额已为 0，再退被拒。
		client().post().uri("/api/v2/orders/" + oneShot.id() + "/refund")
				.header("X-Grassland-Identity", sign(oneShot.consumer(), null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("amountCents", 1, "reason", "超退")).exchange().expectStatus().isEqualTo(409);
	}

	// ---------- TC01-09：held 超批次不饿死正常订单 ----------

	@Test
	@DisplayName("TC01-09 held 数量超过批次：正常单有限轮次被处理；解除后 held 恢复且不重复分账")
	void heldOrdersMustNotStarveOtherDispatchableOrders() {
		Fixture held = fixtureWithInstantPay();
		makeRedeemedAndDue(held);
		var flag = holds.insertFlagged(UUID.fromString(held.id()), "rlid_order_burst", "audit hold").block();
		holds.confirm(flag.id(), UUID.randomUUID(), java.time.Instant.now().plusSeconds(3600)).block();
		db.sql("UPDATE consumer_order SET updated_at = now() - interval '10 days' WHERE id = CAST(:id AS uuid)")
				.bind("id", held.id()).then().block();

		Fixture normal = fixtureWithInstantPay();
		makeRedeemedAndDue(normal);

		// SQL 级排除（LIMIT 无关）：生效中的 held 不进调度集合，正常到期单在集合内。
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id).doesNotContain(held.id())
				.contains(normal.id());
		service.attemptSplit(order(normal)).block(Duration.ofSeconds(10));
		verify(finance, times(1)).split(any(Order.class), any());

		// 解除暂扣：held 单恢复进入可执行集合并完成分账（一次性）。
		holds.release(flag.id(), UUID.randomUUID(), "复核解除").block();
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id).contains(held.id());
		service.attemptSplit(order(held)).block(Duration.ofSeconds(10));
		verify(finance, times(2)).split(any(Order.class), any());
		assertThat(order(held).splitCompletedAt()).isNotNull();
	}

	// ---------- TC01-10：多实例 / 旧租约迟到 / 收尾失败与恢复 ----------

	@Test
	@DisplayName("TC01-10a 双执行者并发占位：单胜者发资金；陈旧快照的第二执行者 0 行跳过不重发")
	void onlyOneExecutorWinsSplitClaim() throws Exception {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		Order stale = order(f); // 占位前的快照（redeemed+due）
		Sinks.Empty<Void> reply = Sinks.empty();
		CountDownLatch called = new CountDownLatch(1);
		when(finance.split(any(Order.class), any())).thenReturn(Mono.defer(() -> {
			called.countDown();
			return reply.asMono();
		}));
		var pending = service.attemptSplit(stale).toFuture();
		assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();
		// 第二执行者以陈旧快照进入：claim 0 行（行已 splitting）→ 跳过，不重发资金。
		Order second = service.attemptSplit(stale).block(Duration.ofSeconds(10));
		assertThat(second.splitCompletedAt()).isNull();

		reply.tryEmitEmpty().orThrow();
		pending.get(10, TimeUnit.SECONDS);
		verify(finance, times(1)).split(any(Order.class), any());
		assertThat(order(f).splitCompletedAt()).isNotNull();
	}

	@Test
	@DisplayName("TC01-10b 资金操作租约：持有中不可再领，过期后可被其他实例接管")
	void fundOperationLeaseBlocksUntilExpiryThenTakeover() {
		Fixture f = fixtureWithFailingPay();
		db.sql("UPDATE consumer_order SET status = 'cancelled' WHERE id = CAST(:id AS uuid)").bind("id", f.id()).then()
				.block();
		// 支付失败登记带退避；拨快到期（恢复语义 = 退避已过）。
		db.sql("UPDATE commerce_fund_operation SET next_attempt_at = now() - interval '1 second'").then().block();

		var first = fundOperations.claimRecoverable(10, "instance-a", Duration.ofSeconds(60)).collectList().block();
		assertThat(first).hasSize(1);
		assertThat(first.get(0).leaseOwner()).isEqualTo("instance-a");
		assertThat(fundOperations.claimRecoverable(10, "instance-b", Duration.ofSeconds(60)).collectList().block())
				.as("租约未过期，其他实例领取不到").isEmpty();

		db.sql("UPDATE commerce_fund_operation SET lease_expires_at = now() - interval '1 second'").then().block();
		var takeover = fundOperations.claimRecoverable(10, "instance-b", Duration.ofSeconds(60)).collectList().block();
		assertThat(takeover).hasSize(1);
		assertThat(takeover.get(0).leaseOwner()).isEqualTo("instance-b");
	}

	@Test
	@DisplayName("TC01-10c 迟到的旧执行者不覆盖新结果：占位归还后收尾 0 行")
	void staleFinalizerCannotOverwriteAbandonedClaim() {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		db.sql("UPDATE consumer_order SET status = 'splitting' WHERE id = CAST(:id AS uuid)").bind("id", f.id()).then()
				.block();
		Order abandoned = repository.abandonSplitClaim(f.id(), "finance unavailable").block();
		assertThat(abandoned.status()).isEqualTo("redeemed");
		// 迟到的旧执行者尝试收尾：0 行（守卫 splitting），不覆盖归还后的状态。
		assertThat(repository.markSplitCompleted(f.id()).block()).isNull();
		assertThat(order(f).splitCompletedAt()).isNull();
		assertThat(order(f).status()).isEqualTo("redeemed");
	}

	@Test
	@DisplayName("TC01-10d splitting 崩溃恢复：dispatcher 重捞 → 幂等重发 → 事实落库")
	void splittingRowIsRecoveredByIdempotentReplay() {
		Fixture f = fixtureWithInstantPay();
		makeRedeemedAndDue(f);
		db.sql("UPDATE consumer_order SET status = 'splitting' WHERE id = CAST(:id AS uuid)").bind("id", f.id()).then()
				.block();
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id).contains(f.id());
		service.attemptSplit(order(f)).block(Duration.ofSeconds(10));
		verify(finance, times(1)).split(any(Order.class), any());
		assertThat(order(f).splitCompletedAt()).isNotNull();
		assertThat(order(f).status()).isEqualTo("redeemed");
	}

	// ---------- TC01-11：存量 redeeming 行接管（结算后禁令由 CommerceSettlementRefundGateIT 锁定）
	// ----------

	@Test
	@DisplayName("TC01-11 旧 redeeming 在途单被新驱动接管：claim → 分账 → 补发核销事件收尾")
	void legacyRedeemingRowIsTakenOverByNewDriver() {
		Fixture f = fixtureWithInstantPay();
		db.sql("UPDATE consumer_order SET status = 'redeeming',"
				+ " split_operation_id = 'commerce-split:' || id::text WHERE id = CAST(:id AS uuid)").bind("id", f.id())
				.then().block();
		assertThat(service.pendingDispatch(100).collectList().block()).extracting(Order::id).contains(f.id());

		service.attemptSplit(order(f)).block(Duration.ofSeconds(10));
		verify(finance, times(1)).split(any(Order.class), any());
		Order done = order(f);
		assertThat(done.status()).isEqualTo("redeemed");
		assertThat(done.splitCompletedAt()).isNotNull();
		Long events = db
				.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox WHERE event_type = 'ConsumerOrderRedeemed'"
						+ " AND aggregate_id = :agg")
				.bind("agg", f.id()).map(r -> r.get("c", Long.class)).one().block();
		assertThat(events).isEqualTo(1L);
	}

	// ---------- helpers ----------

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec resolveRefund(Fixture f,
			long amount) {
		return client().post().uri("/api/v2/orders/" + f.id() + "/after-sales-dispute/resolve")
				.header("X-Grassland-Identity", sign(f.merchant(), "merchant", f.org(), "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("resolution", "refund", "amountCents", amount, "reason", "audit decision"))
				.exchange();
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec redeem(Fixture f) {
		return client().post().uri("/api/v2/merchant/redemptions")
				.header("X-Grassland-Identity", sign(f.merchant(), "merchant", f.org(), "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", codes.codeForOrder(f.id())))
				.exchange();
	}

	@Autowired
	RedeemCodeCodec codes;

	private void makeRedeemedAndDue(Fixture f) {
		repository.markRedeemedWithCooldown(f.id(), "commerce-split:" + f.id(), java.time.Instant.now().minusSeconds(1))
				.block();
	}

	private Order order(Fixture f) {
		return repository.findOrder(f.id()).block();
	}

	private int packageRemaining(Fixture f) {
		Integer value = db.sql("""
				SELECT i.remaining_stock FROM commerce_package_inventory i
				JOIN consumer_order o ON o.package_version_id = i.package_version_id
				WHERE o.id = CAST(:id AS uuid)
				""").bind("id", f.id()).map(r -> r.get("remaining_stock", Integer.class)).one().block();
		return value == null ? -1 : value;
	}

	/** 支付失败的未支付单（泄漏场景复现）：订单停留 pending_payment，fund op 已占位。 */
	@SuppressWarnings("unchecked")
	private Fixture fixtureWithFailingPay() {
		when(finance.pay(any(Order.class)))
				.thenReturn(Mono.error(new IllegalStateException("sandbox pay gateway down")));
		return createFixture();
	}

	/** 默认 sandbox 即时支付成功。 */
	private Fixture fixtureWithInstantPay() {
		return createFixture();
	}

	@SuppressWarnings("unchecked")
	private Fixture createFixture() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String consumer = UUID.randomUUID().toString();
		Map<String, Object> response = client().post().uri("/api/v2/merchant/packages")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("organizationId", org, "title", "资金闭环审查套餐", "priceCents", 10000, "totalStock", 10,
						"validDaysAfterPurchase", 30, "recommenderShareBps", 0, "platformFeeBps", 500, "policyVersion",
						"commerce-v1"))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
		String packageId = String.valueOf(((Map<String, Object>) response.get("data")).get("id"));
		client().post().uri("/api/v2/merchant/packages/" + packageId + "/publish")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isOk();
		Map<String, Object> created = client().post().uri("/api/v2/orders")
				.header("X-Grassland-Identity", sign(consumer, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("packageId", packageId)).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		return new Fixture(String.valueOf(((Map<String, Object>) created.get("data")).get("id")), merchant, org,
				consumer);
	}

	record Fixture(String id, String merchant, String org, String consumer) {
	}
}
