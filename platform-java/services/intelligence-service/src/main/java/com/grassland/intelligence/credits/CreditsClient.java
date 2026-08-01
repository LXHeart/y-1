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

    /**
     * 退回一次扣减。幂等——重复调用只退一次（legacy 侧按 operationId 去重）。
     * 退款自身失败不应覆盖原始上游错误，故实现返回空 Mono 而不向外抛。
     */
    Mono<Void> refund(CreditCharge charge, String note);
}
