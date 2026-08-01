package com.grassland.marketplace.workflow;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * finance escrow 出站 HTTP 客户端（草场 Epic 4 Slice 4F / HLD 5.4、11.1）。marketplace Saga 调 finance 的
 * ReserveFunds / ReleaseFunds。WebClient 已在 spring-webflux classpath（无新依赖）。
 *
 * <ul>
 *   <li>{@code reserve(orgId, engagementRef, amountCents)} → POST /api/finance/accounts/{orgId}/reservations，
 *       映射 2xx→Reserved、409→InsufficientFunds（正常返回值，不重试）、其余→抛异常（Temporal 重试）。</li>
 *   <li>{@code release(orgId, engagementRef)} → POST /api/finance/reservations/{ref}/release，
 *       2xx/404/409 → 成功（幂等：已释放/不存在视作成功），其余→抛异常。</li>
 * </ul>
 *
 * <p>每请求由 {@link ServiceAssertionIssuer} 现签 {@code X-Grassland-Identity} 服务断言（带 org，principal=marketplace）。
 * org 用于 finance 的 org 级授权自查（release 的 org 校验在 finance 加载 reservation 后做，断言需带正确 org）。
 */
@Component
public class FinanceEscrowClient {

    private static final Logger log = LoggerFactory.getLogger(FinanceEscrowClient.class);

    private final WebClient webClient;
    private final ServiceAssertionIssuer issuer;
    private final String headerName;

    public FinanceEscrowClient(ServiceAssertionIssuer issuer,
                               @Value("${finance.service.base-url:http://finance-service:8084}") String baseUrl,
                               @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.issuer = issuer;
        this.headerName = headerName;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 预留资金。{@code payeeAccountId} = 该 engagement 的报名推荐官——finance 只认 engagementRef，
     * 不知道钱将来该付给谁，故由 marketplace 在预留时一并告知，capture 时按它分账。
     */
    public Mono<ReserveResult> reserve(String orgId, String engagementRef, long amountCents, String payeeAccountId) {
        return webClient.post()
                .uri("/api/finance/accounts/{orgId}/reservations", orgId)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", engagementRef, "amountCents", amountCents,
                        "payeeAccountId", payeeAccountId))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("reserve HTTP {} org={} ref={}", code, orgId, engagementRef);
                    return switch (code) {
                        case 200, 201 -> Mono.just(ReserveResult.reserved(amountCents));
                        case 409 -> Mono.just(ReserveResult.insufficientFunds());
                        default -> resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(b -> Mono.<ReserveResult>error(
                                        new FinanceEscrowException("reserve failed: HTTP " + code + ": " + b)));
                    };
                });
    }

    public Mono<Void> release(String orgId, String engagementRef) {
        return webClient.post()
                .uri("/api/finance/reservations/{ref}/release", engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (code == 200 || code == 404 || code == 409) {
                        return Mono.<Void>empty();  // 成功 / 不存在 / 已释放 → 幂等成功
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Void>error(
                                    new FinanceEscrowException("release failed: HTTP " + code + ": " + b)));
                });
    }

    /** 捕获（结算确认，Slice 5A）：reserved→captured，无余额变动。镜像 {@link #release} 的状态映射。 */
    public Mono<Void> capture(String orgId, String engagementRef) {
        return webClient.post()
                .uri("/api/finance/reservations/{ref}/capture", engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("capture HTTP {} org={} ref={}", code, orgId, engagementRef);
                    if (code == 200 || code == 404 || code == 409) {
                        return Mono.<Void>empty();  // 成功 / 不存在 / 已终态(captured 或 released) → 幂等成功
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Void>error(
                                    new FinanceEscrowException("capture failed: HTTP " + code + ": " + b)));
                });
    }
}
