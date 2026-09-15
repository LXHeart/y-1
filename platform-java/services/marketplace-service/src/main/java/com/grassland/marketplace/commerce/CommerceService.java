package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceFundOperationRepository.FundOperation;
import com.grassland.marketplace.commerce.CommerceModels.AfterSalesDispute;
import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskFullAutoCloser;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.time.Duration;
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
 *
 * <p>
 * 任务书 #103 C103-21：退款/售后与核销/分账已按职责搬移至 {@link ConsumerRefundService} 与
 * {@link ConsumerRedemptionService}，本类保留套餐/下单/支付/归因/ 评价与 dispatcher
 * 扫描，搬移方法以委托保持既有 API（行为零变更）。
 */
@Component
public class CommerceService {

	static final Duration FUND_LEASE = Duration.ofSeconds(60);

	// ---------- 任务书 #103 C103-21：退款/售后与核销/分账已按职责搬移，facade 委托保持既有 API ----------

	public Mono<Order> requestRefund(Caller caller, String orderId, Long requestedAmountCents, String reason) {
		return refunds.requestRefund(caller, orderId, requestedAmountCents, reason);
	}

	public Mono<Order> openAfterSalesDispute(Caller caller, String orderId, String reason) {
		return refunds.openAfterSalesDispute(caller, orderId, reason);
	}

	public Mono<Order> resolveAfterSalesDispute(Caller caller, String orderId, DisputeResolutionCommand command) {
		return refunds.resolveAfterSalesDispute(caller, orderId, command);
	}

	public Mono<AfterSalesDispute> afterSalesDispute(Caller caller, String orderId) {
		return refunds.afterSalesDispute(caller, orderId);
	}

	Mono<Order> attemptRefund(Order order, String reason) {
		return refunds.attemptRefund(order, reason);
	}

	Mono<Order> attemptCancelCompensation(String orderId) {
		return refunds.attemptCancelCompensation(orderId);
	}

	public Mono<Order> redeem(Caller caller, String code) {
		return redemptions.redeem(caller, code);
	}

	public String redeemCode(Order order) {
		return redemptions.redeemCode(order);
	}

	public OrderRedemptionPolicy.Result redemptionEligibility(Order order) {
		return redemptions.redemptionEligibility(order);
	}

	Mono<Order> attemptSplit(Order snapshot) {
		return redemptions.attemptSplit(snapshot);
	}

	private final CommerceRepository repository;
	private final TaskResourceAuthorization authorization;
	private final TaskRepository tasks;
	private final ReferralLinkService referralLinks;
	private final RedeemCodeCodec codes;
	private final FinanceCommerceClient finance;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final CommerceFundOperationRepository fundOperations;
	private final ConsumerRefundService refunds;
	private final ConsumerRedemptionService redemptions;
	private final String recoveryOwner;
	private final long paymentTimeoutSeconds;

