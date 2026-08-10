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
    }
}
