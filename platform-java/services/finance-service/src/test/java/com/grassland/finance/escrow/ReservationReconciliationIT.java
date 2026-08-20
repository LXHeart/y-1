package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReservationReconciliationIT extends FinanceItSupport {

    @Test
    void recommenderDecisionCapturesReservedAndThenVerifiesIdempotently() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);

        reconcile(org, ref, "for_recommender")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("captured")
                .jsonPath("$.data.reservation.status").isEqualTo("captured");

        reconcile(org, ref, "for_recommender")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("verified")
                .jsonPath("$.data.reason").isEqualTo("already_captured");
        assertThat(balanceOf(org)).isEqualTo(400L);
        assertThat(outboxCount("FundsCaptured", org)).isEqualTo(1);
    }

    @Test
    void merchantDecisionReleasesReservedAndVerifiesReleased() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);

        reconcile(org, ref, "for_merchant")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("released")
                .jsonPath("$.data.reservation.status").isEqualTo("released");

        reconcile(org, ref, "for_merchant")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("verified")
                .jsonPath("$.data.reason").isEqualTo("already_refunded");
        assertThat(balanceOf(org)).isEqualTo(1_000L);
        assertThat(outboxCount("FundsReleased", org)).isEqualTo(1);
    }

    @Test
    void merchantDecisionReversesCapturedReservation() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);
        capture(merchant, org, ref);

        reconcile(org, ref, "for_merchant")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("refunded")
                .jsonPath("$.data.reservation.status").isEqualTo("refunded");
        assertThat(balanceOf(org)).isEqualTo(1_000L);
        assertThat(outboxCount("FundsReversed", org)).isEqualTo(1);
    }

    @Test
    void recommenderDecisionReportsConflictForReleasedReservation() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);
        release(merchant, org, ref);

        reconcile(org, ref, "for_recommender")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("conflict")
                .jsonPath("$.data.reason").isEqualTo("released_but_recommender_won")
                .jsonPath("$.data.reservation.status").isEqualTo("released");
    }

    /** 任务书 #46 组合模式：同 engagement 两腿并存，reconcile 对两腿各自落定（主结果取 bounty 腿）。 */
    @Test
    void combinedLegsReconcileBothFundsReservationAndFreebieEscrow() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);
        // freebie 腿：推荐官钱包预付 100（先经真实资金流给推荐官充值）
        fundWallet(merchant, org, recommender, 600);
        client().post().uri("/internal/freebie/reserve")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "recommenderAccountId", recommender,
                        "taskOwnerAccountId", merchant, "organizationId", org, "amountCents", 100))
                .exchange().expectStatus().isCreated();

        reconcile(org, ref, "for_merchant")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("repaired")
                .jsonPath("$.data.reason").isEqualTo("released");

        // bounty 腿 released（返商家），freebie 腿 compensated（补商家 org）
        assertThat(reservationStatus(ref)).isEqualTo("released");
        assertThat(escrowStatusOf(ref)).isEqualTo("compensated");
    }

    @Test
    void missingReservationIsExplicitAndDoesNotReturn404() {
        String org = UUID.randomUUID().toString();

        reconcile(org, "eng-missing-" + UUID.randomUUID(), "for_merchant")
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.outcome").isEqualTo("missing")
                .jsonPath("$.data.reason").isEqualTo("reservation_missing")
                .jsonPath("$.data.reservation").doesNotExist();
    }

    @Test
    void reconciliationRequiresMatchingMarketplaceServiceAssertion() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        credit(merchant, org, 1_000);
        reserve(merchant, org, ref, 600);

        client().post().uri("/api/finance/reservations/" + ref + "/reconcile")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "finalDecision", "for_merchant"))
                .exchange().expectStatus().isForbidden();

        client().post().uri("/api/finance/reservations/" + ref + "/reconcile")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "finalDecision", "for_merchant"))
                .exchange().expectStatus().isForbidden();

        client().post().uri("/api/finance/reservations/" + ref + "/reconcile")
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "finalDecision", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void reconciliationRejectsUnknownDecision() {
        String org = UUID.randomUUID().toString();

        reconcile(org, "eng-" + UUID.randomUUID(), "draw")
                .expectStatus().isBadRequest();
    }

    private WebTestClient.ResponseSpec reconcile(String org, String ref, String decision) {
        return client().post().uri("/api/finance/reservations/" + ref + "/reconcile")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "finalDecision", decision))
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
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private void reserve(String merchant, String org, String ref, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", amount))
                .exchange().expectStatus().isCreated();
    }

    private void capture(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }

    private void release(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/release")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }

    /** 走真实资金流给推荐官钱包充值：商家预留→capture 分账入推荐官（镜像 FreebieEscrowControllerIT）。 */
    private void fundWallet(String merchant, String org, String recommender, long amount) {
        String fundingRef = "eng-" + UUID.randomUUID();
        credit(merchant, org, amount + 400);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", fundingRef, "amountCents", amount,
                        "payeeAccountId", recommender))
                .exchange().expectStatus().isCreated();
        client().post().uri("/api/finance/reservations/" + fundingRef + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }

    private String reservationStatus(String ref) {
        return db.sql("SELECT status FROM funds_reservation WHERE engagement_ref = :ref")
                .bind("ref", ref).map(row -> row.get("status", String.class)).one().block();
    }

    private String escrowStatusOf(String ref) {
        return db.sql("SELECT status FROM freebie_escrow WHERE engagement_ref = :ref")
                .bind("ref", ref).map(row -> row.get("status", String.class)).one().block();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(row -> row.get("balance_cents", Long.class)).one().block();
    }

    private long outboxCount(String eventType, String org) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM finance_outbox"
                        + " WHERE event_type = :eventType AND payload->>'organizationId' = :org")
                .bind("eventType", eventType)
                .bind("org", org)
                .map(row -> row.get("c", Long.class)).one().block();
    }
}
