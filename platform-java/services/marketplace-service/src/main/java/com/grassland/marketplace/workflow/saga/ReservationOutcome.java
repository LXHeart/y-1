package com.grassland.marketplace.workflow.saga;

/**
 * 接受报名 Saga 的最终结局（草场 Epic 4 Slice 4F）。{ {@code status}, {@code reason} }。
 *
 * <ul>
 *   <li>{@code accepted} — reserve 成功并激活履约。</li>
 *   <li>{@code compensated} — 失败已补偿（reason 如 insufficient_funds / activate_failed）；报名回 pending 可重试。</li>
 *   <li>{@code aborted} — beginAcceptance 前置校验未过（已终态/名额满/竞态），未进入资金流。</li>
 * </ul>
 *
 * <p>同步契约下（accept 返回 202）此 outcome 主要供 replay 测试断言；运行时轮询端点以 application DB 状态为准。
 */
public record ReservationOutcome(String status, String reason) {

    public static ReservationOutcome accepted() {
        return new ReservationOutcome("accepted", null);
    }

    public static ReservationOutcome compensated(String reason) {
        return new ReservationOutcome("compensated", reason);
    }

    public static ReservationOutcome aborted() {
        return new ReservationOutcome("aborted", null);
    }
}
