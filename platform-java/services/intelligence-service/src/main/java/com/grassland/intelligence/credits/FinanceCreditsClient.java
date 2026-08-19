package com.grassland.intelligence.credits;

import com.grassland.intelligence.ai.run.CreditCompensationRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.security.IntelligenceServiceAssertionIssuer;
import com.grassland.http.ManagedWebClientFactory;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 经 finance-service 扣费（GL-P3-AI-001 下属切片，Java 原生积分的默认实现）。
 *
 * <p>直连 finance 容器（{@code credits.finance.base-url}），不经 edge-bff（避免 intelligence↔edge-bff 循环；
 * 与 marketplace→finance 直连同模式）。每个请求携带受众为 finance 的命名服务断言，
 * finance 按 intelligence principal 授权。
 *
 * <p><b>refund 幂等键派生</b>：consume 用 {@code operationId=X}，对应失败退款必须用 {@code refund:X}
 * （finance 按 operation_id 原样存储，partial unique index 据此保证「一次扣减至多一次退款」）。
 * 旧回退实现曾误传原始 consume id，与 consume 行 operation_id 撞车被当 dedup 吞掉；
 * 此处在客户端派生 {@code refund:<consumeId>}，避免退款与扣减共享幂等键。
 */
@Component
public class FinanceCreditsClient implements CreditsClient {

    private static final String FINANCE_AUDIENCE = "grassland-finance";

    private final WebClient webClient;
    private final String consumePath;
    private final String refundPath;
    private final String compensationPath;
    private final String usageReservationPath;
    private final String usageSettlementPath;
    private final MarketplaceAiEntitlementClient entitlements;
    private final IntelligenceServiceAssertionIssuer assertionIssuer;
    private final CreditCompensationRepository compensationRepository;
    private final Duration responseTimeout;

    @Autowired
    public FinanceCreditsClient(@Value("${credits.finance.base-url:http://finance-service:8084}") String baseUrl,
                                @Value("${credits.finance.consume-path:/internal/credits/consume}") String consumePath,
                                @Value("${credits.finance.refund-path:/internal/credits/refund}") String refundPath,
                                @Value("${credits.finance.compensation-path:/internal/credits/consume-compensations}")
                                String compensationPath,
                                @Value("${credits.finance.usage-reservation-path:/internal/credits/usage-reservations}")
                                String usageReservationPath,
                                @Value("${credits.finance.usage-settlement-path:/internal/credits/usage-settlements}")
                                String usageSettlementPath,
                                @Value("${credits.finance.connect-timeout-ms:3000}") int connectTimeoutMs,
                                @Value("${credits.finance.response-timeout-ms:5000}") long responseTimeoutMs,
                                MarketplaceAiEntitlementClient entitlements,
                                IntelligenceServiceAssertionIssuer assertionIssuer,
                                CreditCompensationRepository compensationRepository) {
        Duration connectTimeout = Duration.ofMillis(Math.max(1, connectTimeoutMs));
        Duration requestTimeout = Duration.ofMillis(Math.max(1, responseTimeoutMs));
        this.webClient = ManagedWebClientFactory.builder(
                        FinanceCreditsClient.class, connectTimeout, requestTimeout, 2 * 1024 * 1024)
                .baseUrl(baseUrl)
                .build();
        this.consumePath = consumePath;
        this.refundPath = refundPath;
        this.compensationPath = compensationPath;
        this.usageReservationPath = usageReservationPath;
        this.usageSettlementPath = usageSettlementPath;
        this.entitlements = entitlements;
        this.assertionIssuer = assertionIssuer;
        this.compensationRepository = compensationRepository;
        this.responseTimeout = requestTimeout;
    }

    FinanceCreditsClient(
            String baseUrl, String consumePath, String refundPath,
            MarketplaceAiEntitlementClient entitlements,
            IntelligenceServiceAssertionIssuer assertionIssuer,
            CreditCompensationRepository compensationRepository) {
        this(baseUrl, consumePath, refundPath, 1_000, 3_000,
                entitlements, assertionIssuer, compensationRepository);
    }

