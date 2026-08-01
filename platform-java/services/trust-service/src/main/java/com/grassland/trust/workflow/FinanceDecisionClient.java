package com.grassland.trust.workflow;

import com.grassland.trust.security.TrustServiceAssertionIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * finance escrow 出站客户端（草场 Epic 6 Slice 6C Phase D / HLD 5.4、11.1）。trust 审判终局调 finance 的
 * release/capture/reverse（判决×reservation 状态矩阵分派）。WebClient 已在 spring-webflux classpath（无新依赖）。
 *
 * <p>每方法返回 {@code Mono<Boolean>}：{@code true}=动作已应用（HTTP 200），{@code false}=不适用（404 不存在 / 409 非目标态，
 * 幂等安全），其余 → 抛 {@link DecisionException}（Temporal 重试）。每请求由 {@link TrustServiceAssertionIssuer}
 * 现签 {@code X-Grassland-Identity} 服务断言（principal=trust，带 org）。镜像 marketplace {@code FinanceEscrowClient}。
 */
@Component
public class FinanceDecisionClient {

    private static final Logger log = LoggerFactory.getLogger(FinanceDecisionClient.class);

    private final WebClient webClient;
    private final TrustServiceAssertionIssuer issuer;
    private final String headerName;

    public FinanceDecisionClient(TrustServiceAssertionIssuer issuer,
                                 @Value("${finance.service.base-url:http://finance-service:8084}") String baseUrl,
                                 @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /** release：reserved→released。true=已释放，false=非 reserved/不存在（幂等）。 */
    public Mono<Boolean> releaseIfReserved(String orgId, String engagementRef) {
        return post("/api/finance/reservations/{ref}/release", orgId, engagementRef, "release");
    }

    /** capture：reserved→captured。true=已捕获，false=非 reserved/不存在（幂等）。 */
    public Mono<Boolean> captureIfReserved(String orgId, String engagementRef) {
        return post("/api/finance/reservations/{ref}/capture", orgId, engagementRef, "capture");
    }

    /** reverse：captured→refunded（D-06）。true=已冲正，false=非 captured/不存在（幂等）。 */
    public Mono<Boolean> reverseIfCaptured(String orgId, String engagementRef) {
        return post("/api/finance/reservations/{ref}/reverse", orgId, engagementRef, "reverse");
    }

    private Mono<Boolean> post(String uri, String orgId, String engagementRef, String op) {
        return webClient.post()
                .uri(uri, engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("{} HTTP {} org={} ref={}", op, code, orgId, engagementRef);
                    if (code == 200) {
                        return Mono.just(true);
                    }
                    if (code == 404 || code == 409) {
                        return Mono.just(false);  // 不存在 / 非目标态 → 幂等不适用
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Boolean>error(new DecisionException(op + " failed: HTTP " + code + ": " + b)));
                });
    }

    /** finance 调用非幂等失败（Temporal activity 重试）。 */
    public static final class DecisionException extends RuntimeException {
        public DecisionException(String message) {
            super(message);
        }
    }
}
