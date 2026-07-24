package com.grassland.marketplace.workflow.saga;

/**
 * finance ReserveFunds 的结果（草场 Epic 4 Slice 4F）。activity 间传递 + 进 workflow history 供 replay。
 *
 * <ul>
 *   <li>{@code reserved=true} — 预留成功（compensation 时须 release 退还）。</li>
 *   <li>{@code reserved=false} — 余额不足（InsufficientFunds）；无预留，compensation 仅回退报名。</li>
 * </ul>
 *
 * <p>注意：余额不足是<b>正常返回值</b>（非异常）——不触发 Temporal 重试；瞬态 finance 错误（5xx/网络）才抛异常被重试。
 */
public record ReserveResult(boolean reserved, long amountCents) {

    public static ReserveResult reserved(long amountCents) {
        return new ReserveResult(true, amountCents);
    }

    public static ReserveResult insufficientFunds() {
        return new ReserveResult(false, 0L);
    }
}
