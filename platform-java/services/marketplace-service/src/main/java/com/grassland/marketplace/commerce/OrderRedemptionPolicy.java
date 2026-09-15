package com.grassland.marketplace.commerce;

import java.time.Instant;

/**
 * 核销资格纯规则（任务书 #103 C103-07 / §6.3 / R07）：展示码能力与实际核销资格同源。
 *
 * <p>
 * 输出稳定 blockedReason（机器可读，前端据此展示，不自行推断）：依次检查已支付、未核销、
 * 净额 &gt; 0、无资金操作在途、未过期、合法状态集合（paid / redeeming / 未核销的
 * partially_refunded）。SQL 守卫（markRedeemedWithCooldown / markRefunded 等）仍是最终权威；
 * 本规则是展示与预检的同一份事实来源——保留原码、不生成替代码、不扩大可读人群。
 */
public final class OrderRedemptionPolicy {

	public record Result(boolean allowed, String blockedReason) {
		static Result allow() {
			return new Result(true, null);
		}

		static Result block(String reason) {
			return new Result(false, reason);
		}
	}

	private OrderRedemptionPolicy() {
	}

	public static Result evaluate(CommerceModels.Order order, Instant now) {
		if (order.paidAt() == null) {
			return Result.block("not_paid");
		}
		if (order.redeemedAt() != null) {
			return Result.block("already_redeemed");
		}
		if (order.priceCents() - order.refundedAmountCents() <= 0) {
			return Result.block("fully_refunded");
		}
		if ("refund_pending".equals(order.status())) {
			return Result.block("refund_in_progress");
		}
		if ("splitting".equals(order.status())) {
			return Result.block("fund_operation_in_progress");
		}
		if ("after_sales_disputed".equals(order.status())) {
			return Result.block("after_sales_disputed");
		}
		if (order.redeemDeadline() != null && !order.redeemDeadline().isAfter(now)) {
			return Result.block("expired");
		}
		// 合法可核销状态：paid / redeeming（存量在途）/ 未核销的 partially_refunded（剩余履约义务保留）。
		if ("paid".equals(order.status()) || "redeeming".equals(order.status())
				|| ("partially_refunded".equals(order.status()) && order.redeemedAt() == null)) {
			return Result.allow();
		}
		return Result.block("not_redeemable");
	}
}
