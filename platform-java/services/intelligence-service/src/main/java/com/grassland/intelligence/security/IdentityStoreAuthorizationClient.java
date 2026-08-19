package com.grassland.intelligence.security;

import com.grassland.http.ManagedWebClientFactory;

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
        this.webClient = ManagedWebClientFactory.create(IdentityStoreAuthorizationClient.class, baseUrl);
    }

    public Mono<Void> require(String accountId, String organizationId, String storeId, String minimumRole) {
        return authorize(accountId, organizationId, storeId, minimumRole).then();
    }

    public Mono<Authorization> authorize(
            String accountId, String organizationId, String storeId, String minimumRole) {
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
                        return response.bodyToMono(Envelope.class)
                                .flatMap(envelope -> envelope.data() == null
                                        ? Mono.error(new IllegalStateException(
                                                "identity store authorization response is missing data"))
                                        : Mono.just(envelope.data()));
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(error -> Mono.error(mapError(status, error)));
                });
    }

    public record Authorization(
            boolean authorized, String accountId, String organizationId, String storeId,
            String role, String scope, String permissionTier) {}

    private record Envelope(boolean success, Authorization data) {}

    private static RuntimeException mapError(int status, String body) {
        if (status == 400 || status == 403 || status == 404) {
            String message = status == 404 ? "门店不存在" : status == 403 ? "门店权限不足" : "门店授权参数无效";
            return new IntelligenceException(status, message);
        }
        return new IllegalStateException("identity store authorization failed: HTTP " + status + ": " + body);
    }
}
