package com.grassland.marketplace.commerce;

import static com.grassland.marketplace.commerce.CommerceService.orderEvent;

import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-21：消费者核销与净额分账服务——自 {@link CommerceService} 按职责原样搬移
 * （核销直迁与冷静期快照/核销码展示与资格视图/分账占位·净额·收尾·经确认事实持久化），逻辑零变更； facade 以委托保持既有公共
 * API（controller/dispatcher 调用方零改动）。
 */
@Component
public class ConsumerRedemptionService {

	private final CommerceRepository repository;
	private final TaskResourceAuthorization authorization;
	private final RedeemCodeCodec codes;
	private final FinanceCommerceClient finance;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;
	private final CommerceSettlementFactRepository settlementFacts;
	private final long splitCooldownHours;
	private final long splitCooldownSecondsOverride;

	public ConsumerRedemptionService(CommerceRepository repository, TaskResourceAuthorization authorization,
			RedeemCodeCodec codes, FinanceCommerceClient finance, OutboxRepository outbox,
			TransactionalOperator transactions, CommerceSettlementFactRepository settlementFacts,
			@org.springframework.beans.factory.annotation.Value("${marketplace.commerce.split-cooldown-hours:48}") long splitCooldownHours,
			@org.springframework.beans.factory.annotation.Value("${marketplace.commerce.split-cooldown-seconds-override:0}") long splitCooldownSecondsOverride) {
		this.repository = repository;
		this.authorization = authorization;
		this.codes = codes;
		this.finance = finance;
		this.outbox = outbox;
		this.transactions = transactions;
		this.settlementFacts = settlementFacts;
		// 任务书 #75 D3：负配视作未配（防误配产生负 sleep）；0 小时 = 立即分账哨兵（IT/回滚开关）。
		this.splitCooldownHours = splitCooldownHours < 0 ? 48 : splitCooldownHours;
		this.splitCooldownSecondsOverride = Math.max(splitCooldownSecondsOverride, 0);
	}

	/**
	 * 冷静期生效秒数（任务书 #75 D3）：秒级 override 优先（照 trust voteWindowSecondsEffective 惯例， 供
	 * IT 与冒烟拨快）；0 = 核销即可分账。
	 */
	long splitCooldownSecondsEffective() {
		return splitCooldownSecondsOverride > 0 ? splitCooldownSecondsOverride : splitCooldownHours * 3600;
	}

