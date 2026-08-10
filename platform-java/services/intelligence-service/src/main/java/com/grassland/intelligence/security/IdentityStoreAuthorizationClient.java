package com.grassland.intelligence.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Calls Identity's authoritative store-resource authorization endpoint. */
@Component
public class IdentityStoreAuthorizationClient {

    private final WebClient webClient;
    private final IntelligenceServiceAssertionIssuer issuer;
    private final String headerName;

    public IdentityStoreAuthorizationClient(
            IntelligenceServiceAssertionIssuer issuer,
            @Value("${identity.service.base-url:http://identity-service:8082}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Mono<Void> require(String accountId, String organizationId, String storeId, String minimumRole) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("organizationId", organizationId);
        if (storeId != null && !storeId.isBlank()) {
            body.put("storeId", storeId);
        }
        body.put("minimumRole", minimumRole);
        return webClient.post()
                .uri("/internal/identity/store-authorizations/check")
                .header(headerName, issuer.issueService("grassland-identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.releaseBody();
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(error -> Mono.error(mapError(status, error)));
                });
    }

    private static RuntimeException mapError(int status, String body) {
        if (status == 400 || status == 403 || status == 404) {
            String message = status == 404 ? "门店不存在" : status == 403 ? "门店权限不足" : "门店授权参数无效";
            return new IntelligenceException(status, message);
        }
        return new IllegalStateException("identity store authorization failed: HTTP " + status + ": " + body);
    }
}