	public CommerceService(CommerceRepository repository, TaskResourceAuthorization authorization, TaskRepository tasks,
			ReferralLinkService referralLinks, RedeemCodeCodec codes, FinanceCommerceClient finance,
			OutboxRepository outbox, TransactionalOperator transactions, CommerceFundOperationRepository fundOperations,
			ConsumerRefundService refunds, ConsumerRedemptionService redemptions,
			@org.springframework.beans.factory.annotation.Value("${marketplace.commerce.payment-timeout-seconds:900}") long paymentTimeoutSeconds) {
		this.repository = repository;
		this.authorization = authorization;
		this.tasks = tasks;
		this.referralLinks = referralLinks;
		this.codes = codes;
		this.finance = finance;
		this.outbox = outbox;
		this.transactions = transactions;
		this.fundOperations = fundOperations;
		this.refunds = refunds;
		this.redemptions = redemptions;
		// 每轮领取再附加随机令牌，阻止本进程或其他副本上一轮的迟到失败覆盖新租约。
		this.recoveryOwner = "commerce-recovery-" + UUID.randomUUID();
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
				// 任务书 #75 D1 派生 2：下架联动——进行中推广任务转手动截止语义态（等价商家手动 close，
				// 不 409 打断商家下架）；已下单未核销订单金额快照在单上，分账不受影响（派生 3）。
				.then(closeLinkedPromotionTask(packageId))
				.then(outbox.append(
						event("CommercePackageOffSale", "CommercePackage", packageId, Map.of("packageId", packageId))))
				.then(repository.findDetail(packageId))));
	}

	/**
	 * 下架联动闭包：关闭进行中推广任务 + TaskClosed 事件（closeReason=package_off_sale）+ 清空 task_id
	 * 回填。
	 */
	private Mono<Void> closeLinkedPromotionTask(String packageId) {
		return tasks.closeActivePromotionByPackage(packageId)
				.flatMap(closed -> outbox.append(TaskFullAutoCloser.taskClosedEnvelope(closed, "package_off_sale"))
						.then(repository.unlinkPromotionTaskByTask(closed.id())))
				.then();
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
			// 任务书 #75 D4/D5 + #90 C90-03：末次点击单归因——链接携带的推荐官为唯一依据，
			// 归因资格 = 该套餐进行中推广任务（招募 published/closed 且推广未结束）的 accepted 报名——
			// 满员自动关闭不终止已接受推广；未接任务/推广已结束/任务取消/参数无效 = 自然流量。
			// 任务书 #98 D98-01/D98-02：归因参数二选一——新 referralLinkId（服务端解析不透明链接，
			// 7 天 last-touch 窗口；链接级失效/过窗 422 可解释、订单可无归因另行创建）与旧
			// recommenderAccountId（兼容期行为不变）。
			String requested = blankToNull(command.recommenderAccountId());
			String rlid = blankToNull(command.referralLinkId());
			if (requested != null && rlid != null) {
				return Mono.error(new IllegalArgumentException("recommenderAccountId 与 referralLinkId 不能同时提供，请只传其一"));
			}
			Mono<OrderAttribution> decision = rlid != null
					? referralLinks.resolveForOrder(caller, detail.offer().id(), rlid)
							.map(res -> new OrderAttribution(res.recommenderAccountId() == null
									? AttributionDecision.NONE
									: new AttributionDecision(res.taskId(), res.recommenderAccountId()), res))
					: tasks.findActivePromotionTaskId(detail.offer().id())
							.flatMap(taskId -> requested == null
									? Mono.just(new AttributionDecision(taskId, null))
									: tasks.hasAcceptedPromotionApplication(detail.offer().id(), requested).map(
											eligible -> new AttributionDecision(taskId, eligible ? requested : null)))
							.defaultIfEmpty(AttributionDecision.NONE).map(found -> new OrderAttribution(found, null));
			return decision.flatMap(attribution -> {
				AttributionDecision resolved = attribution.decision();
				// 自购不计佣（D1 派生 4）：归因照落（审计可见）、推荐官份额 0 归商家，bps 快照照存（金额和 CHECK 仍成立）。
				boolean attributed = resolved.recommenderAccountId() != null;
				boolean selfPurchase = attributed && resolved.recommenderAccountId().equals(caller.accountId());
				long platform = basisPoints(detail.version().priceCents(), detail.version().platformFeeBps());
				int recommenderBps = attributed ? detail.version().recommenderShareBps() : 0;
				long recommenderAmount = 0;
				if (attributed && !selfPurchase) {
					// 佣金形态二选一（D2）：固定额直接取快照；比例按 bps 基点折算（现状）。
					recommenderAmount = detail.version().isFixedCommission()
							? detail.version().recommenderFixedCents()
							: basisPoints(detail.version().priceCents(), recommenderBps);
				}
				long merchant = detail.version().priceCents() - platform - recommenderAmount;
				int merchantBps = 10_000 - detail.version().platformFeeBps() - recommenderBps;
				CommerceRepository.NewOrder newOrder = new CommerceRepository.NewOrder(orderId, caller.accountId(),
						detail.offer().organizationId(), detail.offer().storeId(), resolved.promotionTaskId(),
						detail.offer().id(), detail.version().id(), detail.version().version(),
						detail.version().title(), resolved.recommenderAccountId(), detail.version().priceCents(),
						recommenderBps, detail.version().platformFeeBps(), merchantBps, recommenderAmount, platform,
						merchant, detail.version().policyVersion(), codes.hash(codes.codeForOrder(orderId)), deadline,
						// 任务书 #41（D1）：支付截止随下单快照落行——之后改配置不影响存量订单。
						now.plusSeconds(paymentTimeoutSeconds), "commerce-payment:" + orderId,
						blankToNull(command.inventorySlotId()));
				// 任务书 #75 D5：停写 V37 allocations——finance split 走单推荐官重载（空列表自然落到单归因路径），
				// 多推荐官行仅存量冲销路径继续可读。
				Mono<Order> create = repository.reserveInventory(detail.version().id(), command.inventorySlotId())
						.switchIfEmpty(Mono.error(new MarketplaceException(409, "套餐已售罄")))
						.then(repository.insertOrder(newOrder)).flatMap(order -> {
							// 任务书 #98 D98-02：rlid 归因随订单同事务落事实行（链接 + 触达时间 + 依据，
							// append-only 审计；解释读模型与治理台生命周期的数据源）。
							Mono<Void> referralFact = attribution.referral() != null && attributed
									? repository.insertReferralAttribution(order.id(), resolved.recommenderAccountId(),
											recommenderBps, attribution.referral().basis(), caller.accountId(),
											attribution.referral().referralLinkId(), attribution.referral().touchedAt())
									: Mono.empty();
							return referralFact.then(outbox.append(orderEvent("ConsumerOrderCreated", order)))
									.thenReturn(order);
						});
				return transactions.transactional(create).flatMap(this::attemptPayment);
			});
		});
	}

	/** 单归因裁决：下单时刻的进行中推广任务 id（订单快照用）+ 通过资格闸的推荐官（null=自然流量）。 */
	private record AttributionDecision(String promotionTaskId, String recommenderAccountId) {
		static final AttributionDecision NONE = new AttributionDecision(null, null);
	}

	/** #98：归因裁决 + rlid 解析上下文（触达时间/依据/链接 id，供归因事实行）。 */
	private record OrderAttribution(AttributionDecision decision, ReferralLinkService.ReferralResolution referral) {
	}

	public Mono<Order> findConsumerOrder(Caller caller, String orderId) {
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.filter(order -> caller.accountId().equals(order.consumerAccountId()))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")));
	}

	/**
	 * 归因解释访问裁决（任务书 #98 §5.2）：消费者本人 / 被归因推荐官 / 客服·财务·风控三端可见、 字段同一读模型；无关第三方 403。
	 */
	public Mono<Order> findOrderForAttributionExplain(Caller caller, String orderId) {
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.flatMap(order -> {
					if (caller.accountId().equals(order.consumerAccountId())
							|| caller.hasBackendRole(com.grassland.identity.assertion.BackendRole.CUSTOMER_SERVICE,
									com.grassland.identity.assertion.BackendRole.FINANCE,
									com.grassland.identity.assertion.BackendRole.RISK)) {
						return Mono.just(order);
					}
					return repository.findReferralAttribution(order.id())
							.filter(fact -> caller.accountId().equals(fact.recommenderAccountId())).map(fact -> order)
							.switchIfEmpty(Mono.error(new MarketplaceException(403, "无权查看该订单的归因解释")));
				});
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

	/**
	 * 消费者归因申诉（业务审查 2026-09-07 C01，替代原买家直接改绑）：买家只主张「实际带客的推荐官」，
	 * <b>不提交任何分成比例</b>——金额始终由订单冻结的套餐版本规则计算。提交时做与下单一致的
	 * 资格/自购前置校验（拦截明显无效申诉），终局由运营纠错通道落定。
	 */
	public Mono<CommerceModels.AttributionAppeal> submitAttributionAppeal(Caller caller, String orderId,
			AppealCommand command) {
		if (command == null || blank(command.claimedRecommenderAccountId()) || blank(command.reason())) {
			return Mono.error(new IllegalArgumentException("申诉须填写主张的推荐官与申诉说明"));
		}
		String claimed = command.claimedRecommenderAccountId().trim();
		String reason = command.reason().trim();
		if (reason.length() < 5 || reason.length() > 500) {
			return Mono.error(new IllegalArgumentException("申诉说明长度须在 5 到 500 字之间"));
		}
		if (claimed.equals(caller.accountId())) {
			return Mono.error(new MarketplaceException(409, "自购订单不产生推荐佣金，不能申诉归因给自己"));
		}
		return findConsumerOrder(caller, orderId).flatMap(order -> {
			if (!"paid".equals(order.status()) && !"partially_refunded".equals(order.status())) {
				return Mono.error(new MarketplaceException(409, "已核销或已结束订单不能申诉归因"));
			}
			return requireAttributable(order, claimed)
					.then(repository.insertAttributionAppeal(order.id(), caller.accountId(), claimed, reason)
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "该订单已有待处理的归因申诉"))))
					.flatMap(appeal -> outbox.append(orderEvent("ConsumerOrderAttributionAppealOpened", order))
							.thenReturn(appeal));
		});
	}

	/** 消费者查看本人订单最新申诉（回显处置进度）。 */
	public Mono<CommerceModels.AttributionAppeal> attributionAppeal(Caller caller, String orderId) {
		return findConsumerOrder(caller, orderId).flatMap(order -> repository.findLatestAttributionAppeal(order.id()));
	}

	/**
	 * 纠错统一资格闸：订单下单时冻结的推广任务上，目标推荐官持有 accepted 报名。 与下单同口径（下单无活跃推广任务即自然流量单，无可归因对象）。
	 */
	private Mono<Void> requireAttributable(CommerceModels.Order order, String recommenderAccountId) {
		if (order.taskId() == null) {
			return Mono.error(new MarketplaceException(409, "该订单下单时无进行中推广任务，属自然流量订单"));
		}
		return tasks.hasAcceptedApplicationOnTask(order.taskId(), recommenderAccountId).flatMap(
				eligible -> eligible ? Mono.empty() : Mono.error(new MarketplaceException(409, "该推荐官未持有此订单推广任务的接单资格")));
	}

	/**
	 * 运营归因纠错（业务审查 2026-09-07 C01）：客服/财务/风控通道专用。金额按订单冻结的
	 * {@code commerce_package_version} 规则重算（固定佣保持固定额，比例佣保持 bps），
	 * <b>请求体不携带任何金额或比例</b>；同事务落审计行 + 处置待处理申诉 + 发事件。
	 */
	public Mono<CommerceModels.Order> correctAttribution(Caller caller, String orderId, CorrectionCommand command) {
		if (command == null || blank(command.recommenderAccountId())) {
			return Mono.error(new IllegalArgumentException("纠错须指定目标推荐官"));
		}
		String target = command.recommenderAccountId().trim();
		return repository.findOrder(orderId).switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.flatMap(order -> {
					if (!"paid".equals(order.status()) && !"partially_refunded".equals(order.status())) {
						return Mono.error(new MarketplaceException(409, "已核销或已分账订单不能纠错归因"));
					}
					if (target.equals(order.consumerAccountId())) {
						return Mono.error(new MarketplaceException(409, "自购订单不产生推荐佣金，纠错被拒绝"));
					}
					// 任务书 #97 D97-01：管理端资金动作同守卫——部分退款（分账后售后退款）单不再可纠错，
					// repository 层 split_completed_at IS NULL 条件保留为并发双保险。
					return ConsumerRefundService.requireNotSettled(order).then(requireAttributable(order, target))
							.then(repository.findVersionRule(order.packageVersionId())
									.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单冻结的套餐版本缺失"))))
							.flatMap(rule -> {
								RecomputedSplit split = recomputeSplit(order, rule);
								Mono<CommerceModels.Order> work = repository
										.correctAttribution(order.id(), target, split.recommenderBps(),
												split.recommenderAmountCents(), split.merchantBps(),
												split.merchantAmountCents())
										.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
										.delayUntil(updated -> repository.insertAttribution(updated.id(), target,
												split.recommenderBps(), "ops_correction", blankToNull(command.reason()),
												caller.accountId()))
										.delayUntil(updated -> repository.findLatestAttributionAppeal(order.id())
												.filter(open -> "open".equals(open.status())
														&& (command.appealId() == null || command.appealId().isBlank()
																|| command.appealId().equals(open.id())))
												.flatMap(open -> repository.resolveAttributionAppeal(open.id(),
														"applied", blankToNull(command.reason()), caller.accountId())))
										.flatMap(updated -> outbox
												.append(orderEvent("ConsumerOrderAttributionCorrected", updated))
												.thenReturn(updated));
								return transactions.transactional(work);
							});
				});
	}

	/** 按冻结版本规则重算三方分账：固定佣取冻结固定额，比例佣取冻结 bps；平台费沿用订单行冻结值。 */
	private static RecomputedSplit recomputeSplit(CommerceModels.Order order, CommerceModels.OfferVersion rule) {
		int recommenderBps;
		long recommenderAmount;
		if (rule.isFixedCommission()) {
			recommenderBps = 0;
			recommenderAmount = rule.recommenderFixedCents();
		} else {
			recommenderBps = rule.recommenderShareBps();
			recommenderAmount = basisPoints(order.priceCents(), recommenderBps);
		}
		long merchant = order.priceCents() - order.platformFeeCents() - recommenderAmount;
		if (merchant < 0) {
			throw new MarketplaceException(409, "订单冻结规则与当前金额不一致，不能自动纠错");
		}
		return new RecomputedSplit(recommenderBps, recommenderAmount, 10_000 - order.platformFeeBps() - recommenderBps,
				merchant);
	}

	private record RecomputedSplit(int recommenderBps, long recommenderAmountCents, int merchantBps,
			long merchantAmountCents) {
	}

	/** 运营驳回归因申诉（订单不改）。 */
	public Mono<CommerceModels.AttributionAppeal> rejectAttributionAppeal(Caller caller, String appealId,
			RejectionCommand command) {
		String note = command == null || blank(command.note()) ? "审核未通过" : command.note().trim();
		return repository.resolveAttributionAppeal(appealId, "rejected", note, caller.accountId())
				.switchIfEmpty(Mono.error(new MarketplaceException(409, "申诉不存在或已处理")));
	}

	public Flux<CommerceModels.AttributionAppeal> listAdminAttributionAppeals(String status, int limit, int offset) {
		return repository.listAttributionAppeals(status, limit, offset);
	}

	public Mono<Integer> countAdminAttributionAppeals(String status) {
		return repository.countAttributionAppeals(status);
	}

	public Flux<CommerceRepository.AttributionAllocation> attributionAllocations(Caller caller, String orderId) {
		return findConsumerOrder(caller, orderId)
				.flatMapMany(order -> repository.findAttributionAllocations(order.id()));
	}

	public Flux<Order> listMerchantOrders(Caller caller, String organizationId, String storeId, int limit) {
		return authorization.requireScope(caller, organizationId, storeId, "staff")
				.flatMapMany(scope -> repository.listMerchantOrders(scope.organizationId(), scope.storeId(), limit));
	}

	/** 推荐官「我的推广」（任务书 #75 卡 B6）：本人 accepted 的套餐推广任务 + 归因订单漏斗，权限=本人。 */
	public Flux<CommerceRepository.RecommenderPromotion> recommenderPromotions(Caller caller) {
		return repository.recommenderPromotions(caller.accountId());
	}

	/** 商家推广统计（任务书 #75 卡 D2）：本主体（可选门店）全部套餐推广任务漏斗，权限照既有 merchant commerce 端点。 */
	public Flux<CommerceRepository.MerchantPromotion> merchantPromotions(Caller caller, String organizationId,
			String storeId) {
		return authorization.requireScope(caller, organizationId, storeId, "staff")
				.flatMapMany(scope -> repository.merchantPromotions(scope.organizationId(), scope.storeId()));
	}

	public Flux<Order> exportMerchantOrders(Caller caller, String organizationId, String storeId, String status,
			Instant from, Instant to) {
		return authorization.requireScope(caller, organizationId, storeId, "staff").flatMapMany(scope -> repository
				.exportMerchantOrders(scope.organizationId(), scope.storeId(), status, from, to, 10_000));
	}

	public Flux<Order> listAdminOrders(String status, int limit, int offset) {
		return repository.listAdminOrders(status, limit, offset);
	}

	/** 任务书 #53：与行查同 WHERE 口径的 total。 */
	public Mono<Integer> countAdminOrders(String status) {
		return repository.countAdminOrders(status);
	}

	public Flux<Order> listAdminRedemptions(int limit, int offset) {
		return repository.listAdminRedemptions(limit, offset);
	}

	public Mono<Integer> countAdminRedemptions() {
		return repository.countAdminRedemptions();
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

	/**
	 * 支付尝试（审查修复 01 R01/C01-B 重写）：发起前先持久化支付操作占位（in_flight，稳定幂等键 =
	 * payment_operation_id），按持久化退避时间领取租约后再调 finance（幂等）， 成功后与
	 * markPaid/事件<b>同一事务</b>收尾操作。 取消在途胜出时（markPaid 0 行 + 订单
	 * cancelled）同事务登记取消补偿退款并立即驱动；回复丢失 （错误/进程退出）操作留 in_flight，由恢复驱动按同一操作键重放——finance
	 * 侧幂等保证不会重复扣。
	 */
	Mono<Order> attemptPayment(Order order) {
		if (!"pending_payment".equals(order.status()))
			return Mono.just(order);
		return transactions
				.transactional(fundOperations.ensurePaymentOperation(order.id(), order.paymentOperationId(),
						order.priceCents(), order.version()))
				.flatMap(operation -> fundOperations.claim(operation.operationId(), fundLeaseOwner(), FUND_LEASE,
						"pending_payment"))
				.flatMap(operation -> Mono.defer(() -> finance.pay(order))
						.flatMap(providerRef -> finalizePaymentSuccess(order, providerRef))
						.onErrorResume(error -> failPaymentOperation(order, operation, error)))
				.switchIfEmpty(repository.findOrder(order.id()).defaultIfEmpty(order));
	}

	/** 支付成功回复到达后的收尾：markPaid 胜出 → 落账+事件；取消已胜出 → 支付操作收尾 + 登记补偿；其余状态按幂等收尾操作。 */
	private Mono<Order> finalizePaymentSuccess(Order order, String providerRef) {
		Mono<Order> work = repository.markPaid(order.id(), providerRef)
				.flatMap(updated -> fundOperations.succeed(order.paymentOperationId(), providerRef)
						.then(outbox.append(orderEvent("ConsumerOrderPaid", updated))).thenReturn(updated))
				.switchIfEmpty(Mono.defer(() -> repository.findOrder(order.id()).flatMap(fresh -> {
					if ("cancelled".equals(fresh.status())) {
						// 取消胜出但支付已捕获：支付操作收尾（后续恢复不再重放本单支付），
						// 同事务登记稳定幂等的补偿退款（订单行预写补偿键与全额），恢复驱动完成退款；
						// 库存已由取消路径释放一次，这里不再碰库存。
						return fundOperations.succeed(order.paymentOperationId(), providerRef)
								.then(fundOperations.registerCancelCompensation(fresh.id(), fresh.priceCents(),
										fresh.version()))
								.flatMap(compensation -> repository.prepareCancelCompensation(fresh.id(),
										compensation.operationId()))
								.thenReturn(fresh);
					}
					// 其余状态 = 此前一轮已完成 markPaid（操作与状态同事务，理论不达）——幂等收尾。
					return fundOperations.succeed(order.paymentOperationId(), providerRef).thenReturn(fresh);
				})));
		return transactions.transactional(work)
				.flatMap(fresh -> "cancelled".equals(fresh.status())
						? refunds.attemptCancelCompensation(fresh.id()).defaultIfEmpty(fresh)
						: Mono.just(fresh));
	}

	private Mono<Order> failPaymentOperation(Order order, FundOperation operation, Throwable error) {
		return fundOperations.fail(operation, definitive(error), error.getMessage())
				.flatMap(failed -> repository.recordError(order.id(), "pending_payment", error.getMessage()))
				.then(repository.findOrder(order.id()));
	}

	/** 已取消订单的支付操作重放（回复丢失恢复）：finance 幂等重发取得既成事实，再走成功收尾。 */
	private Mono<Order> drivePaymentOperationOnCancelled(Order order, FundOperation operation) {
		return Mono.defer(() -> finance.pay(order)).flatMap(providerRef -> finalizePaymentSuccess(order, providerRef))
				.onErrorResume(error -> fundOperations.fail(operation, definitive(error), error.getMessage())
						.then(repository.findOrder(order.id())));
	}

	/**
	 * 恢复驱动（C01-A：外部结果未知时有可恢复的处理状态）：租约领取到期未终态的资金操作并按操作键 重放。payment 只捞订单已取消的行（未取消的由
	 * pendingDispatch 正常驱动，避免双路重发）。
	 */
	Flux<Order> recoverFundOperations(int limit) {
		return Flux.defer(() -> fundOperations.claimRecoverable(limit, fundLeaseOwner(), FUND_LEASE))
				.flatMap(operation -> switch (operation.operationType()) {
					case CommerceFundOperationRepository.TYPE_PAYMENT -> repository.findOrder(operation.orderId())
							.flatMap(fresh -> "cancelled".equals(fresh.status())
									? drivePaymentOperationOnCancelled(fresh, operation)
									: Mono.just(fresh));
					case CommerceFundOperationRepository.TYPE_CANCEL_COMPENSATION ->
						refunds.driveCancelCompensation(operation);
					default -> Mono.empty();
				}, 4);
	}

	private String fundLeaseOwner() {
		return recoveryOwner + ":" + UUID.randomUUID();
	}

	static boolean definitive(Throwable error) {
		return error instanceof FinanceCommerceClient.FinanceCommerceException exception && exception.definitive();
	}

	/** 闸门机器可读标识（错误信封 blockedReason 与订单回显 refundBlockedReason 同源）。 */
	static final String SETTLED_NO_REFUND = "settled_no_refund";

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
		// 任务书 #75 D2：佣金二形态——固定额与比例互斥（fixedCents 非空 ⇔ recommenderShareBps=0，
		// bps=0 为形式值仍满足 version 三 bps 和=10000 CHECK）；超价校验在创建/改版时拦（fixed > 价格−平台费）。
		Long fixedCents = command.recommenderFixedCents();
		if (fixedCents != null) {
			if (recommender != 0) {
				throw new IllegalArgumentException("佣金形态只能二选一：固定佣金与比例佣金不能同时设置");
			}
			long shareable = command.priceCents() - basisPoints(command.priceCents(), platform);
			if (fixedCents < 0 || fixedCents > shareable) {
				throw new IllegalArgumentException("固定佣金超出可分配范围（不能为负且不得超过价格减平台费）");
			}
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
				command.inventorySlots() == null ? java.util.List.of() : command.inventorySlots(), fixedCents);
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

	static EventEnvelope orderEvent(String type, Order order) {
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

	static boolean blank(String value) {
		return value == null || value.isBlank();
	}
	private static String blankToNull(String value) {
		return blank(value) ? null : value.trim();
	}

	public record OfferCommand(String organizationId, String storeId, String taskId, String title, String description,
			long priceCents, int totalStock, Instant fixedRedeemDeadline, Integer validDaysAfterPurchase,
			int recommenderShareBps, int platformFeeBps, String policyVersion,
			java.util.List<CommerceRepository.InventorySlotInput> inventorySlots, Long recommenderFixedCents) {

		/** 便捷构造：任务书 #75 之前的签名（固定佣 null）。 */
		public OfferCommand(String organizationId, String storeId, String taskId, String title, String description,
				long priceCents, int totalStock, Instant fixedRedeemDeadline, Integer validDaysAfterPurchase,
				int recommenderShareBps, int platformFeeBps, String policyVersion,
				java.util.List<CommerceRepository.InventorySlotInput> inventorySlots) {
			this(organizationId, storeId, taskId, title, description, priceCents, totalStock, fixedRedeemDeadline,
					validDaysAfterPurchase, recommenderShareBps, platformFeeBps, policyVersion, inventorySlots, null);
		}
	}
	/** referralLinkId（#98 D98-01）与旧 recommenderAccountId 互斥（同传 400），均可缺省=自然流量。 */
	public record CreateOrderCommand(String packageId, String recommenderAccountId, String referralLinkId,
			String inventorySlotId) {
	}

	/**
	 * 归因申诉（业务审查 2026-09-07 C01）：可选字段装箱——Jackson 3 对缺失 primitive 直接 400。
	 * 不携带任何分成比例；金额始终由订单冻结规则计算。
	 */
	public record AppealCommand(String claimedRecommenderAccountId, String reason) {
	}

	/** 运营纠错指令：target 推荐官 + 处置说明；金额/比例不由客户端提交。 */
	public record CorrectionCommand(String recommenderAccountId, String reason, String appealId) {
	}

	/** 运营驳回归因申诉。 */
	public record RejectionCommand(String note) {
	}

	public record DisputeResolutionCommand(String resolution, Long amountCents, String reason) {
	}
	public record ReviewCommand(int rating, String comment) {
	}
}
