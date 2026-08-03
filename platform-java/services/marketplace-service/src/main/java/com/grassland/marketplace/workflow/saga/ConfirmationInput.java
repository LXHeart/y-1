package com.grassland.marketplace.workflow.saga;

/**
 * 商家确认窗口 Saga 输入（D-03）。全可序列化——Temporal workflow 参数。
 *
 * <p>{@code windowSeconds} 由 controller 读 {@code CONFIRMATION_WINDOW_SECONDS} 配置传入——workflow 内禁读 env
 * （HLD 9.2 确定性铁律）。{@code applicationId} = engagement_ref（= task_application.id）。
 * {@code submissionId} 绑定本次提交：被退回后重交会启新 workflow，旧 Timer 到期见旧 submission 已 rejected 即 abort，
 * 不会错误结算新凭证。
 */
public record ConfirmationInput(
        String applicationId,
        String submissionId,
        String organizationId,
        long windowSeconds) {
}
