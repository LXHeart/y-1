package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审查修复 01（R03/C01-D）TC01-08：净额分配函数的金额不变量—— 冻结合计守恒、累计口径确定性（一次退成 vs 分多次退成结果一致）、1
 * 分尾差、退满、接近金额上限、 自然流量（推荐官 0）与固定佣形态。
 */
class NetSplitAllocationTest {

	private static final long PRICE = 10_000;
	private static final long REC = 1_000;
	private static final long MER = 8_500;
	private static final long PLAT = 500;

	private static NetSplitAllocation.NetSplit allocate(long refund) {
		return NetSplitAllocation.allocate(PRICE, REC, MER, PLAT, refund);
	}

	private static void assertInvariant(NetSplitAllocation.NetSplit net, long cumulativeRefund) {
		assertThat(net.totalCents()).as("三方净额之和=原支付额-累计退款").isEqualTo(PRICE - cumulativeRefund);
		assertThat(net.netTotalCents()).isEqualTo(net.totalCents());
		assertThat(net.recommenderAmountCents()).isGreaterThanOrEqualTo(0);
		assertThat(net.merchantAmountCents()).isGreaterThanOrEqualTo(0);
		assertThat(net.platformFeeCents()).isGreaterThanOrEqualTo(0);
	}

	@Test
	@DisplayName("例算：10000 退 3000 → 净 700/5950/350，三方守恒")
	void allocationExampleMatchesDocumentedArithmetic() {
		NetSplitAllocation.NetSplit net = allocate(3_000);
		assertThat(net.recommenderAmountCents()).isEqualTo(700);
		assertThat(net.merchantAmountCents()).isEqualTo(5_950);
		assertThat(net.platformFeeCents()).isEqualTo(350);
		assertInvariant(net, 3_000);
	}

	@Test
	@DisplayName("累计口径确定性：5000 一次退成与 3000+2000 分两次退成最终分配一致")
	void cumulativeRefundIsDeterministicRegardlessOfRefundSplits() {
		NetSplitAllocation.NetSplit oneShot = allocate(5_000);
		// 第二段在首段净冻结（700/5950/350，余 7000）上再退 2000——与一次退 5000 殊途同归。
		NetSplitAllocation.NetSplit incremental = NetSplitAllocation.allocate(7_000,
				allocate(3_000).recommenderAmountCents(), allocate(3_000).merchantAmountCents(),
				allocate(3_000).platformFeeCents(), 2_000);
		assertThat(incremental).isEqualTo(oneShot);
		assertThat(oneShot.recommenderAmountCents()).isEqualTo(500);
		assertThat(oneShot.merchantAmountCents()).isEqualTo(4_250);
		assertThat(oneShot.platformFeeCents()).isEqualTo(250);
		assertInvariant(oneShot, 5_000);
	}

	@Test
	@DisplayName("1 分尾差：退款 1 分全部由平台吸收，三方仍守恒")
	void oneCentRefundIsAbsorbedByPlatformTail() {
		NetSplitAllocation.NetSplit net = allocate(1);
		assertThat(net.recommenderAmountCents()).isEqualTo(REC);
		assertThat(net.merchantAmountCents()).isEqualTo(MER);
		assertThat(net.platformFeeCents()).isEqualTo(PLAT - 1);
		assertInvariant(net, 1);
	}

	@Test
	@DisplayName("退满：净额归零（该形态实际止步 refunded 终态，函数侧仍须给出合法零分配）")
	void fullRefundYieldsZeroNet() {
		NetSplitAllocation.NetSplit net = allocate(PRICE);
		assertThat(net.netTotalCents()).isZero();
		assertInvariant(net, PRICE);
	}

	@Test
	@DisplayName("接近金额上限：退 9999 → 净 1 分（推荐 1 / 商家 0 / 平台 0，含平台溢出转嫁）")
	void nearMaxRefundLeavesOneCentNet() {
		// 退 9999：推荐 floor(999.9)=999、商家 floor(8499.15)=8499，尾差 501 > 平台 500 →
		// 溢出 1 转嫁商家（8499+1=8500）→ 平台恰 500。净额：推荐 1 / 商家 0 / 平台 0。
		NetSplitAllocation.NetSplit net = allocate(PRICE - 1);
		assertThat(net.recommenderAmountCents()).isEqualTo(1);
		assertThat(net.merchantAmountCents()).isZero();
		assertThat(net.platformFeeCents()).isZero();
		assertThat(net.netTotalCents()).isEqualTo(1);
		assertInvariant(net, PRICE - 1);
	}

	@Test
	@DisplayName("自然流量（推荐官冻结 0）：退款不产生推荐官冲减，尾差进平台")
	void naturalTrafficZeroRecommenderKeepsZero() {
		NetSplitAllocation.NetSplit net = NetSplitAllocation.allocate(PRICE, 0, 9_500, 500, 3_000);
		assertThat(net.recommenderAmountCents()).isZero();
		assertThat(net.merchantAmountCents()).isEqualTo(9_500 - 3_000 * 9_500 / PRICE);
		assertInvariant(net, 3_000);
	}

	@Test
	@DisplayName("平台尾差溢出转嫁商家：向下取整损失把尾差推过平台冻结额时，溢出归商家（与 finance 冲销同构）")
	void platformOverflowSpillsToMerchantMirroringFinanceClawback() {
		// S=1000，冻结 推荐 3 / 商家 996 / 平台 1；退 999：推荐 floor(2.997)=2、商家 floor(995.004)=995，
		// 尾差 = 999-2-995 = 2 > 平台冻结 1 → 溢出 1 转嫁商家（封顶 996）。
		NetSplitAllocation.NetSplit net = NetSplitAllocation.allocate(1_000, 3, 996, 1, 999);
		assertThat(net.recommenderAmountCents()).isEqualTo(1);
		assertThat(net.merchantAmountCents()).isZero();
		assertThat(net.platformFeeCents()).isZero();
		assertThat(net.netTotalCents()).isEqualTo(1);
	}

	@Test
	@DisplayName("非法输入：冻结合计不等于原支付额 / 累计退款超原支付额 / 负数")
	void invalidInputsAreRejected() {
		assertThatThrownBy(() -> NetSplitAllocation.allocate(100, 10, 80, 20, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NetSplitAllocation.allocate(100, 10, 80, 10, 101))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> NetSplitAllocation.allocate(100, 10, 80, 10, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
