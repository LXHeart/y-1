package com.grassland.marketplace.commerce;

import static com.grassland.marketplace.commerce.CommerceService.FUND_LEASE;
import static com.grassland.marketplace.commerce.CommerceService.SETTLED_NO_REFUND;
import static com.grassland.marketplace.commerce.CommerceService.blank;
import static com.grassland.marketplace.commerce.CommerceService.definitive;
import static com.grassland.marketplace.commerce.CommerceService.orderEvent;

import com.grassland.marketplace.commerce.CommerceFundOperationRepository.FundOperation;
import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceService.DisputeResolutionCommand;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：消费者退款与售后争议服务——自 {@link CommerceService} 按职责原样搬移
 * （退款请求与执行收尾/售后争议开案·读取·裁定退款/取消后补偿退款/已结算退款闸门），逻辑零变更； facade 以委托保持既有公共
 * API（controller/dispatcher 调用方零改动）。
 */
@Component
public class ConsumerRefundService {

	private final CommerceRepository repository;
	private final TaskResourceAuthorization authorization;
	private final CommerceRefundClaimService refundClaims;
	private final FinanceCommerceClient finance;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final CommerceFundOperationRepository fundOperations;
	private final String recoveryOwner;

	public ConsumerRefundService(CommerceRepository repository, TaskResourceAuthorization authorization,
			CommerceRefundClaimService refundClaims, FinanceCommerceClient finance, OutboxRepository outbox,
			TransactionalOperator transactions, CommerceFundOperationRepository fundOperations) {
		this.repository = repository;
		this.authorization = authorization;
		this.refundClaims = refundClaims;
		this.finance = finance;
		this.outbox = outbox;
		this.transactions = transactions;
		this.fundOperations = fundOperations;
		// 每轮领取再附加随机令牌，阻止本进程或其他副本上一轮的迟到失败覆盖新租约（照搬 facade 语义）。
		this.recoveryOwner = "commerce-refund-" + UUID.randomUUID();
	}

