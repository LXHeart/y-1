package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CommissionBonusIT extends FinanceItSupport {

    @Test
    void marketplaceBonusIsFrozenCapturedAndReversedWithoutChargingMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "bonus-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);

        reserveAsMarketplace(org, ref, recommender, 1_000, 1_000)
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.data.amountCents").isEqualTo(1_000)
                .jsonPath("$.data.commissionBonusBps").isEqualTo(1_000)
                .jsonPath("$.data.commissionBonusCents").isEqualTo(100)
                .jsonPath("$.data.settlementCommissionBonusCents").isEqualTo(0);

        // 完整 scope 相同才是幂等重试，返回第一次冻结值且商家只扣一次原赏金。
        reserveAsMarketplace(org, ref, recommender, 1_000, 1_000)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.commissionBonusBps").isEqualTo(1_000)
                .jsonPath("$.data.commissionBonusCents").isEqualTo(100);
        assertThat(merchantBalance(org)).isZero();

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.payoutCents").isEqualTo(1_100)
                .jsonPath("$.data.basePayoutCents").isEqualTo(1_000)
                .jsonPath("$.data.platformFeeCents").isEqualTo(0)
                .jsonPath("$.data.commissionBonusCents").isEqualTo(100);

        assertThat(walletBalance(recommender)).isEqualTo(1_100);
        assertThat(postingAmount(ref, "CAPTURE", "SUBSIDY_EXPENSE", "DEBIT")).isEqualTo(100);
        assertThat(outboxLong("SplitCompleted", ref, "commissionBonusCents")).isEqualTo(100);
        assertThat(outboxLong("FundsCaptured", ref, "basePayoutCents")).isEqualTo(1_000);

        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("refunded")
                .jsonPath("$.data.commissionBonusCents").isEqualTo(100);

        assertThat(walletBalance(recommender)).isZero();
        assertThat(merchantBalance(org)).isEqualTo(1_000);
        assertThat(postingAmount(ref, "REVERSE", "SUBSIDY_EXPENSE", "CREDIT")).isEqualTo(100);
        assertThat(outboxLong("FundsReversed", ref, "payoutCents")).isEqualTo(1_100);
    }

    @Test
    void idempotentReservationRejectsAnyFrozenScopeChangeWithoutChargingAgain() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "scope-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 2_000);

        reserveAsMarketplace(org, ref, recommender, 1_000, 1_000)
                .expectStatus().isCreated();

        reserveAsMarketplace(org, ref, recommender, 900, 1_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("engagementRef 预留范围冲突");
        reserveAsMarketplace(org, ref, UUID.randomUUID().toString(), 1_000, 1_000)
                .expectStatus().isEqualTo(422);
        reserveAsMarketplace(org, ref, recommender, 1_000, 300)
                .expectStatus().isEqualTo(422);

        assertThat(merchantBalance(org)).isEqualTo(1_000);
        assertThat(outboxLong("FundsReserved", ref, "amountCents")).isEqualTo(1_000);
        assertThat(outboxLong("FundsReserved", ref, "commissionBonusBps")).isEqualTo(1_000);
    }

    @Test
    void merchantCannotSelfGrantSubsidyAndInvalidBpsIsRejected() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1_000);

        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "merchant-bonus-" + UUID.randomUUID(),
                        "amountCents", 500, "payeeAccountId", UUID.randomUUID().toString(),
                        "commissionBonusBps", 1_000))
                .exchange().expectStatus().isForbidden();

        reserveAsMarketplace(org, "bad-bps-" + UUID.randomUUID(), UUID.randomUUID().toString(), 500, 10_001)
                .expectStatus().isBadRequest();
        assertThat(merchantBalance(org)).isEqualTo(1_000);
    }

    @Test
    void merchantCannotChooseReservationPayeeEvenWithoutBonus() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1_000);

        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", "merchant-payee-" + UUID.randomUUID(),
                        "amountCents", 500, "payeeAccountId", UUID.randomUUID().toString()))
                .exchange().expectStatus().isForbidden();

        assertThat(merchantBalance(org)).isEqualTo(1_000);
    }

    @Test
    void capturesSelectedLadderAmountAndReturnsUnusedReserveToMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "ladder-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 2_000);

        reserveAsMarketplace(org, ref, recommender, 1_500, 0)
                .expectStatus().isCreated();

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("settlementAmountCents", 900))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.amountCents").isEqualTo(1_500)
                .jsonPath("$.data.settlementAmountCents").isEqualTo(900)
                .jsonPath("$.data.payoutCents").isEqualTo(900);

        assertThat(merchantBalance(org)).isEqualTo(1_100);
        assertThat(walletBalance(recommender)).isEqualTo(900);
        assertThat(outboxLong("FundsPartiallyReleased", ref, "settlementAmountCents")).isEqualTo(900);
    }

    @Test
    void rejectsLadderCaptureAboveReservedAmountWithoutChangingBalances() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "ladder-over-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserveAsMarketplace(org, ref, recommender, 1_000, 0).expectStatus().isCreated();

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("settlementAmountCents", 1_001))
                .exchange().expectStatus().isBadRequest();

        assertThat(merchantBalance(org)).isZero();
        assertThat(walletBalance(recommender)).isZero();
    }

    @Test
    void merchantCannotChooseTheLadderSettlementAmount() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "ladder-merchant-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserveAsMarketplace(org, ref, recommender, 1_000, 0).expectStatus().isCreated();

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("settlementAmountCents", 1))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void trustCannotChooseTheLadderSettlementAmount() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "ladder-trust-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserveAsMarketplace(org, ref, recommender, 1_000, 0).expectStatus().isCreated();

        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("settlementAmountCents", 1))
                .exchange().expectStatus().isForbidden();

        assertThat(merchantBalance(org)).isZero();
        assertThat(walletBalance(recommender)).isZero();
    }

    @Test
    void idempotencyKeyCannotExposeReservationAcrossOrganizations() {
        String merchantA = UUID.randomUUID().toString();
        String merchantB = UUID.randomUUID().toString();
        String orgA = UUID.randomUUID().toString();
        String orgB = UUID.randomUUID().toString();
        String ref = "shared-" + UUID.randomUUID();
        provision(merchantA, orgA);
        provision(merchantB, orgB);
        credit(merchantA, orgA, 500);
        credit(merchantB, orgB, 500);

        reserveAsMarketplace(orgA, ref, UUID.randomUUID().toString(), 500, 300)
                .expectStatus().isCreated();
        reserveAsMarketplace(orgB, ref, UUID.randomUUID().toString(), 500, 1_000)
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.error").isEqualTo("engagementRef 预留范围冲突")
                .jsonPath("$.data").doesNotExist();

        assertThat(merchantBalance(orgB)).isEqualTo(500);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec reserveAsMarketplace(
            String org, String ref, String payee, long amount, int bonusBps) {
        return client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", amount,
                        "payeeAccountId", payee, "commissionBonusBps", bonusBps))
                .exchange();
    }

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private long merchantBalance(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org).map(row -> row.get("balance_cents", Long.class)).one().block();
    }

    private long walletBalance(String accountId) {
        Long balance = db.sql("SELECT balance_cents FROM recommender_wallet WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId).map(row -> row.get("balance_cents", Long.class)).one().block();
        return balance == null ? 0 : balance;
    }

    private long postingAmount(String ref, String journalType, String accountType, String direction) {
        Long amount = db.sql("""
                SELECT p.amount_cents
                FROM posting p JOIN journal j ON j.id = p.journal_id
                WHERE j.engagement_ref = :ref AND j.journal_type = :journalType
                  AND p.account_type = :accountType AND p.direction = :direction
                """)
                .bind("ref", ref).bind("journalType", journalType)
                .bind("accountType", accountType).bind("direction", direction)
                .map(row -> row.get("amount_cents", Long.class)).one().block();
        return amount == null ? 0 : amount;
    }

    private long outboxLong(String eventType, String ref, String field) {
        String value = db.sql("""
                SELECT payload ->> :field AS value
                FROM finance_outbox
                WHERE event_type = :eventType AND payload ->> 'engagementRef' = :ref
                ORDER BY created_at DESC LIMIT 1
                """)
                .bind("field", field).bind("eventType", eventType).bind("ref", ref)
                .map(row -> row.get("value", String.class)).one().block();
        return Long.parseLong(value);
    }
}
