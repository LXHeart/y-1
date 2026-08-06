package com.grassland.intelligence.credits;

import reactor.core.publisher.Mono;

/**
 * 积分扣减端口。Slice 1 唯一实现 {@link LegacyCreditsClient}（经 legacy 内部端点）。
 * 后续草场 {@code usage-account} 落地后可换实现，业务 controller 不变（端口隔离）。
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
