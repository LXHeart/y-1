package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Marketplace-owned package, inventory, consumer order, redemption and review
 * lifecycle.
 */
@Component
public class CommerceService {

	private final CommerceRepository repository;
	private final TaskResourceAuthorization authorization;
	private final RedeemCodeCodec codes;
	private final FinanceCommerceClient finance;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final long paymentTimeoutSeconds;

	public CommerceService(CommerceRepository repository, TaskResourceAuthorization authorization,
			RedeemCodeCodec codes, FinanceCommerceClient finance, OutboxRepository outbox,
			TransactionalOperator transactions,
			@org.springframework.beans.factory.annotation.Value("${marketplace.commerce.payment-timeout-seconds:900}") long paymentTimeoutSeconds) {
		this.repository = repository;
		this.authorization = authorization;
		this.codes = codes;
		this.finance = finance;
		this.outbox = outbox;
		this.transactions = transactions;
		this.paymentTimeoutSeconds = Math.max(paymentTimeoutSeconds, 1);
	}

	public Mono<OfferDetail> createOffer(Caller caller, OfferCommand command) {
		CommerceRepository.OfferInput input = validateOffer(command);
		return authorization.requireScope(caller, command.organizationId(), command.storeId(), "manager")
				.flatMap(scope -> {
					String packageId = UUID.randomUUID().toString();
					String versionId = UUID.randomUUID().toString();
					Mono<OfferDetail> work = repository
							.insertOffer(packageId, caller.accountId(), scope.organizationId(), scope.storeId(),
									blankToNull(command.taskId()))
							.then(repository.insertVersion(versionId, packageId, 1, input, caller.accountId()))
							.then(repository.insertInventory(versionId, input.totalStock()))
							.then(repository.insertInventorySlots(versionId, input.inventorySlots()))
							.then(outbox.append(event("CommercePackageCreated", "CommercePackage", packageId, Map.of(
									"packageId", packageId, "organizationId", scope.organizationId(), "version", 1))))
							.then(repository.findDetail(packageId));
					return transactions.transactional(work);
				});
	}

