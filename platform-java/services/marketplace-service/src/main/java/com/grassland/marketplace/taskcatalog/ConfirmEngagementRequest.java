package com.grassland.marketplace.taskcatalog;

/**
 * 商家确认履约请求体（D-02）。可整体省略：固定佣金任务保持无体确认；
 * 阶梯佣金任务必须申报 {@code confirmedMetricValue}（Sandbox 指标事实来源），
 * 与 confirmed_at 在同一 guarded UPDATE 中冻结，此后不可变。
 */
public record ConfirmEngagementRequest(Long confirmedMetricValue) {
}
