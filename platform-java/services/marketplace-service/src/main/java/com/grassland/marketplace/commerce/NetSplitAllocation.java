package com.grassland.marketplace.commerce;

/**
 * 审查修复 01（R03/C01-D）：唯一的服务端净额分配函数。
 *
 * <p>
 * 输入一律取订单下单冻结的三方金额（{@code 推荐官 + 平台 + 商家 = 原支付额}）与<b>累计</b>已确认退款，
 * 输出净分账三方金额。累计口径保证同一总退款无论一次退成还是分多次退成，最终分配完全一致 （逐次取整不会累计漂移）。分摊规则沿用 finance 既有
 * {@code refundAfterSplit} 的比例分摊： 退款按各方冻结占比整数分摊（向下取整），尾差由平台吸收；平台份额不够时溢出转嫁给商家
 * （与既有售后冲销算法同构，不引入新费率）。
 *
 * <p>
 * 例算（价格 10000，推荐官 1000 / 商家 8500 / 平台 500，累计退款 3000）： 退款分摊 推荐官 300 / 商家 2550 /
 * 平台 150 → 净分账 700 / 5950 / 350，合计 7000 = 10000 − 3000。
 */
public final class NetSplitAllocation {

	private NetSplitAllocation() {
	}

	public record NetSplit(long recommenderAmountCents, long merchantAmountCents, long platformFeeCents,
			long netTotalCents) {

		public long totalCents() {
			return Math.addExact(Math.addExact(recommenderAmountCents, merchantAmountCents), platformFeeCents);
		}
	}

	public static NetSplit allocate(long priceCents, long recommender0Cents, long merchant0Cents, long platform0Cents,
			long cumulativeRefundCents) {
		if (priceCents <= 0 || recommender0Cents < 0 || merchant0Cents < 0 || platform0Cents < 0
				|| Math.addExact(Math.addExact(recommender0Cents, merchant0Cents), platform0Cents) != priceCents) {
			throw new IllegalArgumentException("冻结分账金额与原支付额不一致");
		}
		if (cumulativeRefundCents < 0 || cumulativeRefundCents > priceCents) {
			throw new IllegalArgumentException("累计退款超出原支付额");
		}
		long refund = cumulativeRefundCents;
		long recommenderRefund = Math.min(recommender0Cents, refund * recommender0Cents / priceCents);
		long merchantRefund = Math.min(merchant0Cents, refund * merchant0Cents / priceCents);
		long platformRefund = refund - recommenderRefund - merchantRefund;
		if (platformRefund > platform0Cents) {
			// 平台份额吃不下全部尾差 → 溢出转嫁商家（封顶商家冻结额），与 finance 既有冲销同构。
			long overflow = platformRefund - platform0Cents;
			merchantRefund = Math.min(merchant0Cents, merchantRefund + overflow);
			platformRefund = refund - recommenderRefund - merchantRefund;
		}
		return new NetSplit(recommender0Cents - recommenderRefund, merchant0Cents - merchantRefund,
				platform0Cents - platformRefund, priceCents - refund);
	}

	/**
	 * 任务书 #103 C103-15：把冻结口径的每推荐官分配等比缩放到净推荐官总额（最大余数法整分摊， 按入参顺序打破平票——确定性，无 Double
	 * 运算）。事实快照要求每单分配之和等于净推荐官总额。
	 */
	public static long[] scaleToTotal(long targetTotal, long[] frozenAmounts) {
		if (frozenAmounts == null || frozenAmounts.length == 0) {
			if (targetTotal != 0) {
				throw new IllegalArgumentException("无分配可缩放但目标净额非零");
			}
			return new long[0];
		}
		long frozenTotal = 0;
		for (long amount : frozenAmounts) {
			if (amount < 0) {
				throw new IllegalArgumentException("冻结分配不能为负");
			}
			frozenTotal = Math.addExact(frozenTotal, amount);
		}
		long[] scaled = new long[frozenAmounts.length];
		if (frozenTotal == 0) {
			// 无冻结信息（免费/零佣）：全部分给首位与净额一致或全零——保守全零仅当目标为零。
			if (targetTotal == 0) {
				return scaled;
			}
			scaled[0] = targetTotal;
			return scaled;
		}
		long allocated = 0;
		long[] remainders = new long[frozenAmounts.length];
		for (int i = 0; i < frozenAmounts.length; i++) {
			scaled[i] = targetTotal * frozenAmounts[i] / frozenTotal;
			remainders[i] = targetTotal * frozenAmounts[i] % frozenTotal;
			allocated = Math.addExact(allocated, scaled[i]);
		}
		long remainder = targetTotal - allocated;
		// 最大余数法：按余数降序（稳定——余数相同按索引升序）逐个 +1 分完尾差。
		Integer[] order = new Integer[frozenAmounts.length];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		java.util.Arrays.sort(order,
				(x, y) -> remainders[y] != remainders[x]
						? Long.compare(remainders[y], remainders[x])
						: Integer.compare(x, y));
		for (int k = 0; k < remainder && k < order.length; k++) {
			scaled[order[k]] += 1;
		}
		return scaled;
	}
}
