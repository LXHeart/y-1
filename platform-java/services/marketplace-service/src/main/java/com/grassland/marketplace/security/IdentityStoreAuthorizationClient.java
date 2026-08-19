package com.grassland.marketplace.security;

import com.grassland.http.ManagedWebClientFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Calls Identity's authoritative store-resource authorization endpoint. */
@Component
public class IdentityStoreAuthorizationClient {

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public IdentityStoreAuthorizationClient(
            ServiceAssertionIssuer issuer,
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
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-identity"))
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

    public Mono<List<NearbyStore>> nearby(double latitude, double longitude, double radiusKm) {
        return webClient.get()
                .uri(builder -> builder.path("/internal/identity/stores/nearby")
                        .queryParam("latitude", latitude).queryParam("longitude", longitude)
                        .queryParam("radiusKm", radiusKm).build())
                .header(headerName, issuer.issueForOrg(null, "grassland-identity"))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.bodyToMono(NearbyEnvelope.class)
                                .map(envelope -> envelope.data() == null ? List.of() : envelope.data());
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(error -> Mono.error(mapError(status, error)));
                });
    }

    /**
     * 任务书 #24：批量拉门店公开资料白名单（feed enrichment / storeBranding 快照用）。
     * 一次拉整页 storeId，不要逐行调；空入参直接回空不发请求。
     */
    public Mono<List<StorePublicProfile>> publicProfiles(java.util.Collection<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return webClient.post()
                .uri("/internal/identity/stores/public-profiles")
                .header(headerName, issuer.issueForOrg(null, "grassland-identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", java.util.List.copyOf(storeIds)))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.bodyToMono(PublicProfileEnvelope.class)
                                .map(envelope -> envelope.data() == null ? List.of() : envelope.data());
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(error -> Mono.error(new IllegalStateException(
                                    "identity store public profiles failed: HTTP " + status + ": " + error)));
                });
    }

    public record Authorization(
            boolean authorized, String accountId, String organizationId, String storeId,
            String role, String scope, String permissionTier) {}

    public record NearbyStore(String storeId, double latitude, double longitude, double distanceKm) {}

    /** identity 公开白名单回包（与 identity {@code StorePublicProfile} 字段对齐）。 */
    public record StorePublicProfile(
            String storeId, String storeName, String address, String phone, String businessHours,
            String description, List<String> categories, List<String> signatureItems,
            String priceRange, Integer averageSpendCents, String visitNotes,
            List<String> sellingPoints, String brandTone, List<String> mustEmphasize,
            List<String> forbiddenPhrases, List<String> allowedTags) {}

    private record Envelope(boolean success, Authorization data) {}
    private record NearbyEnvelope(boolean success, List<NearbyStore> data) {}
    private record PublicProfileEnvelope(boolean success, List<StorePublicProfile> data) {}

    private static RuntimeException mapError(int status, String body) {
        if (status == 400 || status == 403 || status == 404) {
            String message = status == 404 ? "门店不存在" : status == 403 ? "门店权限不足" : "门店授权参数无效";
            return new MarketplaceException(status, message);
        }
        return new IllegalStateException("identity store authorization failed: HTTP " + status + ": " + body);
    }
}
