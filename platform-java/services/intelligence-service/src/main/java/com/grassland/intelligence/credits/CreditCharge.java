package com.grassland.intelligence.credits;

/**
 * 一次成功扣减的凭据。{@code operationId} 是 legacy 侧的幂等键：
 * 重试扣减复用同一值即不会双扣，退款复用同一值即不会重复入账（GL-P0-CRED-001）。
 */
public record CreditCharge(String accountId, CreditFeature feature, String operationId) {
}
