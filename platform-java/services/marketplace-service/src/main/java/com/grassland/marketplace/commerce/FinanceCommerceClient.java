package com.grassland.marketplace.commerce;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Marketplace -> finance commerce client using a freshly signed service assertion per request. */
@Component
public class FinanceCommerceClient {

    private final WebClient client;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public FinanceCommerceClient(
            ServiceAssertionIssuer issuer,
            @Value("${finance.service.base-url:http://finance-service:8084}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.client = WebClient.builder().baseUrl(baseUrl).build();
        this.issuer = issuer;
        this.headerName = headerName;
    }

    public Mono<String> pay(CommerceModels.Order order) {
        Map<String, Object> body = Map.of(
                "orderRef", order.id(),
                "consumerAccountId", order.consumerAccountId(),
                "organizationId", order.organizationId(),
                "amountCents", order.priceCents(),
                "operationId", order.paymentOperationId());
        return post("/internal/commerce/payments", order.organizationId(), body)
                .map(envelope -> String.valueOf(envelope.data().get("providerRef")));
    }

    public Mono<Void> refund(CommerceModels.Order order, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", order.organizationId());
        body.put("amountCents", order.priceCents());
        body.put("operationId", order.refundOperationId());
        body.put("reason", reason);
        return post("/internal/commerce/payments/" + order.id() + "/refund",
                order.organizationId(), body).then();
    }

    public Mono<Void> split(CommerceModels.Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", order.organizationId());
        body.put("totalAmountCents", order.priceCents());
        if (order.recommenderAccountId() != null) {
            body.put("recommenderAccountId", order.recommenderAccountId());
        }
        body.put("recommenderAmountCents", order.recommenderAmountCents());
        body.put("merchantAmountCents", order.merchantAmountCents());
        body.put("platformFeeCents", order.platformFeeCents());
        body.put("operationId", order.splitOperationId());
        return post("/internal/commerce/payments/" + order.id() + "/split",
                order.organizationId(), body).then();
    }

    private Mono<Envelope> post(String path, String organizationId, Map<String, Object> body) {
        return client.post().uri(path)
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(Envelope.class)
                                .filter(Envelope::success)
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "finance commerce response is missing success data")));
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(text -> Mono.error(new IllegalStateException(
                                    "finance commerce failed: HTTP " + response.statusCode().value() + ": " + text)));
                });
    }

    @SuppressWarnings("rawtypes")
    private record Envelope(boolean success, Map data) {}
}
