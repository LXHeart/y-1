package com.grassland.intelligence.credits;

import reactor.core.publisher.Mono;

/**
 * 积分扣减端口。生产实现为 {@link FinanceCreditsClient}，直接调用 Java finance-service。
 * 业务 controller 只依赖此端口，不感知积分域的传输细节。
 */
public interface CreditsClient {

    /**
     * 原子扣减 1 积分；成功返回可用于退款的 {@link CreditCharge}，
     * 积分不足信号 {@link InsufficientCreditsException}（→402），
     * 上游不可用信号 {@code IntelligenceException(502)}。
     *
     * <p>调用方在上游（AI/媒体）失败时必须 {@link #refund(CreditCharge, String)}，
     * 否则用户为失败调用付费（GL-P0-BILL-002）。
     */
    Mono<CreditCharge> consume(String accountId, CreditFeature feature);

    /** Uses a caller-owned idempotency key when the surrounding operation is already durable. */
    default Mono<CreditCharge> consume(String accountId, CreditFeature feature, String operationId) {
        return consume(accountId, feature);
    }

    /** Reserve credits converted from estimated provider cost under a frozen monetary policy. */
    Mono<CreditCharge> reserveUsage(
            String accountId,
            CreditFeature feature,
            String operationId,
            long estimatedCents,
            String creditsCentsPolicyVersion);

    /** Idempotently settle a priced reservation against provider-reported actual cost. */
    Mono<CreditSettlement> settleUsage(
            CreditCharge charge, long actualCents, String creditsCentsPolicyVersion);

    /** 退回一次已确认扣减。幂等；失败必须传播给持久化补偿 worker。 */
    Mono<Void> refund(CreditCharge charge, String note);

    /**
     * Reconcile and compensate a consume whose HTTP response may have been lost.
     * Implementations must prevent compensation from racing with a late consume.
     */
    default Mono<Void> compensate(CreditCharge charge, String note) {
        return refund(charge, note);
    }
}