	public Mono<Order> requestRefund(Caller caller, String orderId, Long requestedAmountCents, String reason) {
		String requestedReason = normalizedRefundReason(reason, "consumer_request");
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			if ("refund_pending".equals(order.status()))
				return attemptRefund(order, requestedReason);
			if ("splitting".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "订单结算处理中，请稍后再申请退款"));
			}
			if (!"paid".equals(order.status()) && !"partially_refunded".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "当前订单状态不可退款"));
			}
			// 任务书 #97 D97-01：已结算（split_completed_at 落账）不再支持退款——分账后售后部分退款
			// 留下的 partially_refunded 单在此统一拒绝，不再落到钱包余额裸 409。
			return requireNotSettled(order).flatMap(unsettled -> {
				long amount = requestedAmountCents == null
						? unsettled.priceCents() - unsettled.refundedAmountCents()
						: requestedAmountCents;
				if (amount <= 0 || amount > unsettled.priceCents() - unsettled.refundedAmountCents()) {
					return Mono.error(new MarketplaceException(409, "退款金额超过可退余额"));
				}
				String operationId = amount == unsettled.priceCents() - unsettled.refundedAmountCents()
						&& unsettled.refundedAmountCents() == 0
								? "commerce-refund:" + unsettled.id()
								: "commerce-refund:" + unsettled.id() + ":" + UUID.randomUUID();
				// 任务书 #103 C103-05：统一退款 claim——SQL 守卫（含 split_completed_at IS NULL）0 行后按当前行分类
				// 409。
				Mono<Order> request = refundClaims
						.claimConsumerRefund(unsettled.id(), operationId, amount, requestedReason)
						.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRefundRequested", updated))
								.thenReturn(updated));
				return transactions.transactional(request).flatMap(updated -> attemptRefund(updated, requestedReason));
			});
		});
	}

	public Mono<Order> openAfterSalesDispute(Caller caller, String orderId, String reason) {
		if (blank(reason))
			return Mono.error(new IllegalArgumentException("争议原因不能为空"));
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			Mono<Order> work = repository.openAfterSalesDispute(order.id(), caller.accountId(), reason.trim())
					.switchIfEmpty(Mono.defer(() -> Mono.error(new MarketplaceException(409,
							// 审查修复 01（C01-C）：分账占位（splitting）期间开案被状态守卫拒绝——
							// 资金已在途，按互斥契约返回处理中/冲突，不谎称已阻断。
							"splitting".equals(order.status()) ? "订单结算处理中，请稍后再发起售后争议" : "当前订单不可发起售后争议"))))
					.delayUntil(updated -> repository.insertAfterSalesDispute(updated.id(), caller.accountId(),
							reason.trim()))
					.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderAfterSalesDisputeOpened", updated))
							.thenReturn(updated));
			return transactions.transactional(work);
		});
	}

	public Mono<Order> resolveAfterSalesDispute(Caller caller, String orderId, DisputeResolutionCommand command) {
		if (command == null || blank(command.resolution())
				|| (!"refund".equals(command.resolution()) && !"reject".equals(command.resolution()))) {
			return Mono.error(new IllegalArgumentException("争议裁定类型不合法"));
		}
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.flatMap(order -> authorization.requireScope(caller, order.organizationId(), order.storeId(), "staff")
						.thenReturn(order))
				.flatMap(order -> {
					if (!"after_sales_disputed".equals(order.status())) {
						// A lost HTTP response may be retried after the unified completion
						// transaction has already closed the dispute. Return the durable
						// result instead of reopening or issuing another refund.
						if ("refund".equals(command.resolution())
								&& ("refunded".equals(order.status()) || "partially_refunded".equals(order.status()))) {
							return repository.findAfterSalesDispute(order.id())
									.flatMap(dispute -> "resolved".equals(dispute.status())
											? Mono.just(order)
											: Mono.error(new MarketplaceException(409, "争议不在处理中")));
						}
						return Mono.error(new MarketplaceException(409, "争议不在处理中"));
					}
					if ("reject".equals(command.resolution())) {
						return transactions.transactional(repository.rejectAfterSalesDispute(order.id())
								.flatMap(updated -> repository
										.resolveAfterSalesDispute(order.id(), "reject", 0, command.reason(), null,
												caller.accountId())
										.then(outbox
												.append(orderEvent("ConsumerOrderAfterSalesDisputeRejected", updated))
												.thenReturn(updated))));
					}
					// 任务书 #97 D97-01：售后裁定退款同受已结算闸门约束（拍板：已结算的钱不支持退款）。
					return requireNotSettled(order).flatMap(unsettled -> {
						long amount = command.amountCents() == null
								? unsettled.priceCents() - unsettled.refundedAmountCents()
								: command.amountCents();
						if (amount <= 0 || amount > unsettled.priceCents() - unsettled.refundedAmountCents()) {
							return Mono.error(new MarketplaceException(409, "裁定退款金额超过可退余额"));
						}
						String operationId = "commerce-dispute-refund:" + unsettled.id() + ":" + UUID.randomUUID();
						String resolutionReason = normalizedRefundReason(command.reason(), "after_sales_refund");
						return transactions
								.transactional(refundClaims
										.claimDisputeRefund(unsettled.id(), operationId, amount, resolutionReason)
										.flatMap(updated -> repository
												.recordAfterSalesRefundIntent(updated.id(), operationId, amount,
														resolutionReason, caller.accountId())
												.flatMap(intentRecorded -> intentRecorded
														? outbox.append(orderEvent(
																"ConsumerOrderDisputeRefundRequested", updated))
																.thenReturn(updated)
														: Mono.error(new MarketplaceException(409, "售后记录已变化")))))
								.flatMap(updated -> attemptRefund(updated, resolutionReason))
								.flatMap(updated -> "refund_pending".equals(updated.status())
										? Mono.error(new MarketplaceException(409, "退款尚未完成，争议保持处理中"))
										: Mono.just(updated));
					});
				});
	}

	/**
	 * Dispute detail is visible to the consumer who opened it or to the managing
	 * store staff (mirrors resolve).
	 */
	public Mono<AfterSalesDispute> afterSalesDispute(Caller caller, String orderId) {
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.flatMap(order -> caller.accountId().equals(order.consumerAccountId())
						? Mono.just(order)
						: authorization.requireScope(caller, order.organizationId(), order.storeId(), "staff")
								.thenReturn(order))
				.flatMap(order -> repository.findAfterSalesDispute(order.id()))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "该订单暂无售后争议")));
	}

	Mono<Order> attemptRefund(Order order, String reason) {
		if (!"refund_pending".equals(order.status()))
			return Mono.just(order);
		String effectiveReason = normalizedRefundReason(order.refundReason(),
				normalizedRefundReason(reason, "consumer_request"));
		return finance.refund(order, effectiveReason)
				.then(transactions.transactional(completeRefund(order, effectiveReason)))
				.onErrorResume(error -> repository.recordError(order.id(), "refund_pending", error.getMessage())
						.then(repository.findOrder(order.id())));
	}

	private Mono<Order> completeRefund(Order pending, String reason) {
		String operationId = pending.refundOperationId();
		long amount = pending.refundRequestedAmountCents() == null ? 0L : pending.refundRequestedAmountCents();
		return repository.markRefunded(pending.id(), operationId).flatMap(updated -> {
			Mono<Void> refundFact = repository.insertRefundFact(updated.id(), operationId, amount,
					reason == null ? "consumer_request" : reason);
			Mono<Void> dispute = repository.resolveAfterSalesRefund(updated.id(), operationId,
					reason == null ? "consumer_request" : reason);
			Mono<Void> replenish = "refunded".equals(updated.status()) && pending.redeemedAt() == null
					? repository.replenishInventory(updated.packageVersionId(), updated.inventorySlotId())
					: Mono.empty();
			return refundFact.then(dispute).then(replenish)
					.then(outbox.append(orderEvent("ConsumerOrderRefunded", updated))).thenReturn(updated);
		}).switchIfEmpty(repository.findOrder(pending.id())
				.flatMap(current -> "refund_pending".equals(current.status())
						? Mono.just(current)
						: repository.resolveAfterSalesRefund(current.id(), operationId, reason).thenReturn(current)));
	}

	/**
	 * 取消后补偿退款（R01 不变量 1：成功支付的订单最终必须是有效已支付订单，或进入<b>已完成退款的取消 结果</b>）。幂等键
	 * {@code commerce-cancel-compensation:<orderId>}；成功后订单保持 cancelled 终态、
	 * refunded_amount=price、退款时间与机器可读原因落行。
	 */
	Mono<Order> attemptCancelCompensation(String orderId) {
		return fundOperations.findByOrderAndType(orderId, CommerceFundOperationRepository.TYPE_CANCEL_COMPENSATION)
				.flatMap(operation -> fundOperations.claim(operation.operationId(), fundLeaseOwner(), FUND_LEASE,
						"cancelled"))
				.flatMap(this::driveCancelCompensation)
				// 未登记补偿 = 正常取消（支付从未发出或从未捕获），无事可做。
				.switchIfEmpty(repository.findOrder(orderId));
	}

	Mono<Order> driveCancelCompensation(FundOperation operation) {
		if (!"in_flight".equals(operation.status()) && !"failed".equals(operation.status())) {
			return repository.findOrder(operation.orderId());
		}
		return repository.findOrder(operation.orderId()).flatMap(fresh -> {
			if (!"cancelled".equals(fresh.status())) {
				// cancelled 是终态，理论上不可达；对账待办兜底，不静默丢弃。
				return fundOperations.fail(operation, true, "order_left_cancelled:" + fresh.status())
						.then(Mono.just(fresh));
			}
			Mono<Order> preparedOrder = fresh.refundOperationId() != null
					? Mono.just(fresh)
					: repository.prepareCancelCompensation(fresh.id(), operation.operationId())
							.then(repository.findOrder(fresh.id()));
			return preparedOrder.flatMap(prepared -> finance.refund(prepared, "payment_cancel_compensation"))
					.then(transactions.transactional(repository.markCancelCompensated(fresh.id())
							.flatMap(compensated -> repository
									.insertRefundFact(compensated.id(), operation.operationId(),
											operation.amountCents(), "payment_cancel_compensation")
									.then(fundOperations.succeed(operation.operationId(), null))
									.then(outbox.append(orderEvent("ConsumerOrderPaymentCompensated", compensated)))
									.thenReturn(compensated))
							.switchIfEmpty(Mono.defer(() -> fundOperations.succeed(operation.operationId(), null)
									.then(repository.findOrder(fresh.id()))))))
					.onErrorResume(error -> fundOperations.fail(operation, definitive(error), error.getMessage())
							.then(repository.findOrder(fresh.id())));
		});
	}

	/**
	 * 任务书 #97 D97-01：已结算退款闸门（拍板：已结算的钱不支持退款，不建应收追偿）。结算事实 =
	 * {@code split_completed_at} 已落账（订单佣金分账完成），不以钱包余额或订单状态推断；买家退款、
	 * 售后争议裁定退款、管理端资金动作（归因纠错）三路径共用。
	 */
	static Mono<Order> requireNotSettled(Order order) {
		return order.splitCompletedAt() == null
				? Mono.just(order)
				: Mono.error(new MarketplaceException(409, "订单佣金已结算，不支持退款；售后申请须在售后窗口内提出", SETTLED_NO_REFUND));
	}

	private static String normalizedRefundReason(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	/** 与 facade 同款的本人订单读取（搬移方法体引用沿用原名）。 */
	private Mono<Order> findConsumerOrder(Caller caller, String orderId) {
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.filter(order -> caller.accountId().equals(order.consumerAccountId()))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")));
	}

	private String fundLeaseOwner() {
		return recoveryOwner + ":" + UUID.randomUUID();
	}
}
