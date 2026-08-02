package com.grassland.marketplace.ops;

import java.time.Instant;

/**
 * 运营处置单（GL-P1-OPS-001 Stage 1）。一行对应一件「需要人工处置的阻断/暂缓」。
 *
 * <p>与来源表<b>解耦</b>：{@code sourceKind} + {@code sourceRef} 指回去，本表只承载处置生命周期。
 * 已接入来源见 {@link OpsCaseSource}。
 *
 * <p>{@code status} 状态机 {@code open→in_review→(approved|rejected)→resolved}：
 * {@code open} 已登记未认领；{@code in_review} 已提审待另一人审批；{@code approved} 审批通过、
 * 处置动作可执行（Stage 2）；{@code rejected}/{@code resolved} 终态。
 *
 * <p><b>双人审批</b>由 DB CHECK 约束 {@code ck_ops_case_two_person} 兜住（审批人 ≠ 提审人），
 * 不只靠应用层判断 —— 资金处置的四眼原则被新调用路径绕过时没有第二道防线。
 */
public record OpsCase(
        String id,
        String sourceKind,
        String sourceRef,
        String organizationId,
        String applicationId,
        String reason,
        String severity,
        String status,
        long version,
        String submittedBy,
        Instant submittedAt,
        String submitNote,
        String approvedBy,
        Instant approvedAt,
        String approveNote,
        Instant resolvedAt,
        String resolution,
        Instant createdAt,
        Instant updatedAt) {

    /** 终态（不可再流转）。 */
    public boolean isTerminal() {
        return "rejected".equals(status) || "resolved".equals(status);
    }
}
