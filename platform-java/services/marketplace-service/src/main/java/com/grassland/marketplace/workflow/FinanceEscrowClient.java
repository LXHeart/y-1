package com.grassland.marketplace.workflow;

import com.grassland.http.ManagedWebClientFactory;

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
 *   <li>{@code release(orgId, engagementRef)} → POST /api/finance/reservations/{ref}/release（任务书 #90 D90-02
 *       验证语义）：200 核对回包；409 回读核对确为 released 才算幂等成功；404 无预留可退；其余抛异常。</li>
 *   <li>{@code captureVerified(...)} → POST /api/finance/reservations/{ref}/capture：成功/已处理/异常三态，
 *       404 与 409 均回读核对状态、金额、组织、收款人，不一致返回对账结论而非静默成功。</li>
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
        this.webClient = ManagedWebClientFactory.create(FinanceEscrowClient.class, baseUrl);
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

    /**
     * 释放预留（任务书 #90 D90-02 验证语义）：200 须回包核对组织/引用/终态；409 须回读核对确为
     * released 才按幂等成功（已 captured/refunded 的预留绝不能当已释放——抛异常交上层对账）；
     * 404 = 无预留可退（补偿/取消幂等 no-op，reserve 可能未发生过）；其余抛异常重试。
     */
    public Mono<Void> release(String orgId, String engagementRef) {
        return webClient.post()
                .uri("/api/finance/reservations/{ref}/release", engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("release HTTP {} org={} ref={}", code, orgId, engagementRef);
                    if (code == 200) {
                        return verifiedRelease(resp, orgId, engagementRef).then();
                    }
                    if (code == 404) {
                        return Mono.empty();  // 不存在 → 无预留可退（幂等 no-op）
                    }
                    if (code == 409) {
                        return confirmReleasedAfterConflict(orgId, engagementRef);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Void>error(
                                    new FinanceEscrowException("release failed: HTTP " + code + ": " + b)));
                });
    }

    private Mono<ReservationData> verifiedRelease(ClientResponse response, String organizationId,
                                                   String engagementRef) {
        return response.bodyToMono(RESERVATION_TYPE)
                .switchIfEmpty(Mono.error(new FinanceEscrowException("release failed: empty success response")))
                .map(envelope -> {
                    ReservationData data = envelope.data();
                    boolean matches = Boolean.TRUE.equals(envelope.success()) && data != null
                            && Objects.equals(organizationId, data.organizationId())
                            && Objects.equals(engagementRef, data.engagementRef())
                            && "released".equals(data.status());
                    if (!matches) {
                        throw new FinanceEscrowException("release failed: finance response scope mismatch");
                    }
                    return data;
                })
                .onErrorMap(error -> error instanceof FinanceEscrowException
                        ? error
                        : new FinanceEscrowException(
                                "release failed: invalid success response: " + error.getMessage()));
    }

    /** 409 后回读核对（D90-02）：确为 released → 幂等成功；captured/refunded/缺行 → 异常进对账。 */
    private Mono<Void> confirmReleasedAfterConflict(String organizationId, String engagementRef) {
        return fetchReservation(organizationId, engagementRef)
                .switchIfEmpty(Mono.error(new FinanceEscrowException(
                        "release failed: reservation vanished after 409: " + engagementRef)))
                .flatMap(data -> "released".equals(data.status()) && organizationId.equals(data.organizationId())
                        ? Mono.empty()
                        : Mono.error(new FinanceEscrowException(
                                "release conflict: reservation state " + data.status() + " must be reconciled")));
    }

    /**
     * 结算捕获（任务书 #90 D90-01/D90-02）：成功/已处理/异常三态分明。
     * {@code expectedAmountCents}/{@code expectedPayeeAccountId} 为 accept 时冻结的赏金与收款推荐官——
     * 200 与 409 幂等回读都要核对组织、引用、金额、收款人，一致才算捕获成功；不一致返回
     * {@link CaptureOutcome#reconciliationRequired(String)}，调用方<b>不得发 settled</b>，转对账处置。
     */
    public Mono<CaptureOutcome> captureVerified(String orgId, String engagementRef, long expectedAmountCents,
                                                String expectedPayeeAccountId, Long settlementAmountCents) {
        return webClient.post()
                .uri("/api/finance/reservations/{ref}/capture", engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CaptureRequestPayload(settlementAmountCents))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("capture HTTP {} org={} ref={}", code, orgId, engagementRef);
                    if (code == 200) {
                        return verifiedCapture(resp, orgId, engagementRef, expectedAmountCents,
                                expectedPayeeAccountId);
                    }
                    if (code == 404) {
                        return Mono.just(CaptureOutcome.reconciliationRequired("reservation_missing"));
                    }
                    if (code == 409) {
                        return classifiedCaptureConflict(orgId, engagementRef, expectedAmountCents,
                                expectedPayeeAccountId);
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<CaptureOutcome>error(
                                    new FinanceEscrowException("capture failed: HTTP " + code + ": " + b)));
                });
    }

    private Mono<CaptureOutcome> verifiedCapture(ClientResponse response, String organizationId,
                                                 String engagementRef, long expectedAmountCents,
                                                 String expectedPayeeAccountId) {
        return response.bodyToMono(RESERVATION_TYPE)
                .switchIfEmpty(Mono.error(new FinanceEscrowException("capture failed: empty success response")))
                .map(envelope -> {
                    ReservationData data = envelope.data();
                    if (captureScopeMatches(envelope, data, organizationId, engagementRef, expectedAmountCents,
                            expectedPayeeAccountId) && "captured".equals(data.status())) {
                        return CaptureOutcome.capturedNow();
                    }
                    // HTTP 200 但范围不符：钱已动了但与预期不符——进对账，不得发 settled。
                    return CaptureOutcome.reconciliationRequired("reservation_scope_mismatch");
                })
                .onErrorMap(error -> error instanceof FinanceEscrowException
                        ? error
                        : new FinanceEscrowException(
                                "capture failed: invalid success response: " + error.getMessage()));
    }

    /** 409 后回读核对（D90-02）：captured 且范围一致 → 幂等成功；released/refunded/范围不符 → 对账。 */
    private Mono<CaptureOutcome> classifiedCaptureConflict(String organizationId, String engagementRef,
                                                           long expectedAmountCents, String expectedPayeeAccountId) {
        return fetchReservation(organizationId, engagementRef)
                .<CaptureOutcome>map(data -> {
                    if (!"captured".equals(data.status())) {
                        return "released".equals(data.status())
                                ? CaptureOutcome.reconciliationRequired("reservation_released")
                                : CaptureOutcome.reconciliationRequired("reservation_refunded");
                    }
                    return captureScopeMatches(null, data, organizationId, engagementRef, expectedAmountCents,
                            expectedPayeeAccountId)
                            ? CaptureOutcome.capturedAlready()
                            : CaptureOutcome.reconciliationRequired("reservation_scope_mismatch");
                })
                .defaultIfEmpty(CaptureOutcome.reconciliationRequired("reservation_missing"));
    }

    /** D90-02 核对：组织、引用、冻结金额、收款推荐官四项全符才算同一预留。 */
    private static boolean captureScopeMatches(Envelope<ReservationData> envelope, ReservationData data,
                                               String organizationId, String engagementRef, long expectedAmountCents,
                                               String expectedPayeeAccountId) {
        boolean envelopeOk = envelope == null || Boolean.TRUE.equals(envelope.success());
        return envelopeOk && data != null
                && Objects.equals(organizationId, data.organizationId())
                && Objects.equals(engagementRef, data.engagementRef())
                && Objects.equals(expectedAmountCents, data.amountCents())
                && Objects.equals(expectedPayeeAccountId, data.payeeAccountId());
    }

    /** 服务断言读预留（D90-02 核对通道）：200 → 数据；404 → empty；其余 → 异常。 */
    Mono<ReservationData> fetchReservation(String organizationId, String engagementRef) {
        return webClient.get()
                .uri("/api/finance/reservations/{ref}", engagementRef)
                .header(headerName, issuer.issueForOrg(organizationId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (code == 200) {
                        return resp.bodyToMono(RESERVATION_TYPE)
                                .switchIfEmpty(Mono.error(new FinanceEscrowException(
                                        "fetch reservation failed: empty success response")))
                                .map(envelope -> {
                                    if (!Boolean.TRUE.equals(envelope.success()) || envelope.data() == null) {
                                        throw new FinanceEscrowException(
                                                "fetch reservation failed: unsuccessful envelope");
                                    }
                                    return envelope.data();
                                })
                                .onErrorMap(error -> error instanceof FinanceEscrowException
                                        ? error
                                        : new FinanceEscrowException("fetch reservation failed: "
                                                + error.getMessage()));
                    }
                    if (code == 404) {
                        return Mono.empty();
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<ReservationData>error(new FinanceEscrowException(
                                    "fetch reservation failed: HTTP " + code + ": " + b)));
                });
    }

    /**
     * capture 结算校验结论（D90-02）：{@code captured=true} 才允许发 settled 事件
     * （{@link #alreadyCaptured()} 是经回读核对的幂等重试，重发确定性 event_id 仍 exactly-once）；
     * 否则 {@code reconciliationReason} 供结算侧登记对账处置单。
     */
    public record CaptureOutcome(boolean captured, String reconciliationReason, boolean alreadyCaptured) {
        public static CaptureOutcome capturedNow() {
            return new CaptureOutcome(true, null, false);
        }

        public static CaptureOutcome capturedAlready() {
            return new CaptureOutcome(true, null, true);
        }

        public static CaptureOutcome reconciliationRequired(String reason) {
            return new CaptureOutcome(false, reason, false);
        }
    }

    // ---------------- 霸王餐押金（ADR-D12，方向与 bounty 相反：出资方=推荐官钱包） ----------------

    /**
     * 预付押金进托管（accept Saga 分支）：扣推荐官钱包、建 freebie_escrow 行。
     * 2xx→Reserved；409「钱包余额不足」→InsufficientFunds（镜像商家 reserve 语义，Saga 补偿回 pending）；其余→抛异常。
     */
    public Mono<ReserveResult> freebieReserve(String orgId, String engagementRef, long amountCents,
                                              String recommenderAccountId, String taskOwnerAccountId) {
        return webClient.post()
                .uri("/internal/freebie/reserve")
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new FreebieReservePayload(
                        engagementRef, recommenderAccountId, taskOwnerAccountId, orgId, amountCents))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("freebie reserve HTTP {} org={} ref={}", code, orgId, engagementRef);
                    return switch (code) {
                        case 200, 201 -> resp.bodyToMono(String.class).defaultIfEmpty("")
                                .then(Mono.just(ReserveResult.reserved(amountCents)));
                        case 409 -> resp.bodyToMono(ERROR_TYPE)
                                .switchIfEmpty(Mono.error(new FinanceEscrowException(
                                        "freebie reserve failed: HTTP 409: empty response")))
                                .flatMap(envelope -> "钱包余额不足".equals(envelope.error())
                                        ? Mono.just(ReserveResult.insufficientFunds())
                                        : Mono.<ReserveResult>error(new FinanceEscrowException(
                                                "freebie reserve failed: HTTP 409: " + envelope.error())))
                                .onErrorMap(error -> error instanceof FinanceEscrowException ? error
                                        : new FinanceEscrowException(
                                                "freebie reserve failed: invalid 409 response: " + error.getMessage()));
                        default -> resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(body -> Mono.<ReserveResult>error(
                                        new FinanceEscrowException("freebie reserve failed: HTTP " + code + ": " + body)));
                    };
                });
    }

    /** 押金退还推荐官（达标/取消/补偿回滚）。2xx/404/409 → 成功（幂等：已终态/不存在视作成功）。 */
    public Mono<Void> freebieRefund(String orgId, String engagementRef) {
        return postFreebieLifecycle(orgId, engagementRef, "refund");
    }

    /** 押金补偿商家 org（未达标/商家获判）。幂等语义同 refund。 */
    public Mono<Void> freebieCompensate(String orgId, String engagementRef) {
        return postFreebieLifecycle(orgId, engagementRef, "compensate");
    }

    private Mono<Void> postFreebieLifecycle(String orgId, String engagementRef, String action) {
        return webClient.post()
                .uri("/internal/freebie/{ref}/" + action, engagementRef)
                .header(headerName, issuer.issueForOrg(orgId, "grassland-finance"))
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    log.info("freebie {} HTTP {} org={} ref={}", action, code, orgId, engagementRef);
                    if (code == 200 || code == 404 || code == 409) {
                        return Mono.<Void>empty();  // 成功 / 不存在 / 已终态 → 幂等成功
                    }
                    return resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.<Void>error(
                                    new FinanceEscrowException("freebie " + action + " failed: HTTP " + code + ": " + b)));
                });
    }

    private record Envelope<T>(Boolean success, T data) {}

    private record ErrorEnvelope(Boolean success, String error) {}

    private record ReserveRequestPayload(
            String engagementRef,
            long amountCents,
            String payeeAccountId,
            int commissionBonusBps) {}

    private record FreebieReservePayload(
            String engagementRef,
            String recommenderAccountId,
            String taskOwnerAccountId,
            String organizationId,
            long amountCents) {}

    private record CaptureRequestPayload(Long settlementAmountCents) {}

    private record ReservationData(
            String organizationId,
            String engagementRef,
            Long amountCents,
            String payeeAccountId,
            Integer commissionBonusBps,
            Long commissionBonusCents,
            String status) {}
}
