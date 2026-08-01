package com.grassland.marketplace.workflow;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
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
    private final String headerName;

    public TrustDisputeClient(ServiceAssertionIssuer issuer,
                              @Value("${trust.service.base-url:http://trust-service:8085}") String baseUrl,
                              @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
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
}
