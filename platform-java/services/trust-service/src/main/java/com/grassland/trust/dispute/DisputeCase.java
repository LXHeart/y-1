package com.grassland.trust.dispute;

import java.time.Instant;

/**
 * 争议案件（dispute-case，HLD 5.5）。草场 Epic 6 Slice 6A（受理）+ 6C（审判扩字段）。
 *
 * <p>{@code engagementRef} 跨服务引用 marketplace application/engagement（database-per-service 无 FK）；
 * {@code organizationId} 冗余供鉴权/查询；{@code openedByRole}=merchant/recommender（HLD 10.5 Party）。
 *
 * <p>状态机（{@link DisputeCaseStatus}，5 态）：{@code open→voting→decided→(appealed→)final}，平票按 {@code round} 重开。
 * 非 {@code final} 状态均占用该 engagement 的活跃争议槽（阻塞结算）。{@code decision}=面板/手动裁决文本；
 * {@code finalDecision}=终局裁决（含客服覆盖）；{@code version}=聚合版本（替代 outbox 硬编码 1）；
 * {@code round}=审判轮次；{@code appealState}=none/pending/filed/withdrawn；{@code evidenceRef}=脱敏证据句柄（D-10 占位）。
 *
 * <p>{@code kind}（D-03 slice 2）：{@code standard}（普通用户争议，走 7 官面板）/ {@code merchant_rejection}
 * （商家对核实通过履约的拒绝，直送客服终审，不走面板）。
 */
public record DisputeCase(
        String id,
        String engagementRef,
        String organizationId,
        String openedByAccountId,
        String openedByRole,
        String status,
        String reason,
        String decision,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt,
        int round,
        long version,
        String appealState,
        String finalDecision,
        String finalDecidedBy,
        String evidenceRef,
        String kind
) {}
