package com.grassland.intelligence.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Calls Identity's authoritative organization-role authorization endpoint
 * ({@code /internal/identity/organization-authorizations/check})——商家素材库 org admin/member
 * 粒度鉴权（PRD §4.8 后续缺口）用，镜像 {@link IdentityStoreAuthorizationClient} 的门店边界。
 */
@Component
public class IdentityOrgAuthorizationClient {

    private final WebClient webClient;
    private final IntelligenceServiceAssertionIssuer issuer;
    private final String headerName;

    public IdentityOrgAuthorizationClient(
            IntelligenceServiceAssertionIssuer issuer,
            @Value("${identity.service.base-url:http://identity-service:8082}") String baseUrl,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** 要求账号在 org 内角色不低于 minimumRole（member/admin/owner）；不足/非成员 → 403，org 不存在 → 404。 */
    public Mono<Void> require(String accountId, String organizationId, String minimumRole) {
        return authorize(accountId, organizationId, minimumRole).then();
    }

    public Mono<Authorization> authorize(String accountId, String organizationId, String minimumRole) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("organizationId", organizationId);
        body.put("minimumRole", minimumRole);
        return webClient.post()
                .uri("/internal/identity/organization-authorizations/check")
                .header(headerName, issuer.issueService("grassland-identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200) {
                        return response.bodyToMono(Envelope.class)
                                .flatMap(envelope -> envelope.data() == null
                                        ? Mono.error(new IllegalStateException(
                                                "identity organization authorization response is missing data"))
                                        : Mono.just(envelope.data()));
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(error -> Mono.error(mapError(status, error)));
                });
    }

    public record Authorization(boolean authorized, String accountId, String organizationId, String role) {}

    private record Envelope(boolean success, Authorization data) {}

    private static RuntimeException mapError(int status, String body) {
        if (status == 400 || status == 403 || status == 404) {
            String message = status == 404 ? "组织不存在"
                    : status == 403 ? "组织权限不足" : "组织授权参数无效";
            return new IntelligenceException(status, message);
        }
        return new IllegalStateException("identity organization authorization failed: HTTP " + status + ": " + body);
    }
}
