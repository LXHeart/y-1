package com.grassland.marketplace.workflow;

import com.grassland.marketplace.security.ServiceAssertionIssuer;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * finance escrow 出站 HTTP 客户端（草场 Epic 4 Slice 4F / HLD 5.4、11.1）。marketplace Saga 调 finance 的
 * ReserveFunds / ReleaseFunds。WebClient 已在 spring-webflux classpath（无新依赖）。
 *
 * <ul>
 *   <li>{@code reserve(orgId, engagementRef, amountCents)} → POST /api/finance/accounts/{orgId}/reservations，
 *       仅完整匹配请求 scope 的 2xx→Reserved、明确余额不足的 409→InsufficientFunds；其余→抛异常（Temporal 重试）。</li>
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
    private static final int BASIS_POINTS = 10_000;
    private static final ParameterizedTypeReference<Envelope<ReservationData>> RESERVATION_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ErrorEnvelope> ERROR_TYPE =
            new ParameterizedTypeReference<>() {};

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
        return reserve(orgId, engagementRef, amountCents, payeeAccountId, 0);
    }

    public Mono<ReserveResult> reserve(String orgId, String engagementRef, long amountCents,
                                       String payeeAccountId, int commissionBonusBps) {
        return webClient.post()
                .uri("/api/finance/accounts/{orgId}/reservations", orgId)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReserveRequestPayload(
                        engagementRef, amountCents, payeeAccountId, commissionBonusBps))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("reserve HTTP {} org={} ref={}", code, orgId, engagementRef);
                    return switch (code) {
                        case 200, 201 -> verifiedReservation(
                                resp, orgId, engagementRef, amountCents, payeeAccountId, commissionBonusBps);
                        case 409 -> reserveConflict(resp, code);
                        default -> responseError(resp, code);
                    };
                });
    }

    private Mono<ReserveResult> verifiedReservation(
            ClientResponse response,
            String organizationId,
            String engagementRef,
            long amountCents,
            String payeeAccountId,
            int commissionBonusBps) {
        return response.bodyToMono(RESERVATION_TYPE)
                .switchIfEmpty(Mono.error(new FinanceEscrowException("reserve failed: empty success response")))
                .map(envelope -> verifyReservation(
                        envelope, organizationId, engagementRef, amountCents, payeeAccountId, commissionBonusBps))
                .onErrorMap(error -> error instanceof FinanceEscrowException
                        ? error
                        : new FinanceEscrowException(
                                "reserve failed: invalid success response: " + error.getMessage()));
    }

    private ReserveResult verifyReservation(
            Envelope<ReservationData> envelope,
            String organizationId,
            String engagementRef,
            long amountCents,
            String payeeAccountId,
            int commissionBonusBps) {
        ReservationData data = envelope.data();
        long expectedBonusCents = calculateBonusCents(amountCents, commissionBonusBps);
        boolean matches = Boolean.TRUE.equals(envelope.success())
                && data != null
                && Objects.equals(organizationId, data.organizationId())
                && Objects.equals(engagementRef, data.engagementRef())
                && Objects.equals(amountCents, data.amountCents())
                && Objects.equals(payeeAccountId, data.payeeAccountId())
                && Objects.equals(commissionBonusBps, data.commissionBonusBps())
                && Objects.equals(expectedBonusCents, data.commissionBonusCents())
                && "reserved".equals(data.status());
        if (!matches) {
            throw new FinanceEscrowException("reserve failed: finance response scope mismatch");
        }
        return ReserveResult.reserved(data.amountCents());
    }

    private Mono<ReserveResult> reserveConflict(ClientResponse response, int code) {
        return response.bodyToMono(ERROR_TYPE)
                .switchIfEmpty(Mono.error(new FinanceEscrowException("reserve failed: HTTP 409: empty response")))
                .flatMap(envelope -> {
                    if (Boolean.FALSE.equals(envelope.success()) && "余额不足".equals(envelope.error())) {
                        return Mono.just(ReserveResult.insufficientFunds());
                    }
                    return Mono.error(new FinanceEscrowException(
                            "reserve failed: HTTP " + code + ": " + envelope.error()));
                })
                .onErrorMap(error -> error instanceof FinanceEscrowException
                        ? error
                        : new FinanceEscrowException(
                                "reserve failed: invalid HTTP 409 response: " + error.getMessage()));
    }

    private Mono<ReserveResult> responseError(ClientResponse response, int code) {
        return response.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(body -> Mono.error(
                        new FinanceEscrowException("reserve failed: HTTP " + code + ": " + body)));
    }

    private static long calculateBonusCents(long amountCents, int bonusBps) {
        try {
            long whole = Math.multiplyExact(amountCents / BASIS_POINTS, bonusBps);
            long remainder = Math.multiplyExact(amountCents % BASIS_POINTS, bonusBps) / BASIS_POINTS;
            return Math.addExact(whole, remainder);
        } catch (ArithmeticException overflow) {
            throw new FinanceEscrowException("reserve failed: commission bonus overflow");
        }
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

    private record Envelope<T>(Boolean success, T data) {}

    private record ErrorEnvelope(Boolean success, String error) {}

    private record ReserveRequestPayload(
            String engagementRef,
            long amountCents,
            String payeeAccountId,
            int commissionBonusBps) {}

    private record ReservationData(
            String organizationId,
            String engagementRef,
            Long amountCents,
            String payeeAccountId,
            Integer commissionBonusBps,
            Long commissionBonusCents,
            String status) {}
}
