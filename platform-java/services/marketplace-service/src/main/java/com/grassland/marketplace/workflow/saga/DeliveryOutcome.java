package com.grassland.marketplace.workflow.saga;

/**
 * 交付看门狗 Saga 结局（任务书 #96 C96-01）。
 *
 * <ul>
 *   <li>{@code terminated} — 补救窗到期仍无交付，已按推荐官有责终结（refunded + exit_kind=timeout，
 *       资金按来源释放、名额回收）。</li>
 *   <li>{@code aborted} — 前置未过（非 accepted / 已确认 / 已退出 / 已提交凭证 / 延期把终结线推向未来：
 *       旧 workflow 到点被行级守卫 abort，新截止由派发器补启的新 workflow 接管）。</li>
 *   <li>{@code held} — 保留字（当前无 held 分支，语义对齐 {@link ConfirmationOutcome} 便于扩展）。</li>
 * </ul>
 */
public record DeliveryOutcome(String status, String reason) {

    public static DeliveryOutcome terminated() {
        return new DeliveryOutcome("terminated", null);
    }

    public static DeliveryOutcome aborted() {
        return new DeliveryOutcome("aborted", null);
    }
}
