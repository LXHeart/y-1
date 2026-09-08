package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.TaskApplication;

/**
 * Converts the acceptance-time day entitlement into a deterministic Temporal timer.
 *
 * <p>业务审查 2026-09-07 C11 / ADR-D06 时序门控：可支付时间 = <b>max(T+N 结算权益, 争议保护窗口)</b>。
 * 等级权益（Lv5 默认 T+1 = 24h）不能单独把资金保护期缩短到争议窗口（48h）之内——争议在出账前
 * 落在托管态（S1）是 D06 的根治主线，窗口内出账会把处置推到 S2+（冲正/应收追偿）。
 * {@code disputeWindowSeconds} 以确认时点起算（确认 → 争议窗口 → 结算，三段时序）；0 = 关闭下限
 * （IT/e2e 拨快），生产/compose 显式置 172800。
 */
public final class SettlementWindowPolicy {

    private static final int STANDARD_DELAY_DAYS = 2;

    private SettlementWindowPolicy() {}

    /** 兼容旧口径（无争议窗口下限，纯 T+N）；生产路径应使用三参重载。 */
    public static long windowSeconds(TaskApplication application, long daySeconds) {
        return windowSeconds(application, daySeconds, 0);
    }

    public static long windowSeconds(TaskApplication application, long daySeconds, long disputeWindowSeconds) {
        return windowSeconds(application.settlementDelayDaysAtAccept(), daySeconds, disputeWindowSeconds);
    }

    /**
     * 任务书 #96 C96-05：同一口径的预览形态（发布预览无已接受报名，等级权益取标准缺省 T+2）——
     * 与结算窗口<b>同源计算</b>，预览数字即服务端结算口径（TC96-019/021）。
     */
    public static long windowSeconds(Integer settlementDelayDaysAtAccept, long daySeconds, long disputeWindowSeconds) {
        int days = settlementDelayDaysAtAccept == null ? STANDARD_DELAY_DAYS : settlementDelayDaysAtAccept;
        long entitlement = Math.multiplyExact(Math.max(0L, daySeconds), Math.max(0, days));
        return Math.max(entitlement, Math.max(0L, disputeWindowSeconds));
    }
}
