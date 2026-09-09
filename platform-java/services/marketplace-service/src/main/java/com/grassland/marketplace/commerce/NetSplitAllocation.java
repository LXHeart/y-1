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
}