	public Mono<OfferDetail> reviseOffer(Caller caller, String packageId, OfferCommand command) {
		CommerceRepository.OfferInput input = validateOffer(command);
		return requireManagedOffer(caller, packageId).flatMap(current -> {
			int nextVersion = current.offer().currentVersion() + 1;
			String versionId = UUID.randomUUID().toString();
			Mono<OfferDetail> work = repository
					.insertVersion(versionId, packageId, nextVersion, input, caller.accountId())
					.then(repository.insertInventory(versionId, input.totalStock()))
					.then(repository.insertInventorySlots(versionId, input.inventorySlots()))
					.then(repository.setCurrentVersion(packageId, current.offer().currentVersion(), nextVersion)
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "套餐版本已变化，请刷新后重试"))))
					.then(outbox.append(event("CommercePackageRevised", "CommercePackage", packageId,
							Map.of("packageId", packageId, "version", nextVersion))))
					.then(repository.findDetail(packageId));
			return transactions.transactional(work);
		});
	}

	public Mono<OfferDetail> publishOffer(Caller caller, String packageId) {
		return requireManagedOffer(caller, packageId).flatMap(detail -> {
			Instant now = Instant.now();
			if (detail.version().fixedRedeemDeadline() != null && !detail.version().fixedRedeemDeadline().isAfter(now)
					&& detail.version().validDaysAfterPurchase() == null) {
				return Mono.error(new MarketplaceException(409, "核销截止时间已过，不能上架"));
			}
			return transactions.transactional(repository.publish(packageId)
					.then(outbox.append(event("CommercePackagePublished", "CommercePackage", packageId,
							Map.of("packageId", packageId, "version", detail.version().version()))))
					.then(repository.findDetail(packageId)));
		});
	}

	public Mono<OfferDetail> offSaleOffer(Caller caller, String packageId) {
		return requireManagedOffer(caller, packageId).flatMap(detail -> transactions.transactional(repository
				.offSale(packageId)
				.then(outbox.append(
						event("CommercePackageOffSale", "CommercePackage", packageId, Map.of("packageId", packageId))))
				.then(repository.findDetail(packageId))));
	}

	public Mono<OfferDetail> publicOffer(String packageId) {
		return repository.findDetail(packageId).switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在")))
				.filter(detail -> "published".equals(detail.offer().status()))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在或已下架")));
	}

	public Flux<OfferDetail> listManagedOffers(Caller caller, String organizationId, String storeId) {
		return authorization.requireScope(caller, organizationId, storeId, "staff")
				.flatMapMany(scope -> repository.listOffers(scope.organizationId(), scope.storeId()));
	}

	public Mono<Order> createOrder(Caller caller, CreateOrderCommand command) {
		return publicOffer(command.packageId()).flatMap(detail -> {
			Instant now = Instant.now();
			Instant deadline = redeemDeadline(detail, now);
			if (!deadline.isAfter(now)) {
				return Mono.error(new MarketplaceException(409, "套餐已过有效期"));
			}
			String orderId = UUID.randomUUID().toString();
			java.util.List<AllocationCommand> requestedAllocations = command.allocations() == null
					? java.util.List.of()
					: command.allocations();
			String recommender = requestedAllocations.isEmpty()
					? blankToNull(command.recommenderAccountId())
					: blankToNull(requestedAllocations.get(0).recommenderAccountId());
			long platform = basisPoints(detail.version().priceCents(), detail.version().platformFeeBps());
			int requestedShareBps = requestedAllocations.isEmpty()
					? detail.version().recommenderShareBps()
					: requestedAllocations.stream().mapToInt(AllocationCommand::shareBps).sum();
			if (requestedShareBps < 0 || requestedShareBps + detail.version().platformFeeBps() > 10000
					|| requestedAllocations.stream().anyMatch(a -> blank(a.recommenderAccountId()) || a.shareBps() <= 0)
					|| requestedAllocations.stream().map(AllocationCommand::recommenderAccountId).distinct()
							.count() != requestedAllocations.size()) {
				return Mono.error(new MarketplaceException(409, "推荐官分配比例不合法"));
			}
			java.util.List<CommerceRepository.AttributionAllocationInput> allocations = recommender == null
					? new java.util.ArrayList<>()
					: requestedAllocations.isEmpty()
							? new java.util.ArrayList<>(java.util.List.of(
									new CommerceRepository.AttributionAllocationInput(recommender, requestedShareBps,
											basisPoints(detail.version().priceCents(), requestedShareBps))))
							: new java.util.ArrayList<>(requestedAllocations.stream()
									.map(a -> new CommerceRepository.AttributionAllocationInput(
											a.recommenderAccountId(), a.shareBps(),
											basisPoints(detail.version().priceCents(), a.shareBps())))
									.toList());
			long recommenderAmount = allocations.stream()
					.mapToLong(CommerceRepository.AttributionAllocationInput::amountCents).sum();
			if (!allocations.isEmpty()) {
				long residual = basisPoints(detail.version().priceCents(), requestedShareBps) - recommenderAmount;
				if (residual != 0) {
					CommerceRepository.AttributionAllocationInput last = allocations.remove(allocations.size() - 1);
					allocations.add(new CommerceRepository.AttributionAllocationInput(last.recommenderAccountId(),
							last.shareBps(), last.amountCents() + residual));
					recommenderAmount += residual;
				}
			}
			long merchant = detail.version().priceCents() - platform - recommenderAmount;
			int recommenderBps = recommender == null ? 0 : requestedShareBps;
			int merchantBps = 10_000 - detail.version().platformFeeBps() - recommenderBps;
			CommerceRepository.NewOrder newOrder = new CommerceRepository.NewOrder(orderId, caller.accountId(),
					detail.offer().organizationId(), detail.offer().storeId(), detail.offer().taskId(),
					detail.offer().id(), detail.version().id(), detail.version().version(), detail.version().title(),
					recommender, detail.version().priceCents(), recommenderBps, detail.version().platformFeeBps(),
					merchantBps, recommenderAmount, platform, merchant, detail.version().policyVersion(),
					codes.hash(codes.codeForOrder(orderId)), deadline,
					// 任务书 #41（D1）：支付截止随下单快照落行——之后改配置不影响存量订单。
					now.plusSeconds(paymentTimeoutSeconds), "commerce-payment:" + orderId,
					blankToNull(command.inventorySlotId()));
			Mono<Order> create = repository.reserveInventory(detail.version().id(), command.inventorySlotId())
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "套餐已售罄")))
					.then(repository.insertOrder(newOrder))
					.flatMap(order -> repository.replaceAttributionAllocations(order.id(), allocations, "order_create")
							.thenReturn(order))
					.flatMap(order -> outbox.append(orderEvent("ConsumerOrderCreated", order)).thenReturn(order));
			return transactions.transactional(create).flatMap(this::attemptPayment);
		});
	}

	public Mono<Order> findConsumerOrder(Caller caller, String orderId) {
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.filter(order -> caller.accountId().equals(order.consumerAccountId()))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")));
	}

	/**
	 * 消费者主动取消未支付订单（任务书 #41 尾巴）：claim 条件 UPDATE 单边胜出（与支付/超时关单同款 状态机守卫），同事务释放库存 + 发
	 * {@code ConsumerOrderCancelled} 同族事件（D9）。 幂等/竞态：claim 0 行（支付已先赢/已关单）→
	 * 409；非本人/不存在 → 404。
	 */
	public Mono<Order> cancelByConsumer(Caller caller, String orderId) {
		return findConsumerOrder(caller, orderId).flatMap(order -> transactions.transactional(repository
				.claimConsumerCancelled(order.id(), caller.accountId())
				.switchIfEmpty(Mono.error(new MarketplaceException(409, "仅待支付订单可取消")))
				.flatMap(cancelled -> repository
						.releaseInventory(cancelled.packageVersionId(), cancelled.inventorySlotId())
						.then(outbox.append(orderEvent("ConsumerOrderCancelled", cancelled))).thenReturn(cancelled))));
	}

	public Flux<Order> listConsumerOrders(Caller caller, int limit) {
		return repository.listConsumerOrders(caller.accountId(), limit);
	}

	public Mono<Order> rebindAttribution(Caller caller, String orderId, AttributionCommand command) {
		if (command == null || (command.allocations() == null || command.allocations().isEmpty())
				&& (blank(command.recommenderAccountId()) || command.recommenderShareBps() == null
						|| command.recommenderShareBps() < 0 || command.recommenderShareBps() > 10000)) {
			return Mono.error(new IllegalArgumentException("推荐官归因参数不合法"));
		}
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			java.util.List<AllocationCommand> allocations = command.allocations() == null
					|| command.allocations().isEmpty()
							? java.util.List.of(new AllocationCommand(command.recommenderAccountId(),
									command.recommenderShareBps()))
							: command.allocations();
			int totalBps = allocations.stream().mapToInt(AllocationCommand::shareBps).sum();
			if (allocations.stream().anyMatch(a -> blank(a.recommenderAccountId()) || a.shareBps() <= 0) || allocations
					.stream().map(AllocationCommand::recommenderAccountId).distinct().count() != allocations.size()
					|| totalBps + order.platformFeeBps() > 10000) {
				return Mono.error(new MarketplaceException(409, "推荐官分配比例超过可分配范围"));
			}
			if (!"paid".equals(order.status()) && !"partially_refunded".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "已核销或已结束订单不能换绑归因"));
			}
			java.util.List<CommerceRepository.AttributionAllocationInput> allocationInputs = new java.util.ArrayList<>(
					allocations.stream()
							.map(a -> new CommerceRepository.AttributionAllocationInput(a.recommenderAccountId(),
									a.shareBps(), basisPoints(order.priceCents(), a.shareBps())))
							.toList());
			long allocated = allocationInputs.stream()
					.mapToLong(CommerceRepository.AttributionAllocationInput::amountCents).sum();
			long target = basisPoints(order.priceCents(), totalBps);
			if (!allocationInputs.isEmpty() && allocated != target) {
				CommerceRepository.AttributionAllocationInput last = allocationInputs
						.remove(allocationInputs.size() - 1);
				allocationInputs.add(new CommerceRepository.AttributionAllocationInput(last.recommenderAccountId(),
						last.shareBps(), last.amountCents() + target - allocated));
			}
			Mono<Order> work = repository
					.rebindAttribution(order.id(), allocations.get(0).recommenderAccountId(), totalBps)
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
					.delayUntil(updated -> repository
							.insertAttribution(updated.id(), updated.recommenderAccountId(),
									updated.recommenderShareBps(),
									blankToNull(command.source()) == null ? "manual" : command.source(),
									blankToNull(command.reason()), caller.accountId())
							.then(repository.replaceAttributionAllocations(updated.id(), allocationInputs,
									blank(command.source()) ? "manual" : command.source())))
					.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderAttributionRebound", updated))
							.thenReturn(updated));
			return transactions.transactional(work);
		});
	}

	public Flux<CommerceRepository.AttributionAllocation> attributionAllocations(Caller caller, String orderId) {
		return findConsumerOrder(caller, orderId)
				.flatMapMany(order -> repository.findAttributionAllocations(order.id()));
	}

	public Mono<Order> openAfterSalesDispute(Caller caller, String orderId, String reason) {
		if (blank(reason))
			return Mono.error(new IllegalArgumentException("争议原因不能为空"));
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			Mono<Order> work = repository.openAfterSalesDispute(order.id(), caller.accountId(), reason.trim())
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "当前订单不可发起售后争议")))
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
						return Mono.error(new MarketplaceException(409, "争议不在处理中"));
					}
					if ("reject".equals(command.resolution())) {
						return transactions.transactional(repository.rejectAfterSalesDispute(order.id())
								.flatMap(updated -> repository
										.resolveAfterSalesDispute(order.id(), "reject", 0, command.reason(), null)
										.then(outbox
												.append(orderEvent("ConsumerOrderAfterSalesDisputeRejected", updated))
												.thenReturn(updated))));
					}
					long amount = command.amountCents() == null
							? order.priceCents() - order.refundedAmountCents()
							: command.amountCents();
					if (amount <= 0 || amount > order.priceCents() - order.refundedAmountCents()) {
						return Mono.error(new MarketplaceException(409, "裁定退款金额超过可退余额"));
					}
					String operationId = "commerce-dispute-refund:" + order.id() + ":" + UUID.randomUUID();
					return transactions
							.transactional(
									repository.requestDisputeRefund(order.id(), operationId, amount, command.reason())
											.switchIfEmpty(Mono.error(new MarketplaceException(409, "争议状态已变化")))
											.flatMap(updated -> outbox
													.append(orderEvent("ConsumerOrderDisputeRefundRequested", updated))
													.thenReturn(updated)))
							.flatMap(updated -> attemptRefund(updated, command.reason()))
							.flatMap(updated -> "refund_pending".equals(updated.status())
									? Mono.error(new MarketplaceException(409, "退款尚未完成，争议保持处理中"))
									: repository.resolveAfterSalesDispute(order.id(), "refund", amount,
											command.reason(), operationId).thenReturn(updated));
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

	public Mono<Order> requestRefund(Caller caller, String orderId, Long requestedAmountCents, String reason) {
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			if ("refund_pending".equals(order.status()))
				return attemptRefund(order, reason);
			if (!"paid".equals(order.status()) && !"partially_refunded".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "当前订单状态不可退款"));
			}
			long amount = requestedAmountCents == null
					? order.priceCents() - order.refundedAmountCents()
					: requestedAmountCents;
			if (amount <= 0 || amount > order.priceCents() - order.refundedAmountCents()) {
				return Mono.error(new MarketplaceException(409, "退款金额超过可退余额"));
			}
			String operationId = amount == order.priceCents() - order.refundedAmountCents()
					&& order.refundedAmountCents() == 0
							? "commerce-refund:" + order.id()
							: "commerce-refund:" + order.id() + ":" + UUID.randomUUID();
			Mono<Order> request = repository.requestRefund(order.id(), operationId, amount, reason)
					.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化"))).flatMap(updated -> outbox
							.append(orderEvent("ConsumerOrderRefundRequested", updated)).thenReturn(updated));
			return transactions.transactional(request).flatMap(updated -> attemptRefund(updated, reason));
		});
	}

	public Mono<Order> redeem(Caller caller, String code) {
		String hash = codes.hash(code);
		return repository.findOrderByCodeHash(hash).switchIfEmpty(Mono.error(new MarketplaceException(404, "核销码无效")))
				.flatMap(order -> authorization.requireScope(caller, order.organizationId(), order.storeId(), "staff")
						.thenReturn(order))
				.flatMap(order -> {
					if ("redeemed".equals(order.status())) {
						return Mono.error(new MarketplaceException(409, "该核销码已使用"));
					}
					if ("redeeming".equals(order.status()))
						return attemptSplit(order);
					if (!"paid".equals(order.status())) {
						return Mono.error(new MarketplaceException(409, "订单当前不可核销"));
					}
					if (!order.redeemDeadline().isAfter(Instant.now())) {
						return Mono.error(new MarketplaceException(409, "核销码已过期，订单将自动退款"));
					}
					Mono<Order> mark = repository.markRedeeming(order.id(), "commerce-split:" + order.id())
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
							.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRedemptionStarted", updated))
									.thenReturn(updated));
					return transactions.transactional(mark).flatMap(this::attemptSplit);
				});
	}

	public Flux<Order> listMerchantOrders(Caller caller, String organizationId, String storeId, int limit) {
		return authorization.requireScope(caller, organizationId, storeId, "staff")
				.flatMapMany(scope -> repository.listMerchantOrders(scope.organizationId(), scope.storeId(), limit));
	}

	public Flux<Order> exportMerchantOrders(Caller caller, String organizationId, String storeId, String status,
			Instant from, Instant to) {
		return authorization.requireScope(caller, organizationId, storeId, "staff").flatMapMany(scope -> repository
				.exportMerchantOrders(scope.organizationId(), scope.storeId(), status, from, to, 10_000));
	}

	public Flux<Order> listAdminOrders(String status, int limit) {
		return repository.listAdminOrders(status, limit);
	}

	public Mono<Review> review(Caller caller, String orderId, ReviewCommand command) {
		if (command.rating() < 1 || command.rating() > 5) {
			return Mono.error(new IllegalArgumentException("评分必须在 1 到 5 之间"));
		}
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			if (!"redeemed".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "仅已核销订单可评价"));
			}
			Mono<Review> work = repository
					.insertReview(order.id(), caller.accountId(), command.rating(),
							blankToNull(command.comment()))
					.flatMap(review -> outbox.append(event("ConsumerOrderReviewed", "ConsumerOrder", order.id(),
							Map.of("orderId", order.id(), "consumerAccountId", caller.accountId(), "rating",
									review.rating())))
							.thenReturn(review))
					.switchIfEmpty(repository.findReview(order.id()));
			return transactions.transactional(work);
		});
	}

	Mono<Order> attemptPayment(Order order) {
		if (!"pending_payment".equals(order.status()))
			return Mono.just(order);
		return finance.pay(order)
				.flatMap(providerRef -> transactions.transactional(repository.markPaid(order.id(), providerRef)
						.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderPaid", updated)).thenReturn(updated))
						.switchIfEmpty(repository.findOrder(order.id()))))
				.onErrorResume(error -> repository.recordError(order.id(), "pending_payment", error.getMessage())
						.then(repository.findOrder(order.id())));
	}

	Mono<Order> attemptRefund(Order order, String reason) {
		if (!"refund_pending".equals(order.status()))
			return Mono.just(order);
		return finance.refund(order, reason == null ? "consumer_request" : reason)
				.then(transactions.transactional(repository.markRefunded(order.id()).flatMap(updated -> {
					Mono<Void> replenish = "refunded".equals(updated.status()) && order.redeemedAt() == null
							? repository.replenishInventory(updated.packageVersionId(), updated.inventorySlotId())
							: Mono.empty();
					return replenish.then(outbox.append(orderEvent("ConsumerOrderRefunded", updated)))
							.thenReturn(updated);
				}).switchIfEmpty(repository.findOrder(order.id()))))
				.onErrorResume(error -> repository.recordError(order.id(), "refund_pending", error.getMessage())
						.then(repository.findOrder(order.id())));
	}

	Mono<Order> attemptSplit(Order order) {
		if (!"redeeming".equals(order.status()))
			return Mono.just(order);
		return repository.findAttributionAllocations(order.id()).collectList()
				.flatMap(allocations -> finance.split(order, allocations))
				.then(transactions.transactional(repository.markRedeemed(order.id())
						.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRedeemed", updated))
								.thenReturn(updated))
						.switchIfEmpty(repository.findOrder(order.id()))))
				.onErrorResume(error -> repository.recordError(order.id(), "redeeming", error.getMessage())
						.then(repository.findOrder(order.id())));
	}

	Flux<Order> claimExpired(int limit) {
		return repository.claimExpired(limit);
	}
	Flux<Order> pendingDispatch(int limit) {
		return repository.pendingDispatch(limit);
	}

	/**
	 * 任务书 #41（D3）：支付超时关单——claim（pending_payment→cancelled，DB 守卫单边胜出）成功后，
	 * **同一事务**内对称释放下单时占用的库存（带 slot 释放 slot 级，无 slot 释放包级）， 并补发
	 * {@code ConsumerOrderCancelled} 同族事件（D9）。
	 *
	 * <p>
	 * 幂等：claim 条件 UPDATE 0 行（并发副本/双轮重复/支付已先赢）自然不进链； release
	 * 的封顶守卫吸收任何上游重复释放。释放抛错则整个事务回滚（订单留在 pending_payment，下一轮重新 claim 重试）。
	 */
	Flux<Order> cancelExpired(int limit) {
		return transactions.transactional(repository.claimPaymentExpired(limit)
				.flatMap(order -> repository.releaseInventory(order.packageVersionId(), order.inventorySlotId())
						.then(outbox.append(orderEvent("ConsumerOrderCancelled", order))).thenReturn(order)));
	}

	public String redeemCode(Order order) {
		return switch (order.status()) {
			case "paid", "redeeming" -> codes.codeForOrder(order.id());
			default -> null;
		};
	}

	private Mono<OfferDetail> requireManagedOffer(Caller caller, String packageId) {
		return repository.findDetail(packageId).switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在")))
				.flatMap(detail -> authorization
						.requireScope(caller, detail.offer().organizationId(), detail.offer().storeId(), "manager")
						.thenReturn(detail));
	}

	private static CommerceRepository.OfferInput validateOffer(OfferCommand command) {
		if (command == null || blank(command.organizationId()) || blank(command.title()) || command.priceCents() <= 0
				|| command.totalStock() < 0) {
			throw new IllegalArgumentException("组织、套餐名称、价格和库存不能为空");
		}
		int recommender = command.recommenderShareBps();
		int platform = command.platformFeeBps();
		if (recommender < 0 || platform < 0 || recommender + platform > 10_000) {
			throw new IllegalArgumentException("分账比例不合法");
		}
		if (command.fixedRedeemDeadline() == null && command.validDaysAfterPurchase() == null) {
			throw new IllegalArgumentException("固定截止日和购买后有效天数至少填写一项");
		}
		if (command.validDaysAfterPurchase() != null && command.validDaysAfterPurchase() <= 0) {
			throw new IllegalArgumentException("购买后有效天数必须大于 0");
		}
		return new CommerceRepository.OfferInput(command.title().trim(), blankToNull(command.description()),
				command.priceCents(), command.totalStock(), command.fixedRedeemDeadline(),
				command.validDaysAfterPurchase(), recommender, platform, 10_000 - recommender - platform,
				blank(command.policyVersion()) ? "commerce-v1" : command.policyVersion().trim(),
				command.inventorySlots() == null ? java.util.List.of() : command.inventorySlots());
	}

	private static Instant redeemDeadline(OfferDetail detail, Instant purchasedAt) {
		Instant fixed = detail.version().fixedRedeemDeadline();
		Instant rolling = detail.version().validDaysAfterPurchase() == null
				? null
				: purchasedAt.plus(detail.version().validDaysAfterPurchase(), ChronoUnit.DAYS);
		if (fixed == null)
			return rolling;
		if (rolling == null)
			return fixed;
		return fixed.isBefore(rolling) ? fixed : rolling;
	}

	private static long basisPoints(long amount, int bps) {
		return Math.addExact(Math.multiplyExact(amount / 10_000, bps),
				Math.multiplyExact(amount % 10_000, bps) / 10_000);
	}

	private static EventEnvelope orderEvent(String type, Order order) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("orderId", order.id());
		payload.put("consumerAccountId", order.consumerAccountId());
		payload.put("organizationId", order.organizationId());
		if (order.storeId() != null)
			payload.put("storeId", order.storeId());
		if (order.recommenderAccountId() != null)
			payload.put("recommenderAccountId", order.recommenderAccountId());
		payload.put("packageId", order.packageId());
		payload.put("packageVersion", order.packageVersion());
		payload.put("priceCents", order.priceCents());
		payload.put("status", order.status());
		return event(type, "ConsumerOrder", order.id(), payload);
	}

	private static EventEnvelope event(String type, String aggregateType, String aggregateId,
			Map<String, Object> payload) {
		return new EventEnvelope(UUID.randomUUID().toString(), type, aggregateType, aggregateId, 1, Instant.now(),
				aggregateId, payload);
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
	private static String blankToNull(String value) {
		return blank(value) ? null : value.trim();
	}

	public record OfferCommand(String organizationId, String storeId, String taskId, String title, String description,
			long priceCents, int totalStock, Instant fixedRedeemDeadline, Integer validDaysAfterPurchase,
			int recommenderShareBps, int platformFeeBps, String policyVersion,
			java.util.List<CommerceRepository.InventorySlotInput> inventorySlots) {
	}
	public record CreateOrderCommand(String packageId, String recommenderAccountId, String inventorySlotId,
			java.util.List<AllocationCommand> allocations) {
	}

	public record AllocationCommand(String recommenderAccountId, int shareBps) {
	}

	/**
	 * Optional fields are boxed: Jackson 3 fails requests on absent primitives, and
	 * the allocations path legitimately omits {@code recommenderShareBps}.
	 */
	public record AttributionCommand(String recommenderAccountId, Integer recommenderShareBps, String source,
			String reason, java.util.List<AllocationCommand> allocations) {
	}

	public record DisputeResolutionCommand(String resolution, Long amountCents, String reason) {
	}
	public record ReviewCommand(int rating, String comment) {
	}
}
