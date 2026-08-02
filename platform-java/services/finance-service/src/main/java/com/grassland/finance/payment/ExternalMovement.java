package com.grassland.finance.payment;

/**
 * 外部资金移动命令（{@link PaymentProviderAdapter#recordExternalMovement} 的入参）。
 *
 * <p>停留在 payment 包，不进领域模型（HLD §12.2）。
 */
public record ExternalMovement(
        Direction direction,
        long amountCents,
        String currency,
        /** 跨服务/业务引用（engagementRef / withdrawalId），供供应商侧对账。 */
        String reference,
        String memo) {

    public enum Direction {
        /** 资金入托管（充值 / 消费者支付）。 */
        IN,
        /** 资金出账（提现 / 退款）。 */
        OUT;
    }

    public static ExternalMovement in(long amountCents, String currency, String reference, String memo) {
        return new ExternalMovement(Direction.IN, amountCents, currency, reference, memo);
    }

    public static ExternalMovement out(long amountCents, String currency, String reference, String memo) {
        return new ExternalMovement(Direction.OUT, amountCents, currency, reference, memo);
    }
}
