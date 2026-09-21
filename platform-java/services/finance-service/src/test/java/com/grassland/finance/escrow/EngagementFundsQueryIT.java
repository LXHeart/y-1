package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 退出资金权威事实查询 IT（任务书 #103 C103-03 / §6.2）：只读聚合 reservation/freebie 已提交事实； 仅
 * marketplace principal 可读；不触发任何资金变动；组织不符按 404（不泄露存在性）。
 */
class EngagementFundsQueryIT extends FinanceItSupport {

	@Test
	void exitFactsReportCommittedReservationAndFreebieStates() {
		String merchant = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String ref = "app-" + UUID.randomUUID();
		provision(merchant, org);
		credit(merchant, org, 10_000);
		// 赏金预留 500 + 押金托管 100（freebie 走 /internal/freebie/reserve，marketplace
		// principal）。
		// 带 payeeAccountId 的预留走 marketplace 服务断言（商家用户指定收款人 → 403，见 EscrowController）。
		client().post().uri("/api/finance/accounts/" + org + "/reservations")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("engagementRef", ref, "amountCents", 500, "payeeAccountId", rec)).exchange()
				.expectStatus().isCreated();
		fundRecommenderWallet(org, rec, "fund-" + UUID.randomUUID(), 300);
		client().post().uri("/internal/freebie/reserve").header("X-Grassland-Identity", signService(org, "marketplace"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "recommenderAccountId",
						rec, "taskOwnerAccountId", merchant, "organizationId", org, "amountCents", 100))
				.exchange().expectStatus().isCreated();

		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.bounty.exists").isEqualTo(true)
				.jsonPath("$.data.bounty.originalReservedCents").isEqualTo(500).jsonPath("$.data.bounty.capturedCents")
				.isEqualTo(0).jsonPath("$.data.bounty.releasedCents").isEqualTo(0).jsonPath("$.data.deposit.exists")
				.isEqualTo(true).jsonPath("$.data.deposit.amountCents").isEqualTo(100)
				.jsonPath("$.data.deposit.refundedCents").isEqualTo(0);

		// 只读：再次查询不改变任何事实。
		long balanceBefore = balanceOf(org);
		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk();
		assertThat(balanceOf(org)).isEqualTo(balanceBefore);
	}

	@Test
	void exitFactsReflectCapturedAndRefundedLegs() {
		String merchant = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String ref = "app-" + UUID.randomUUID();
		provision(merchant, org);
		credit(merchant, org, 10_000);
		client().post().uri("/api/finance/accounts/" + org + "/reservations")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("engagementRef", ref, "amountCents", 500, "payeeAccountId", rec, "commissionBonusBps",
						1000))
				.exchange().expectStatus().isCreated();
		client().post().uri("/api/finance/reservations/" + ref + "/capture")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("settlementAmountCents", 300)).exchange().expectStatus().isOk();
		fundRecommenderWallet(org, rec, "fund-" + UUID.randomUUID(), 300);
		client().post().uri("/internal/freebie/reserve").header("X-Grassland-Identity", signService(org, "marketplace"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", ref, "recommenderAccountId",
						rec, "taskOwnerAccountId", merchant, "organizationId", org, "amountCents", 100))
				.exchange().expectStatus().isCreated();
		client().post().uri("/internal/freebie/" + ref + "/refund")
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk();

		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.bounty.capturedCents").isEqualTo(300)
				.jsonPath("$.data.bounty.releasedCents").isEqualTo(200).jsonPath("$.data.deposit.refundedCents")
				.isEqualTo(100).jsonPath("$.data.deposit.compensatedCents").isEqualTo(0);
	}

	@Test
	void exitFactsRequireMarketplacePrincipalAndHonorOrgScope() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String ref = "app-" + UUID.randomUUID();
		// 非 marketplace principal（同 keyring 的其它服务）→ 401/403（拒绝即可，不泄露差异）。
		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "trust")).exchange().expectStatus().is4xxClientError();
		// 无断言 → 401。
		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org).exchange()
				.expectStatus().isUnauthorized();
		// 无事实 → 404。
		client().get().uri("/internal/engagements/" + ref + "/exit-facts?organizationId=" + org)
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isNotFound();
	}

	// ---------- helpers（照 EscrowControllerIT 惯例） ----------

	private void provision(String merchant, String org) {
		client().post().uri("/api/finance/accounts")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction")).exchange()
				.expectStatus().isCreated();
	}

	private void credit(String merchant, String org, long amountCents) {
		client().post().uri("/api/finance/accounts/" + org + "/credit")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amountCents)).exchange()
				.expectStatus().isOk();
	}

	/**
	 * 押金托管扣推荐官钱包——先经预留+capture 给推荐官钱包充值（照 FreebieEscrowControllerIT.fundWallet）。
	 */
	private void fundRecommenderWallet(String org, String rec, String fundingRef, long amount) {
		client().post().uri("/api/finance/accounts/" + org + "/reservations")
				.header("X-Grassland-Identity", signService(org, "marketplace")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("engagementRef", fundingRef, "amountCents", amount, "payeeAccountId", rec)).exchange()
				.expectStatus().isCreated();
		client().post().uri("/api/finance/reservations/" + fundingRef + "/capture")
				.header("X-Grassland-Identity", signService(org, "marketplace")).exchange().expectStatus().isOk();
	}

	private long balanceOf(String org) {
		Long balance = db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
				.bind("org", org).map(r -> r.get("balance_cents", Long.class)).one().block();
		return balance == null ? 0 : balance;
	}
}
