package com.grassland.marketplace.workflow.saga;

/**
 * 结算 Saga 结局（草场 Epic 5 Slice 5A）。{ {@code status}, {@code reason} }。
 *
 * <ul>
 *   <li>{@code settled} — capture 成功（funds_reservation reserved→captured，资金确认付给商家）。</li>
 *   <li>{@code held} — 因开放争议等暂缓结算（reason 如 open_dispute）；资金仍 reserved，待争议裁决。</li>
 *   <li>{@code aborted} — 前置未过（非 accepted/confirmed / app 不存在），未进 capture。</li>
 * </ul>
 */
public record SettlementOutcome(String status, String reason) {

    public static SettlementOutcome settled() {
        return new SettlementOutcome("settled", null);
    }

    public static SettlementOutcome held(String reason) {
        return new SettlementOutcome("held", reason);
    }

    public static SettlementOutcome aborted() {
        return new SettlementOutcome("aborted", null);
    }
}
