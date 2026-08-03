package com.grassland.marketplace.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * trust 争议查询出站 HTTP 客户端（草场 Epic 6 Slice 6A）。marketplace 结算窗口到期查 trust 开放争议。
 * 镜像 {@link FinanceEscrowClient}：WebClient + 每请求现签 marketplace 服务断言（{@link ServiceAssertionIssuer}）。
 *
 * <p>{@code hasOpenDispute} → GET /api/trust/engagements/{ref}/open-dispute，映射 200→true、404→false、其余→抛异常
 * （Temporal 重试）。orgId 用于现签服务断言（trust org 级授权）。
 */
@Component
public class TrustDisputeClient {

    private static final Logger log = LoggerFactory.getLogger(TrustDisputeClient.class);

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final ObjectMapper objectMapper;
    private final String headerName;

    public TrustDisputeClient(ServiceAssertionIssuer issuer,
                              ObjectMapper objectMapper,
                              @Value("${trust.service.base-url:http://trust-service:8085}") String baseUrl,
                              @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.objectMapper = objectMapper;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public Mono<Boolean> hasOpenDispute(String orgId, String engagementRef) {
        return webClient.get()
                .uri("/api/trust/engagements/{ref}/open-dispute", engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-trust"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("trust open-dispute HTTP {} org={} ref={}", code, orgId, engagementRef);
                    if (code == 200) {
                        return Mono.just(true);
                    }
                    if (code == 404) {
                        return Mono.just(false);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Boolean>error(
                                    new IllegalStateException("trust open-dispute failed: HTTP " + code + ": " + b)));
                });
    }

    /**
     * D-03 §2：marketplace 代商家开 merchant_rejection 争议。trust 按 engagementRef 活跃争议槽幂等（201/200）。
     * 返回 canonical disputeId；其它响应抛异常，controller 不写 contest 状态。
     */
    public Mono<String> openMerchantRejection(String orgId, String engagementRef,
                                              String merchantAccountId, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engagementRef", engagementRef);
        body.put("kind", "merchant_rejection");
        body.put("openedByAccountId", merchantAccountId);
        body.put("organizationId", orgId);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        return webClient.post()
                .uri("/api/trust/disputes")
                .header(headerName, issuer.issueForOrg(orgId, "grassland-trust"))
                .bodyValue(body)
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (code == 200 || code == 201) {
                        return resp.bodyToMono(String.class)
                                .map(this::disputeId)
                                .filter(id -> !id.isBlank())
                                .switchIfEmpty(Mono.error(new IllegalStateException(
                                        "trust merchant-rejection response missing dispute id")));
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<String>error(new IllegalStateException(
                                    "trust merchant-rejection failed: HTTP " + code + ": " + b)));
                });
    }

    private String disputeId(String body) {
        try {
            return objectMapper.readTree(body).path("data").path("id").asText();
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("trust merchant-rejection response is not valid JSON", failure);
        }
    }

    /** D-03 客服 SLA 超时：仅 merchant_rejection；trust 幂等终局为 for_recommender。 */
    public Mono<Void> autoFinalizeMerchantRejection(String orgId, String disputeId) {
        return webClient.post()
                .uri("/api/trust/internal/disputes/{id}/auto-finalize", disputeId)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-trust"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (code == 200) {
                        return Mono.<Void>empty();
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Void>error(new IllegalStateException(
                                    "trust merchant-rejection auto-finalize failed: HTTP " + code + ": " + b)));
                });
    }
}
