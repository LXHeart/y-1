package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 核销资格纯规则单测（任务书 #103 C103-07 / TC103-07-01 资格表）。
 * R07 反例 → 不变量：展示能力与实际核销资格同源——部分退款（未核销、净额&gt;0）同为
 * 「返回原码」与「可核销」；全退/在途/过期同为「不给码」与「不可核销」。
 */
class OrderRedemptionPolicyTest {

	private final Instant now = Instant.parse("2026-09-15T00:00:00Z");

	private CommerceModels.Order order(String status, long priceCents, long refundedCents, Instant paidAt,
			Instant redeemedAt, Instant deadline) {
		return new CommerceModels.Order(UUID.randomUUID().toString(), "consumer", "org", "store", null, "pkg", "ver", 1,
				"套餐", null, priceCents, 1000, 500, 8500, 0, 0, 0, "commerce-v1", status, refundedCents, null, null,
				null, null, deadline, null, null, null, null, null, null, 0, now.minusSeconds(3600), paidAt,
				redeemedAt, null, now, null, null, null, null);
	}

	@Test
	void paidAndUnredeemedPartialRefundAreEligibleForOriginalCode() {
		assertThat(OrderRedemptionPolicy.evaluate(order("paid", 10_000, 0, now, null, now.plusSeconds(3600)), now)
				.allowed()).isTrue();
		// R07 主形态：部分退款未核销、净额>0 → 原码继续可用。
		var partial = order("partially_refunded", 10_000, 3_000, now, null, now.plusSeconds(3600));
		assertThat(OrderRedemptionPolicy.evaluate(partial, now).allowed()).isTrue();
		assertThat(order("redeeming", 10_000, 0, now, null, now.plusSeconds(3600)).status()).isEqualTo("redeeming");
	}

	@Test
	void blockedStatesCarryStableMachineReadableReasons() {
		assertThat(OrderRedemptionPolicy
				.evaluate(order("paid", 10_000, 0, null, null, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("not_paid");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("redeemed", 10_000, 0, now, now, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("already_redeemed");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("refunded", 10_000, 10_000, now, null, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("fully_refunded");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("refund_pending", 10_000, 0, now, null, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("refund_in_progress");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("splitting", 10_000, 0, now, null, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("fund_operation_in_progress");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("after_sales_disputed", 10_000, 0, now, null, now.plusSeconds(3600)), now)
				.blockedReason()).isEqualTo("after_sales_disputed");
		// 过期边界：截止恰好等于 now 视为到期（§5）。
		assertThat(OrderRedemptionPolicy
				.evaluate(order("paid", 10_000, 0, now, null, now), now).blockedReason()).isEqualTo("expired");
		assertThat(OrderRedemptionPolicy
				.evaluate(order("cancelled", 10_000, 0, now, null, now.plusSeconds(3600)), now).blockedReason())
				.isEqualTo("not_redeemable");
	}

	/** 一分钱剩余仍是真实净额——资格随事实而非金额大小（TC103-07-05 边界）。 */
	@Test
	void oneCentRemainingNetAmountStaysEligible() {
		assertThat(OrderRedemptionPolicy
				.evaluate(order("partially_refunded", 10_000, 9_999, now, null, now.plusSeconds(3600)), now)
				.allowed()).isTrue();
	}
}
