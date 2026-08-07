package com.grassland.marketplace.workflow.saga;

/**
 * 商家确认窗口 Saga 结局（D-03）。
 *
 * <ul>
 *   <li>{@code auto_settled} — 兼容状态名：窗口到期后已自动确认并成功启动等级对应的结算窗口；
 *       真正 capture 仍由后续 settlement workflow 完成。</li>
 *   <li>{@code held} — 自动结算时遇开放争议 / 核验 failed → 暂缓（reason 如 open_dispute）；资金仍 reserved。</li>
 *   <li>{@code aborted} — 前置未过（app 不存在 / 非 accepted；或商家已手动确认由其 SettlementWindow 接管结算）。</li>
 * </ul>
 */
public record ConfirmationOutcome(String status, String reason) {

    public static ConfirmationOutcome autoSettled() {
        return new ConfirmationOutcome("auto_settled", null);
    }

    public static ConfirmationOutcome held(String reason) {
        return new ConfirmationOutcome("held", reason);
    }

    public static ConfirmationOutcome aborted() {
        return new ConfirmationOutcome("aborted", null);
    }
}
