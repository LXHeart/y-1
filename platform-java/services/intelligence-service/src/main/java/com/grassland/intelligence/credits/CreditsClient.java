package com.grassland.intelligence.credits;

import reactor.core.publisher.Mono;

/**
 * 积分扣减端口。Slice 1 唯一实现 {@link LegacyCreditsClient}（经 legacy 内部端点）。
 * 后续草场 {@code usage-account} 落地后可换实现，业务 controller 不变（端口隔离）。
 */
public interface CreditsClient {

    /**
     * 原子扣减 1 积分；成功完成 Mono，积分不足信号 {@link InsufficientCreditsException}（→402），
     * 上游不可用信号 {@code IntelligenceException(502)}。
     */
    Mono<Void> consume(String accountId, CreditFeature feature);
}