    FinanceCreditsClient(
            String baseUrl, String consumePath, String refundPath,
            int connectTimeoutMs, long responseTimeoutMs,
            MarketplaceAiEntitlementClient entitlements,
            IntelligenceServiceAssertionIssuer assertionIssuer,
            CreditCompensationRepository compensationRepository) {
        this(baseUrl, consumePath, refundPath, "/internal/credits/consume-compensations",
                "/internal/credits/usage-reservations", "/internal/credits/usage-settlements",
                connectTimeoutMs, responseTimeoutMs, entitlements, assertionIssuer,
                compensationRepository);
    }

    @Override
    public Mono<CreditCharge> consume(String accountId, CreditFeature feature) {
        return consume(accountId, feature, UUID.randomUUID().toString());
    }

    @Override
    public Mono<CreditCharge> consume(String accountId, CreditFeature feature, String operationId) {
        return entitlements.get(accountId)
                .flatMap(entitlement -> webClient.post().uri(consumePath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Grassland-Identity", assertionIssuer.issueService(FINANCE_AUDIENCE))
                        .bodyValue(Map.of(
                                "accountId", accountId,
                                "feature", feature.key(),
                                "operationId", operationId,
                                "aiQuotaMultiplierBps", entitlement.aiQuotaMultiplierBps(),
                                "policyVersion", entitlement.policyVersion()))
                        .retrieve()
                        .onStatus(s -> s.value() == 402,
                                response -> Mono.error(new InsufficientCreditsException()))
                        .onStatus(s -> s.is4xxClientError(),
                                response -> Mono.error(new IntelligenceException(400, "积分扣减请求无效")))
                        .onStatus(s -> s.is5xxServerError(),
                                response -> Mono.error(new UnknownConsumeOutcomeException()))
                        .bodyToMono(ConsumeEnvelope.class)
                        .timeout(responseTimeout)
                        .switchIfEmpty(Mono.error(new UnknownConsumeOutcomeException()))
                        .map(response -> chargeFrom(
                                accountId, feature, operationId, entitlement, response))
                        .onErrorResume(error -> isDefinitiveRejection(error)
                                ? Mono.error(error)
                                : persistAndCompensateUnknownConsume(
                                        accountId, feature, operationId,
                                        "积分扣减结果不确定自动补偿")));
    }

    @Override
    public Mono<CreditCharge> reserveUsage(
            String accountId, CreditFeature feature, String operationId,
            long estimatedCents, String creditsCentsPolicyVersion) {
        return entitlements.get(accountId)
                .flatMap(entitlement -> webClient.post().uri(usageReservationPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Grassland-Identity", assertionIssuer.issueService(FINANCE_AUDIENCE))
                        .bodyValue(Map.of(
                                "accountId", accountId,
                                "feature", feature.key(),
                                "operationId", operationId,
                                "estimatedCents", estimatedCents,
                                "creditsCentsPolicyVersion", creditsCentsPolicyVersion,
                                "aiQuotaMultiplierBps", entitlement.aiQuotaMultiplierBps(),
                                "policyVersion", entitlement.policyVersion()))
                        .retrieve()
                        .onStatus(s -> s.value() == 402,
                                response -> Mono.error(new InsufficientCreditsException()))
                        .onStatus(s -> s.is4xxClientError(), response -> Mono.error(
                                new IntelligenceException(
                                        response.statusCode().value(), "AI 用量积分预留请求无效")))
                        .onStatus(s -> s.is5xxServerError(),
                                response -> Mono.error(new UnknownConsumeOutcomeException()))
                        .bodyToMono(UsageReservationEnvelope.class)
                        .timeout(responseTimeout)
                        .switchIfEmpty(Mono.error(new UnknownConsumeOutcomeException()))
                        .map(response -> usageChargeFrom(
                                accountId, feature, operationId, creditsCentsPolicyVersion,
                                entitlement, response))
                        .onErrorResume(error -> isDefinitiveRejection(error)
                                ? Mono.error(error)
                                : persistAndCompensateUnknownConsume(
                                        accountId, feature, operationId,
                                        "AI 用量积分预留结果不确定自动补偿")));
    }

    @Override
    public Mono<CreditSettlement> settleUsage(
            CreditCharge charge, long actualCents, String creditsCentsPolicyVersion) {
        return webClient.post().uri(usageSettlementPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", assertionIssuer.issueService(FINANCE_AUDIENCE))
                .bodyValue(Map.of(
                        "accountId", charge.accountId(),
                        "feature", charge.feature().key(),
                        "consumeOperationId", charge.operationId(),
                        "actualCents", actualCents,
                        "creditsCentsPolicyVersion", creditsCentsPolicyVersion))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(), response -> Mono.error(
                        new IntelligenceException(
                                response.statusCode().value(), "AI 用量积分结算请求无效")))
                .onStatus(s -> s.is5xxServerError(), response -> Mono.error(
                        new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(UsageSettlementEnvelope.class)
                .timeout(responseTimeout)
                .switchIfEmpty(Mono.error(new IntelligenceException(502, "积分结算响应为空")))
                .map(response -> settlementFrom(
                        charge, actualCents, creditsCentsPolicyVersion, response));
    }

    @Override
    public Mono<Void> refund(CreditCharge charge, String note) {
        return webClient.post().uri(refundPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", assertionIssuer.issueService(FINANCE_AUDIENCE))
                .bodyValue(Map.of(
                        "accountId", charge.accountId(),
                        "feature", charge.feature().key(),
                        "operationId", "refund:" + charge.operationId(),   // 派生退款键（修正既有 bug）
                        "note", note))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(400, "积分退款请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class);
    }

    @Override
    public Mono<Void> compensate(CreditCharge charge, String note) {
        return compensateOperation(charge.accountId(), charge.feature(), charge.operationId(), note);
    }

    private Mono<Void> compensateOperation(
            String accountId, CreditFeature feature, String operationId, String note) {
        return webClient.post().uri(compensationPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Grassland-Identity", assertionIssuer.issueService(FINANCE_AUDIENCE))
                .bodyValue(Map.of(
                        "accountId", accountId,
                        "feature", feature.key(),
                        "consumeOperationId", operationId,
                        "note", note))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(
                                r.statusCode().value(), "积分补偿请求无效")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "积分服务暂不可用")))
                .bodyToMono(Void.class)
                .timeout(responseTimeout);
    }

    private Mono<CreditCharge> persistAndCompensateUnknownConsume(
            String accountId, CreditFeature feature, String operationId, String note) {
        UUID consumeOperationId;
        try {
            consumeOperationId = UUID.fromString(operationId);
        } catch (IllegalArgumentException invalid) {
            return Mono.error(new IntelligenceException(502, "积分扣减幂等键无效，无法可靠补偿"));
        }
        Mono<Void> immediateAttempt = compensateOperation(accountId, feature, operationId, note)
                .then(Mono.defer(() -> compensationRepository.markCompletedByOperationId(consumeOperationId)))
                .then()
                .onErrorResume(error -> Mono.empty());
        return compensationRepository.enqueueUnknownConsume(
                        consumeOperationId, accountId, feature.key(), note)
                .then(immediateAttempt)
                .then(Mono.error(new IntelligenceException(502, "积分服务响应不确定，已记录补偿")));
    }

    private static CreditCharge chargeFrom(
            String accountId, CreditFeature feature, String operationId,
            MarketplaceAiEntitlementClient.AiEntitlement entitlement,
            ConsumeEnvelope envelope) {
        if (envelope == null || !envelope.success() || envelope.data() == null) {
            throw new UnknownConsumeOutcomeException();
        }
        ConsumeData data = envelope.data();
        CreditCharge.Source source;
        try {
            source = CreditCharge.Source.fromWire(data.source());
        } catch (RuntimeException invalid) {
            throw new UnknownConsumeOutcomeException();
        }
        if (data.policyVersion() == null
                || data.policyVersion() != entitlement.policyVersion()
                || data.transactionId() == null
                || !isCanonicalUuid(data.transactionId())) {
            throw new UnknownConsumeOutcomeException();
        }
        return new CreditCharge(accountId, feature, operationId, source, data.policyVersion());
    }

    private static CreditCharge usageChargeFrom(
            String accountId, CreditFeature feature, String operationId,
            String expectedMoneyPolicyVersion,
            MarketplaceAiEntitlementClient.AiEntitlement entitlement,
            UsageReservationEnvelope envelope) {
        if (envelope == null || !envelope.success() || envelope.data() == null) {
            throw new UnknownConsumeOutcomeException();
        }
        UsageReservationData data = envelope.data();
        CreditCharge.Source source;
        try {
            source = CreditCharge.Source.fromWire(data.source());
        } catch (RuntimeException invalid) {
            throw new UnknownConsumeOutcomeException();
        }
        if (data.policyVersion() == null
                || data.policyVersion() != entitlement.policyVersion()
                || !expectedMoneyPolicyVersion.equals(data.creditsCentsPolicyVersion())
                || data.reservedCents() == null || data.reservedCents() < 0
                || data.reservedCredits() == null || data.reservedCredits() < 0
                || data.transactionId() == null || !isCanonicalUuid(data.transactionId())) {
            throw new UnknownConsumeOutcomeException();
        }
        return new CreditCharge(
                accountId, feature, operationId, source, data.policyVersion(), true,
                data.creditsCentsPolicyVersion(), data.reservedCents(), data.reservedCredits());
    }

    private static CreditSettlement settlementFrom(
            CreditCharge charge, long expectedActualCents, String expectedMoneyPolicyVersion,
            UsageSettlementEnvelope envelope) {
        if (envelope == null || !envelope.success() || envelope.data() == null) {
            throw new IntelligenceException(502, "积分结算响应无效");
        }
        UsageSettlementData data = envelope.data();
        CreditCharge.Source source;
        try {
            source = CreditCharge.Source.fromWire(data.source());
        } catch (RuntimeException invalid) {
            throw new IntelligenceException(502, "积分结算响应无效");
        }
        if (!expectedMoneyPolicyVersion.equals(data.creditsCentsPolicyVersion())
                || data.reservedCents() == null
                || charge.reservedCents() >= 0 && data.reservedCents() != charge.reservedCents()
                || data.reservedCredits() == null
                || charge.reservedCredits() >= 0 && data.reservedCredits() != charge.reservedCredits()
                || data.actualCents() == null || data.actualCents() != expectedActualCents
                || data.actualCredits() == null || data.actualCredits() < 0
                || data.adjustmentCredits() == null) {
            throw new IntelligenceException(502, "积分结算响应与冻结参数不一致");
        }
        return new CreditSettlement(
                charge.accountId(), charge.feature(), charge.operationId(), source,
                data.creditsCentsPolicyVersion(), data.reservedCents(), data.reservedCredits(),
                data.actualCents(), data.actualCredits(), data.adjustmentCredits(),
                Boolean.TRUE.equals(data.deduplicated()));
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isDefinitiveRejection(Throwable error) {
        return error instanceof InsufficientCreditsException
                || error instanceof IntelligenceException intelligenceError
                        && intelligenceError.status() >= 400
                        && intelligenceError.status() < 500;
    }

    private static final class UnknownConsumeOutcomeException extends RuntimeException {}

    private record ConsumeEnvelope(boolean success, ConsumeData data) {}

    private record ConsumeData(String source, Long policyVersion, String transactionId) {}

    private record UsageReservationEnvelope(boolean success, UsageReservationData data) {}

    private record UsageReservationData(
            String source, Long policyVersion, String transactionId,
            String creditsCentsPolicyVersion, Long reservedCents, Integer reservedCredits) {}

    private record UsageSettlementEnvelope(boolean success, UsageSettlementData data) {}

    private record UsageSettlementData(
            String source, String creditsCentsPolicyVersion,
            Long reservedCents, Integer reservedCredits,
            Long actualCents, Integer actualCredits, Integer adjustmentCredits,
            Boolean deduplicated) {}
}
