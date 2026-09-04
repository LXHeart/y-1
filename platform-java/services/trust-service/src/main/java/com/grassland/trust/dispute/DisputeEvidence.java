package com.grassland.trust.dispute;

import java.time.Instant;

/**
 * 争议证据项（GL-P2-TRUST-001 T1）。
 *
 * <p>争议的附件——文本（原文存 {@code contentRef}）、截图（{@code contentRef}=intelligence {@code media_reference} id）
 * 或外链。{@code redactedRef} 是脱敏后内容，审判官/客服只读它（D-10）；raw 永不进审判视图、不进 outbox payload。
 *
 * <p>{@code retentionUntil} 按 D-10 证据保留期（6–12 月，provisional 默认 365 天，config
 * {@code trust.evidence.retention-days} 覆盖）；过期由清理任务脱敏/删（清理任务另项，本轮只建模）。
 *
 * <p>{@code submittedByAccountId} 仅审计/归属用，<b>不</b>进脱敏审判视图（剥离 uploader 身份，D-10）。
 *
 * @param kind {@code text} / {@code screenshot} / {@code link}
 * @param phase 任务书 #74 卡 B：证据轮次——claim=原告首轮 / answer=被告答辩 / rebuttal=原告补充
 *              （存量行默认 claim）
 */
public record DisputeEvidence(
        String id,
        String disputeId,
        String submittedByAccountId,
        String submittedByRole,
        String kind,
        String contentRef,
        String redactedRef,
        String caption,
        Instant createdAt,
        Instant retentionUntil,
        String phase) {

    /** 既有 10 参调用方兼容：phase 缺省 claim。 */
    public DisputeEvidence(String id, String disputeId, String submittedByAccountId, String submittedByRole,
                           String kind, String contentRef, String redactedRef, String caption,
                           Instant createdAt, Instant retentionUntil) {
        this(id, disputeId, submittedByAccountId, submittedByRole, kind, contentRef, redactedRef, caption,
                createdAt, retentionUntil, "claim");
    }
}