	/**
	 * 核销（任务书 #75 D3 解耦版）：paid → redeemed 直迁（商家核销即刻成功，不再被分账 RPC 拦），冷静期
	 * {@code split_eligible_at = 核销时刻 + cooldown} 同事务快照落行；分账由 dispatcher 冷静期满后触发。
	 * redeeming 旧在途单（升级时刻卡住）维持旧语义立即补一次分账收尾。 审查修复
	 * 01（R03）：未核销的部分退款单（partially_refunded 且未核销）继续合法核销—— 部分退款保留剩余履约义务，不是终态。
	 */
	public Mono<Order> redeem(Caller caller, String code) {
		String hash = codes.hash(code);
		return repository.findOrderByCodeHash(hash).switchIfEmpty(Mono.error(new MarketplaceException(404, "核销码无效")))
				.flatMap(order -> authorization.requireScope(caller, order.organizationId(), order.storeId(), "staff")
						.thenReturn(order))
				.flatMap(order -> {
					if ("redeeming".equals(order.status()))
						return attemptSplit(order);
					// 任务书 #103 C103-07：预检与展示共用同一资格规则（SQL 守卫仍为最终权威）。
					OrderRedemptionPolicy.Result eligibility = OrderRedemptionPolicy.evaluate(order, Instant.now());
					if (!eligibility.allowed()) {
						return Mono.error(new MarketplaceException(409,
								redeemBlockedMessage(eligibility.blockedReason()), eligibility.blockedReason()));
					}
					Mono<Order> mark = repository
							.markRedeemedWithCooldown(order.id(), "commerce-split:" + order.id(),
									Instant.now().plusSeconds(splitCooldownSecondsEffective()))
							.switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
							.flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRedeemed", updated))
									.thenReturn(updated));
					return transactions.transactional(mark);
				});
	}

	/**
	 * 原核销码展示（任务书 #103 C103-07 / R07）：资格由 {@link OrderRedemptionPolicy} 与实际核销同源判定
	 * ——部分退款（未核销、净额&gt;0）继续返回<b>原码</b>，不生成替代码；无资格返回 null（前端按
	 * redemptionEligibility.blockedReason 说明，不得用新码「修复」）。
	 */
	public String redeemCode(Order order) {
		return OrderRedemptionPolicy.evaluate(order, Instant.now()).allowed() ? codes.codeForOrder(order.id()) : null;
	}

	/** 资格视图（§6.3 redemptionEligibility）：controller 回显与核销预检共用。 */
	public OrderRedemptionPolicy.Result redemptionEligibility(Order order) {
		return OrderRedemptionPolicy.evaluate(order, Instant.now());
	}

	private static String redeemBlockedMessage(String reason) {
		return switch (reason) {
			case "already_redeemed" -> "该核销码已使用";
			case "fully_refunded" -> "订单已全额退款，无剩余履约";
			case "refund_in_progress" -> "退款处理中，请等待完成后再核销";
			case "fund_operation_in_progress" -> "订单结算处理中，请稍后核销";
			case "after_sales_disputed" -> "订单售后争议处理中，暂不可核销";
			case "expired" -> "核销码已过期，订单将自动退款";
			case "not_paid" -> "订单未支付";
			default -> "订单当前不可核销";
		};
	}

	/**
	 * 分账尝试（审查修复 01 重写，任务书 #75 D3 语义保持）：
	 * <ol>
	 * <li><b>原子占位</b>（R02/C01-C）：claimSplit 单行条件 UPDATE 把 redeemed / 已核销
	 * partially_refunded / 存量 redeeming 迁入 splitting——售后开案、退款请求与暂扣确认竞争同一状态位，
	 * 单边胜出；held 行在 claim 里即被排除（C01-E）。0 行 = 他路先赢，本轮跳过。</li>
	 * <li><b>净额计算</b>（R03/C01-D）：{@link NetSplitAllocation} 从订单冻结金额 + 累计退款一次算出
	 * 三方净额（不重写原金额，不改 finance 原支付额校验基准）。</li>
	 * <li><b>幂等发出 + 唯一收尾权</b>：finance.split 幂等键
	 * {@code commerce-split:<orderId>}；成功后 markSplitCompleted 只接受
	 * splitting/redeeming——售后开案无法再让财务已分账的事实丢失。</li>
	 * <li><b>恢复重放</b>：splitting 行再次进入本方法 = 上一执行者崩在 RPC 与收尾之间，按同一幂等键 重发收尾。</li>
	 * </ol>
	 */
	Mono<Order> attemptSplit(Order snapshot) {
		boolean resume = "splitting".equals(snapshot.status());
		boolean legacyInFlight = "redeeming".equals(snapshot.status());
		boolean due = ("redeemed".equals(snapshot.status()) || "partially_refunded".equals(snapshot.status()))
				&& snapshot.redeemedAt() != null && snapshot.splitCompletedAt() == null
				&& snapshot.splitEligibleAt() != null && !snapshot.splitEligibleAt().isAfter(Instant.now());
		if (!resume && !legacyInFlight && !due) {
			return Mono.just(snapshot);
		}
		Mono<Order> claim = resume
				? repository.findOrder(snapshot.id()).filter(fresh -> "splitting".equals(fresh.status()))
				: repository.claimSplit(snapshot.id());
		return claim.flatMap(claimed -> {
			NetSplitAllocation.NetSplit net = NetSplitAllocation.allocate(claimed.priceCents(),
					claimed.recommenderAmountCents(), claimed.merchantAmountCents(), claimed.platformFeeCents(),
					claimed.refundedAmountCents());
			if (net.netTotalCents() <= 0) {
				// 退满单理论上止于 refunded 终态；防御性归还占位并可见（不进零额账本）。
				return repository.abandonSplitClaim(claimed.id(), "net_zero_after_refund").defaultIfEmpty(claimed);
			}
			Order netOrder = withNetAmounts(claimed, net);
			return repository.findAttributionAllocations(claimed.id()).collectList()
					.flatMap(
							allocations -> finance.split(netOrder, allocations)
									.then(transactions.transactional(repository.markSplitCompleted(claimed.id())
											// C103-15：分账成功同事务持久化经确认净额事实（幂等；恢复重放同键吸收）。
											.flatMap(completed -> settlementFacts
													.recordVerified(settlementFactOf(claimed, net, allocations),
															netAllocations(claimed, net, allocations))
													.thenReturn(completed))
											// 历史 redeeming 单补发核销事件（新单核销时已发，D3 事件语义=核销即发）。
											.flatMap(completed -> legacyInFlight
													? outbox.append(orderEvent("ConsumerOrderRedeemed", completed))
															.thenReturn(completed)
													: Mono.just(completed)))))
					// RPC 或本地提交失败都不能证明资金未分出。保留 splitting，按原操作键重放收尾；
					// 即使本轮收到 4xx，也可能有上一轮/其他副本的成功在途，不重新开放退款与暂扣。
					.onErrorResume(error -> repository.recordError(claimed.id(), "splitting", error.getMessage())
							.then(repository.findOrder(claimed.id())).defaultIfEmpty(claimed));
		}).defaultIfEmpty(snapshot);
	}

	/** C103-15：经确认净额事实（原支付额/分账前累计退款/三方净额 + finance 完成时刻）。 */
	private static CommerceSettlementFactRepository.VerifiedFact settlementFactOf(CommerceModels.Order claimed,
			NetSplitAllocation.NetSplit net, java.util.List<CommerceRepository.AttributionAllocation> allocations) {
		return new CommerceSettlementFactRepository.VerifiedFact(claimed.id(), claimed.organizationId(),
				claimed.splitOperationId(), claimed.priceCents(), claimed.refundedAmountCents(), net.netTotalCents(),
				net.merchantAmountCents(), net.platformFeeCents(), net.recommenderAmountCents(), Instant.now(),
				Instant.now());
	}

	/**
	 * 净推荐官总额按冻结分配等比整分摊（事实快照约束：每单分配之和=推荐官总净额）。 空 allocations = #75 D5 起的单归因新单
	 * （createOrder 不再写 V37 行）：按订单冻结的归因推荐官落全额净额； 自然流量单（无归因、净额必为 0）落零额占位行。 C103-15
	 * 尾巴修复：原实现空集恒落零额行，净额&gt;0 时被 recordVerified 的和校验拒收， 分账事实永远落不了、订单卡 splitting
	 * 反复重试（OpsOrderHoldIT/CommercePromotionTaskIT 实锤）。
	 */
	private static java.util.List<CommerceSettlementFactRepository.Allocation> netAllocations(
			CommerceModels.Order claimed, NetSplitAllocation.NetSplit net,
			java.util.List<CommerceRepository.AttributionAllocation> allocations) {
		if (allocations == null || allocations.isEmpty()) {
			String recommender = claimed.recommenderAccountId();
			if (recommender == null && net.recommenderAmountCents() != 0) {
				throw new IllegalStateException("无归因推荐官但净推荐额非零：" + claimed.id());
			}
			return java.util.List.of(new CommerceSettlementFactRepository.Allocation(
					recommender != null ? recommender : "00000000-0000-0000-0000-000000000000",
					net.recommenderAmountCents()));
		}
		long[] frozen = allocations.stream().mapToLong(CommerceRepository.AttributionAllocation::amountCents).toArray();
		long[] scaled = NetSplitAllocation.scaleToTotal(net.recommenderAmountCents(), frozen);
		java.util.List<CommerceSettlementFactRepository.Allocation> result = new java.util.ArrayList<>();
		for (int i = 0; i < allocations.size(); i++) {
			result.add(new CommerceSettlementFactRepository.Allocation(allocations.get(i).recommenderAccountId(),
					scaled[i]));
		}
		return result;
	}

	/** 净额视图（不改库）：保留订单冻结字段，发送给 finance 的载荷按净额覆盖三方金额。 */
	private static Order withNetAmounts(Order order, NetSplitAllocation.NetSplit net) {
		return new Order(order.id(), order.consumerAccountId(), order.organizationId(), order.storeId(), order.taskId(),
				order.packageId(), order.packageVersionId(), order.packageVersion(), order.packageTitle(),
				order.recommenderAccountId(), order.priceCents(), order.recommenderShareBps(), order.platformFeeBps(),
				order.merchantShareBps(), net.recommenderAmountCents(), net.platformFeeCents(),
				net.merchantAmountCents(), order.policyVersion(), order.status(), order.refundedAmountCents(),
				order.refundRequestedAmountCents(), order.refundReason(), order.inventorySlotId(),
				order.redeemCodeHash(), order.redeemDeadline(), order.paymentDeadline(), order.paymentOperationId(),
				order.refundOperationId(), order.splitOperationId(), order.providerRef(), order.lastError(),
				order.version(), order.createdAt(), order.paidAt(), order.redeemedAt(), order.refundedAt(),
				order.updatedAt(), order.slotStart(), order.slotEnd(), order.splitEligibleAt(),
				order.splitCompletedAt());
	}
}
