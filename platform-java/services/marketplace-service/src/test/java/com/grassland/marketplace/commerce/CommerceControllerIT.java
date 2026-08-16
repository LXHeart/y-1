package com.grassland.marketplace.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** End-to-end marketplace commerce lifecycle against PostgreSQL, with finance kept at its HTTP seam. */
class CommerceControllerIT extends MarketplaceItSupport {

    @MockitoBean
    FinanceCommerceClient finance;

    @BeforeEach
    void sandboxFinance() {
        when(finance.pay(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            return Mono.just("sandbox:payment:" + order.id());
        });
        when(finance.refund(any(Order.class), anyString())).thenReturn(Mono.empty());
        when(finance.split(any(Order.class))).thenReturn(Mono.empty());
        when(finance.split(any(Order.class), anyList())).thenReturn(Mono.empty());
    }

    @Test
    void packageRevisionDoesNotChangePaidOrderSnapshotAndRedemptionIsSingleUse() {
        String merchant = UUID.randomUUID().toString();
        String consumer = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        Map<String, Object> offer = createAndPublish(merchant, org, 1000, 3);
        Map<String, Object> order = createOrder(consumer, (String) offer.get("id"), recommender);
        assertThat(order.get("status")).isEqualTo("paid");
        assertThat(order.get("packageVersion")).isEqualTo(1);
        assertThat(order.get("priceCents")).isEqualTo(1000);
        assertThat(order.get("recommenderAmountCents")).isEqualTo(100);
        assertThat(order.get("platformFeeCents")).isEqualTo(50);

        Map<String, Object> revisedBody = offerBody(org, 2500, 8);
        client().put().uri("/api/v2/merchant/packages/" + offer.get("id"))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(revisedBody)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.priceCents").isEqualTo(2500);

        client().get().uri("/api/v2/orders/" + order.get("id"))
                .header("X-Grassland-Identity", sign(consumer, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.packageVersion").isEqualTo(1)
                .jsonPath("$.data.priceCents").isEqualTo(1000);

        String code = String.valueOf(order.get("redeemCode"));
        client().post().uri("/api/v2/merchant/redemptions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("redeemed");
        client().post().uri("/api/v2/merchant/redemptions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("code", code))
                .exchange().expectStatus().isEqualTo(409);

        client().post().uri("/api/v2/orders/" + order.get("id") + "/review")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("rating", 5, "comment", "很好"))
                .exchange().expectStatus().isCreated();
    }

    @Test
    void lastInventoryCanOnlyBeSoldOnceAndRefundRestoresItOnce() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> offer = createAndPublish(merchant, org, 8800, 1);

        Map<String, Object> first = createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
        client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("packageId", offer.get("id")))
                .exchange().expectStatus().isEqualTo(409);

        String firstConsumer = String.valueOf(first.get("consumerAccountId"));
        client().post().uri("/api/v2/orders/" + first.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(firstConsumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "change_of_mind"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("refunded");
        client().post().uri("/api/v2/orders/" + first.get("id") + "/refund")
                .header("X-Grassland-Identity", sign(firstConsumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "again"))
                .exchange().expectStatus().isEqualTo(409);

        client().get().uri("/api/v2/packages/" + offer.get("id"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.remainingStock").isEqualTo(1);
        createOrder(UUID.randomUUID().toString(), (String) offer.get("id"), null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAndPublish(
            String merchant, String org, long priceCents, int stock) {
        Map<String, Object> created = client().post().uri("/api/v2/merchant/packages")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(offerBody(org, priceCents, stock))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> offer = (Map<String, Object>) created.get("data");
        client().post().uri("/api/v2/merchant/packages/" + offer.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
        return offer;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createOrder(String consumer, String packageId, String recommender) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageId", packageId);
        if (recommender != null) body.put("recommenderAccountId", recommender);
        Map<String, Object> response = client().post().uri("/api/v2/orders")
                .header("X-Grassland-Identity", sign(consumer, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    private static Map<String, Object> offerBody(String org, long priceCents, int stock) {
        return Map.of(
                "organizationId", org, "title", "双人到店套餐", "description", "测试套餐",
                "priceCents", priceCents, "totalStock", stock, "validDaysAfterPurchase", 30,
                "recommenderShareBps", 1000, "platformFeeBps", 500, "policyVersion", "commerce-v1");
    }
}
