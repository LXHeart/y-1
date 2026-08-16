package com.grassland.finance.commerce;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import com.grassland.finance.ledger.LedgerAccount;
import com.grassland.finance.ledger.LedgerRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Finance payment/refund/split idempotency and balanced-ledger integration. */
class ConsumerPaymentControllerIT extends FinanceItSupport {

    @Autowired
    LedgerRepository ledger;

    @Test
    void paymentAndSplitAreIdempotentAndCreditThreeDestinations() {
        String order = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> payment = Map.of(
                "orderRef", order, "consumerAccountId", consumer, "organizationId", org,
                "amountCents", 10_000, "operationId", "commerce-payment:" + order);

        for (int i = 0; i < 2; i++) {
            client().post().uri("/internal/commerce/payments")
                    .header("X-Grassland-Identity", signService(org, "marketplace"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(payment)
                    .exchange().expectStatus().isCreated()
                    .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");
        }
        assertThat(ledger.sumBalance(LedgerAccount.Type.CONSUMER_ESCROW, order).block())
                .isEqualTo(10_000L);

        Map<String, Object> split = Map.of(
                "organizationId", org, "totalAmountCents", 10_000,
                "recommenderAccountId", recommender, "recommenderAmountCents", 1_000,
                "merchantAmountCents", 8_500, "platformFeeCents", 500,
                "operationId", "commerce-split:" + order);
        for (int i = 0; i < 2; i++) {
            client().post().uri("/internal/commerce/payments/" + order + "/split")
                    .header("X-Grassland-Identity", signService(org, "marketplace"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(split)
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.data.status").isEqualTo("completed");
        }
        assertThat(ledger.sumBalance(LedgerAccount.Type.CONSUMER_ESCROW, order).block()).isZero();
        assertThat(ledger.sumBalance(LedgerAccount.Type.WALLET, recommender).block()).isEqualTo(1_000L);
        assertThat(ledger.sumBalance(LedgerAccount.Type.ESCROW, org).block()).isEqualTo(8_500L);
        assertThat(providerOperationCount("commerce-payment:" + order, "payment")).isEqualTo(1);
        assertThat(providerOperationCount("commerce-split:" + order, "split")).isEqualTo(1);
    }

    @Test
    void unredeemedRefundIsIdempotentAndCannotBeCalledByUserPrincipal() {
        String order = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/internal/commerce/payments")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "orderRef", order, "consumerAccountId", consumer, "organizationId", org,
                        "amountCents", 6600, "operationId", "commerce-payment:" + order))
                .exchange().expectStatus().isCreated();

        Map<String, Object> refund = Map.of(
                "organizationId", org, "amountCents", 6600,
                "operationId", "commerce-refund:" + order, "reason", "expiry");
        client().post().uri("/internal/commerce/payments/" + order + "/refund")
                .header("X-Grassland-Identity", sign(consumer, null, org, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(refund)
                .exchange().expectStatus().isForbidden();
        for (int i = 0; i < 2; i++) {
            client().post().uri("/internal/commerce/payments/" + order + "/refund")
                    .header("X-Grassland-Identity", signService(org, "marketplace"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(refund)
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");
        }
        assertThat(ledger.sumBalance(LedgerAccount.Type.CONSUMER_ESCROW, order).block()).isZero();
        assertThat(providerOperationCount("commerce-refund:" + order, "refund")).isEqualTo(1);
    }

    @Test
    void partialRefundsAccumulateAndRejectOverRefund() {
        String order = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/internal/commerce/payments")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "orderRef", order, "consumerAccountId", consumer, "organizationId", org,
                        "amountCents", 10000, "operationId", "commerce-payment:" + order))
                .exchange().expectStatus().isCreated();

        postRefund(order, org, 2500, "commerce-refund:" + order + ":a").expectStatus().isOk()
                .expectBody().jsonPath("$.data.amountCents").isEqualTo(2500);
        postRefund(order, org, 3500, "commerce-refund:" + order + ":b").expectStatus().isOk();
        postRefund(order, org, 5000, "commerce-refund:" + order + ":b").expectStatus().isOk();
        postRefund(order, org, 5000, "commerce-refund:" + order + ":c").expectStatus().isEqualTo(409);

        // 6000/10000 已退，剩余托管资金仍在 escrow（待核销分账或后续退款）。
        assertThat(ledger.sumBalance(LedgerAccount.Type.CONSUMER_ESCROW, order).block()).isEqualTo(4000L);
        assertThat(providerOperationCount("commerce-refund:" + order + ":a", "refund")).isEqualTo(1);
        assertThat(providerOperationCount("commerce-refund:" + order + ":b", "refund")).isEqualTo(1);
    }

    @Test
    void multiRecommenderSplitCanBeReversedByAfterSalesRefund() {
        String order = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/internal/commerce/payments")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "orderRef", order, "consumerAccountId", consumer, "organizationId", org,
                        "amountCents", 10000, "operationId", "commerce-payment:" + order))
                .exchange().expectStatus().isCreated();
        Map<String, Object> split = Map.of(
                "organizationId", org, "totalAmountCents", 10000,
                "recommenderAmountCents", 2000, "merchantAmountCents", 7500, "platformFeeCents", 500,
                "operationId", "commerce-split:" + order,
                "allocations", java.util.List.of(Map.of("recommenderAccountId", first, "amountCents", 1200),
                        Map.of("recommenderAccountId", second, "amountCents", 800)));
        client().post().uri("/internal/commerce/payments/" + order + "/split")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(split).exchange().expectStatus().isOk();
        postRefund(order, org, 4000, "commerce-dispute-refund:" + order)
                .expectStatus().isOk();
        assertThat(ledger.sumBalance(LedgerAccount.Type.WALLET, first).block()).isEqualTo(720L);
        assertThat(ledger.sumBalance(LedgerAccount.Type.WALLET, second).block()).isEqualTo(480L);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postRefund(
            String order, String org, long amount, String operationId) {
        return client().post().uri("/internal/commerce/payments/" + order + "/refund")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of(
                        "organizationId", org, "amountCents", amount,
                        "operationId", operationId, "reason", "customer_request")).exchange();
    }

    private long providerOperationCount(String operationId, String operationType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_provider_operation"
                        + " WHERE operation_id = :operationId AND operation_type = :operationType")
                .bind("operationId", operationId).bind("operationType", operationType)
                .map(row -> row.get("c", Integer.class)).one().block().longValue();
    }
}
