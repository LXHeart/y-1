package com.grassland.trust.dispute;

import java.time.Instant;

/**
 * 争议案件（dispute-case，HLD 5.5）。草场 Epic 6 Slice 6A（受理）+ 6C（审判扩字段）+ 任务书 #74 小法庭重构。
 *
 * <p>{@code engagementRef} 跨服务引用 marketplace application/engagement（database-per-service 无 FK）；
 * {@code organizationId} 冗余供鉴权/查询；{@code openedByRole}=merchant/recommender（HLD 10.5 Party）。
 *
 * <p>状态机（{@link DisputeCaseStatus}，任务书 #74 卡 B 起 6 态）：
 * {@code open→(evidence)→voting→decided→(appealed→)final}，平票按 {@code round} 重开。
 * 非 {@code final} 状态均占用该 engagement 的活跃争议槽（阻塞结算）。court 通道新案直入 {@code evidence}
 * 质证期；存量 open 案与 cs_direct/merchant_rejection 停留 open（读取时 open 视同 evidence——fresh 判定 open|evidence）。
 *
 * <p>{@code kind}：standard / merchant_rejection（D-03，直送客服终审）。
 * {@code channel}（任务书 #74 卡 A，D6）：court=小法庭（质证+面板）/ cs_direct=客服直裁（SLA 内终裁，不进面板）。
 * {@code taskPlatform}：涉案任务目标平台（卡 D 垂类配额抽签 + 卡 G 判例库共用）。
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
        String kind,
        boolean premiumSupport,
        int supportPriority,
        // ---- 任务书 #74：通道（卡 A）/ 质证期（卡 B）/ 垂类（卡 D）/ 被诉方（方案 α）----
        String channel,
        Instant csDueAt,
        String taskPlatform,
        Instant claimantDoneAt,
        Instant respondentDoneAt,
        boolean respondentAnswered,
        Instant evidenceDeadline,
        String respondentAccountId) {

    /** 既有 19 参调用方兼容（#74 之前）：新列取默认（court 通道、无质证标记）。 */
    public DisputeCase(
            String id, String engagementRef, String organizationId, String openedByAccountId,
            String openedByRole, String status, String reason, String decision, Instant decidedAt,
            Instant createdAt, Instant updatedAt, int round, long version, String appealState,
            String finalDecision, String finalDecidedBy, String evidenceRef, String kind) {
        this(id, engagementRef, organizationId, openedByAccountId, openedByRole, status, reason, decision,
                decidedAt, createdAt, updatedAt, round, version, appealState, finalDecision, finalDecidedBy,
                evidenceRef, kind, false, 0);
    }

    /** 既有 20 参调用方兼容（premium 快照版）：新列取默认。 */
    public DisputeCase(
            String id, String engagementRef, String organizationId, String openedByAccountId,
            String openedByRole, String status, String reason, String decision, Instant decidedAt,
            Instant createdAt, Instant updatedAt, int round, long version, String appealState,
            String finalDecision, String finalDecidedBy, String evidenceRef, String kind,
            boolean premiumSupport, int supportPriority) {
        this(id, engagementRef, organizationId, openedByAccountId, openedByRole, status, reason, decision,
                decidedAt, createdAt, updatedAt, round, version, appealState, finalDecision, finalDecidedBy,
                evidenceRef, kind, premiumSupport, supportPriority, null, null, null,
                null, null, false, null, null);
    }

    /** 任务书 #74 实现（6f64a7aa）28 参调用方兼容：被诉方取 NULL（仅 /me 与受众判定消费）。 */
    public DisputeCase(
            String id, String engagementRef, String organizationId, String openedByAccountId,
            String openedByRole, String status, String reason, String decision, Instant decidedAt,
            Instant createdAt, Instant updatedAt, int round, long version, String appealState,
            String finalDecision, String finalDecidedBy, String evidenceRef, String kind,
            boolean premiumSupport, int supportPriority, String channel, Instant csDueAt, String taskPlatform,
            Instant claimantDoneAt, Instant respondentDoneAt, boolean respondentAnswered,
            Instant evidenceDeadline) {
        this(id, engagementRef, organizationId, openedByAccountId, openedByRole, status, reason, decision,
                decidedAt, createdAt, updatedAt, round, version, appealState, finalDecision, finalDecidedBy,
                evidenceRef, kind, premiumSupport, supportPriority, channel, csDueAt, taskPlatform,
                claimantDoneAt, respondentDoneAt, respondentAnswered, evidenceDeadline, null);
    }

    /** 通道缺省：null/blank → court（存量语义）。 */
    public String effectiveChannel() {
        return channel == null || channel.isBlank() ? "court" : channel;
    }

    /** 质证期判定：court 通道且状态处于受理/质证（open 为存量兼容态，视同 evidence）。 */
    public boolean inEvidencePhase() {
        String current = status == null ? "open" : status;
        return "court".equals(effectiveChannel()) && DisputeCaseStatus.isEvidencePending(current);
    }
}
