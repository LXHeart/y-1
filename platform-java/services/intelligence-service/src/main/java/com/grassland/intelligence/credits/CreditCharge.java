package com.grassland.intelligence.credits;

/**
 * 一次成功扣减的凭据。{@code operationId} 是 legacy 侧的幂等键：
 * 重试扣减复用同一值即不会双扣，退款复用同一值即不会重复入账（GL-P0-CRED-001）。
 */
public record CreditCharge(
        String accountId, CreditFeature feature, String operationId,
        Source source, Long policyVersion,
        boolean usagePriced, String creditsCentsPolicyVersion,
        long reservedCents, int reservedCredits) {

    public CreditCharge(String accountId, CreditFeature feature, String operationId) {
        this(accountId, feature, operationId, Source.PAID, null, false, null, 0, 1);
    }

    public CreditCharge(
            String accountId, CreditFeature feature, String operationId,
            Source source, Long policyVersion) {
        this(accountId, feature, operationId, source, policyVersion, false, null, 0, 1);
    }

    public enum Source {
        QUOTA,
        PAID;

        static Source fromWire(String value) {
            return switch (value) {
                case "quota" -> QUOTA;
                case "paid" -> PAID;
                default -> throw new IllegalArgumentException("unknown credit charge source");
            };
        }
    }
}
