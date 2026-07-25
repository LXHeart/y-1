package com.grassland.marketplace.workflow.saga;

/**
 * 结算窗口 Saga 输入（草场 Epic 5 Slice 5A / HLD 10.3）。全可序列化——Temporal workflow 参数。
 *
 * <p>{@code windowSeconds} 由 controller 读 {@code SETTLEMENT_WINDOW_SECONDS} 配置传入——workflow 内禁读 env
 * （HLD 9.2 确定性铁律），Timer 时长只来自 input。其余字段语义同 {@link AcceptanceInput}（engagement_ref = applicationId）。
 */
public record SettlementInput(
        String applicationId,
        String taskId,
        String merchantAccountId,
        String organizationId,
        long amountCents,
        long windowSeconds) {
}
