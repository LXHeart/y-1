package com.grassland.finance.freebie;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import com.grassland.finance.ledger.LedgerProjectionService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 霸王餐押金生命周期端到端（ADR-D12 / 任务书 #22 Stage B1）。
 *
 * <p>钱包资金经真实 bounty 链路入账（reserve+capture 分账，账本背书，非裸 INSERT），
 * 再走 /internal/freebie/** 全链：预扣/幂等/余额不足、达标全额返还（fee=0）、未达标补偿商家 org、
 * 争议对账分支、每条 journal 借贷零和、投影可重建（派生==投影）。
 */
class FreebieEscrowControllerIT extends FinanceItSupport {

    private static final String H = "X-Grassland-Identity";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    LedgerProjectionService projections;

    @Test
    void reserveDebitsWalletAndIsIdempotent() {
        Funding funding = fundWallet(600);
        String ref = "eng-" + UUID.randomUUID();

        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(funding.org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", ref,
                        "recommenderAccountId", funding.recommender,
                        "taskOwnerAccountId", funding.merchant,
                        "organizationId", funding.org,
                        "amountCents", 100))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("reserved")
                .jsonPath("$.data.amountCents").isEqualTo(100);

        assertThat(walletBalance(funding.recommender)).isEqualTo(500L);
        assertThat(escrowStatus(ref)).isEqualTo("reserved");
        assertThat(outboxCount("FreebieReserved", ref)).isEqualTo(1);

