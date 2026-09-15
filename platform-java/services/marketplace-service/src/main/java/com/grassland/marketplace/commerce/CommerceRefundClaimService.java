package com.grassland.marketplace.commerce;

import com.grassland.marketplace.security.MarketplaceException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 统一退款 claim（任务书 #103 C103-05 / D103-03 / R02）：全部业务退款写入口共用的条件更新与失败分类。
 *
 * <p>
 * 权威守卫烧进 SQL：状态合法、累计退款不超原支付额、{@code split_completed_at IS NULL}
 * （已结算事实与新增退款互斥——Java 侧旧快照检查只是友好提示，不是闸门）。0 行 → 重读订单分类
 * 409（settled_no_refund / fund_operation_in_progress / refund_in_progress / 超余额 / 状态已变化），
 * 不再发任何 Finance 请求。同退款 operationId 重放由 Finance 同键幂等与既有恢复链处理，本层不重建退款键。
 */
@Component
public class CommerceRefundClaimService {

	static final String SETTLED_NO_REFUND = "settled_no_refund";
	static final String FUND_OPERATION_IN_PROGRESS = "fund_operation_in_progress";
	static final String REFUND_IN_PROGRESS = "refund_in_progress";

	private final CommerceRepository repository;

	public CommerceRefundClaimService(CommerceRepository repository) {
		this.repository = repository;
	}

	/** 买家主动退款（paid / 未结算 partially_refunded）。 */
	public Mono<CommerceModels.Order> claimConsumerRefund(String orderId, String operationId, long amountCents,
			String reason) {
		return repository.requestRefund(orderId, operationId, amountCents, reason)
				.switchIfEmpty(Mono.defer(() -> classify(orderId, amountCents)));
	}

	/** 售后裁定退款（after_sales_disputed）。 */
	public Mono<CommerceModels.Order> claimDisputeRefund(String orderId, String operationId, long amountCents,
			String reason) {
		return repository.requestDisputeRefund(orderId, operationId, amountCents, reason)
				.switchIfEmpty(Mono.defer(() -> classify(orderId, amountCents)));
	}

	/** 条件更新失败后的权威分类：按数据库当前行给出稳定 blockedReason，不猜测旧快照。 */
	private Mono<CommerceModels.Order> classify(String orderId, long amountCents) {
		return repository.findOrder(orderId)
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
				.<CommerceModels.Order>flatMap(current -> Mono.error(classifyFailure(current, amountCents)));
	}

	static MarketplaceException classifyFailure(CommerceModels.Order current, long amountCents) {
		if (current.splitCompletedAt() != null) {
			return new MarketplaceException(409, "订单佣金已结算，不支持退款；售后申请须在售后窗口内提出",
					SETTLED_NO_REFUND);
		}
		if ("splitting".equals(current.status())) {
			return new MarketplaceException(409, "订单结算处理中，暂时无法退款，请稍后重试", FUND_OPERATION_IN_PROGRESS);
		}
		if ("refund_pending".equals(current.status())) {
			return new MarketplaceException(409, "已有退款处理中，请等待完成后再申请", REFUND_IN_PROGRESS);
		}
		if (amountCents > current.priceCents() - current.refundedAmountCents()) {
			return new MarketplaceException(409, "退款金额超过可退余额");
		}
		return new MarketplaceException(409, "订单状态已变化，无法退款");
	}
}