        // 幂等重放：同 scope 再 reserve → 200 既有，钱包不再扣
        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(funding.org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", ref,
                        "recommenderAccountId", funding.recommender,
                        "taskOwnerAccountId", funding.merchant,
                        "organizationId", funding.org,
                        "amountCents", 100))
                .exchange().expectStatus().isOk();
        assertThat(walletBalance(funding.recommender)).isEqualTo(500L);
        assertThat(outboxCount("FreebieReserved", ref)).isEqualTo(1);

        assertThat(projections.reconcileWallet(funding.recommender).block())
                .as("freebie reserve 后钱包投影可重建").isTrue();
        assertJournalsBalanced();
    }

    @Test
    void reserveWithoutWalletFundsConflicts() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        provision(merchant, org);   // 无钱包/零余额推荐官

        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", "eng-" + UUID.randomUUID(),
                        "recommenderAccountId", recommender,
                        "taskOwnerAccountId", merchant,
                        "organizationId", org,
                        "amountCents", 100))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void reserveAmountMustBePositive() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", "eng-" + UUID.randomUUID(),
                        "recommenderAccountId", UUID.randomUUID().toString(),
                        "organizationId", org,
                        "amountCents", 0))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void reserveByWrongServiceForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        provision(merchant, org);
        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(org, "trust"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", "eng-" + UUID.randomUUID(),
                        "recommenderAccountId", recommender,
                        "organizationId", org,
                        "amountCents", 100))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void refundReturnsFullAmountWithoutFee() {
        Funding funding = fundWallet(600);
        String ref = freebieReserve(funding, 100);
        assertThat(walletBalance(funding.recommender)).isEqualTo(500L);

        client().post().uri("/internal/freebie/" + ref + "/refund")
                .header(H, signService(funding.org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("refunded");

        assertThat(walletBalance(funding.recommender)).as("达标全额返还").isEqualTo(600L);
        assertThat(escrowStatus(ref)).isEqualTo("refunded");
        assertThat(outboxCount("FreebieRefunded", ref)).isEqualTo(1);
        Long fee = db.sql("""
                        SELECT fee_cents FROM wallet_ledger
                        WHERE account_id = CAST(:a AS uuid) AND entry_type = 'freebie_refund'
                        """)
                .bind("a", funding.recommender)
                .map(r -> r.get("fee_cents", Long.class)).one().block();
        assertThat(fee).as("§8.2 全额返还无平台费").isZero();
        Long refundAmount = db.sql("""
                        SELECT amount_cents FROM wallet_ledger
                        WHERE account_id = CAST(:a AS uuid) AND entry_type = 'freebie_refund'
                        """)
                .bind("a", funding.recommender)
                .map(r -> r.get("amount_cents", Long.class)).one().block();
        assertThat(refundAmount).isEqualTo(100L);

        // 再 refund → 409（已终态）
        client().post().uri("/internal/freebie/" + ref + "/refund")
                .header(H, signService(funding.org, "marketplace"))
                .exchange().expectStatus().isEqualTo(409);

        assertThat(projections.reconcileWallet(funding.recommender).block()).isTrue();
        assertJournalsBalanced();
    }

    @Test
    void compensateCreditsMerchantOrgAndNotifiesBothParties() {
        Funding funding = fundWallet(600);
        String ref = freebieReserve(funding, 100);
        long orgBalanceBefore = balanceOf(funding.org);

        client().post().uri("/internal/freebie/" + ref + "/compensate")
                .header(H, signService(funding.org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("compensated");

        assertThat(balanceOf(funding.org)).as("补偿入商家 org 账户").isEqualTo(orgBalanceBefore + 100L);
        assertThat(walletBalance(funding.recommender)).as("补偿不动推荐官钱包（预付时已扣）").isEqualTo(500L);
        assertThat(escrowStatus(ref)).isEqualTo("compensated");
        assertThat(outboxCount("FreebieCompensated", ref)).isEqualTo(1);
        assertThat(outboxPayloadField(ref, "FreebieCompensated", "taskOwnerId")).isEqualTo(funding.merchant);
        assertThat(outboxPayloadField(ref, "FreebieCompensated", "recommenderAccountId"))
                .isEqualTo(funding.recommender);

        assertThat(projections.reconcileEscrow(funding.org).block()).isTrue();
        assertThat(projections.reconcileWallet(funding.recommender).block()).isTrue();
        assertJournalsBalanced();
    }

    /** D6 争议对账：既有 /api/finance/reservations/{ref}/reconcile 端点对 freebie 行按反向矩阵落账。 */
    @Test
    void disputeReconcileBranchesByFundingSource() {
        Funding fundingForMerchant = fundWallet(600);
        String merchantWon = freebieReserve(fundingForMerchant, 100);
        client().post().uri("/api/finance/reservations/" + merchantWon + "/reconcile")
                .header(H, signService(fundingForMerchant.org, "marketplace"))
                .contentType(JSON)
                .bodyValue(Map.of("organizationId", fundingForMerchant.org, "finalDecision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("compensated");
        assertThat(escrowStatus(merchantWon)).isEqualTo("compensated");

        Funding fundingForRecommender = fundWallet(600);
        String recommenderWon = freebieReserve(fundingForRecommender, 100);
        client().post().uri("/api/finance/reservations/" + recommenderWon + "/reconcile")
                .header(H, signService(fundingForRecommender.org, "marketplace"))
                .contentType(JSON)
                .bodyValue(Map.of(
                        "organizationId", fundingForRecommender.org, "finalDecision", "for_recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("refunded");
        assertThat(escrowStatus(recommenderWon)).isEqualTo("refunded");
        assertThat(walletBalance(fundingForRecommender.recommender)).isEqualTo(600L);

        // 重放同一判决 → verified（幂等）
        client().post().uri("/api/finance/reservations/" + merchantWon + "/reconcile")
                .header(H, signService(fundingForMerchant.org, "marketplace"))
                .contentType(JSON)
                .bodyValue(Map.of("organizationId", fundingForMerchant.org, "finalDecision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.outcome").isEqualTo("verified");
        assertJournalsBalanced();
    }

    // ---------- helpers ----------

    private record Funding(String merchant, String org, String recommender) {}

    /** 经真实 bounty 链路给推荐官钱包入账（reserve 带 payee + capture 分账），保持账本背书。 */
    private Funding fundWallet(long amount) {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String fundingRef = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, amount + 400);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header(H, signService(org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", fundingRef, "amountCents", amount, "payeeAccountId", recommender))
                .exchange().expectStatus().isCreated();
        client().post().uri("/api/finance/reservations/" + fundingRef + "/capture")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        assertThat(walletBalance(recommender)).isEqualTo(amount);
        return new Funding(merchant, org, recommender);
    }

    private String freebieReserve(Funding funding, long amount) {
        String ref = "eng-" + UUID.randomUUID();
        client().post().uri("/internal/freebie/reserve")
                .header(H, signService(funding.org, "marketplace"))
                .contentType(JSON).bodyValue(Map.of(
                        "engagementRef", ref,
                        "recommenderAccountId", funding.recommender(),
                        "taskOwnerAccountId", funding.merchant(),
                        "organizationId", funding.org(),
                        "amountCents", amount))
                .exchange().expectStatus().isCreated();
        return ref;
    }

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private long walletBalance(String accountId) {
        Long balance = db.sql(
                        "SELECT balance_cents FROM recommender_wallet WHERE account_id = CAST(:a AS uuid)")
                .bind("a", accountId)
                .map(r -> r.get("balance_cents", Long.class)).one().block();
        return balance == null ? 0L : balance;
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(r -> r.get("balance_cents", Long.class)).one().block();
    }

    private String escrowStatus(String engagementRef) {
        return db.sql("SELECT status FROM freebie_escrow WHERE engagement_ref = :ref")
                .bind("ref", engagementRef)
                .map(r -> r.get("status", String.class)).one().block();
    }

    private long outboxCount(String eventType, String engagementRef) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'engagementRef' = :ref")
                .bind("et", eventType).bind("ref", engagementRef)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private String outboxPayloadField(String engagementRef, String eventType, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'engagementRef' = :ref")
                .bind("et", eventType).bind("ref", engagementRef)
                .map(r -> r.get("v", String.class)).one().block();
    }

    /** 断言 ledger 中每条 journal 借贷合计为零（HLD §6.4，镜像 LedgerProjectionIT）。 */
    private void assertJournalsBalanced() {
        Long unbalanced = db.sql("""
                        SELECT COUNT(*)::bigint AS c FROM (
                            SELECT p.journal_id,
                                   SUM(CASE p.direction WHEN 'DEBIT' THEN p.amount_cents ELSE -p.amount_cents END) AS net
                            FROM posting p GROUP BY p.journal_id
                        ) s WHERE s.net <> 0
                        """)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();
        assertThat(unbalanced).as("借贷不平衡的 journal 数").isZero();
    }
}
