package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 履约核验记录：商家触发的自动核验（链接可达性 + AI 视觉）对一份交付物的聚合判定（Verification v1）。
 *
 * <p>{@code status} 是自动核验的 tri-state 聚合态：passed / failed / inconclusive
 * （多项 check 取最差：failed &gt; inconclusive &gt; passed）。{@code checksJson} 是各项明细的 JSON 字符串
 * （{@code [{type, status, detail, checked_at}]}），存为 jsonb。
 *
 * <p>商家手动决策<b>不在此记录</b>——{@code confirm} 即手动通过、{@code submissions/.../reject} 即手动退回，
 * 复用既有流。本记录只承载自动核验的证据，keyed on {@code submission_id}（履约级，resubmit 安全）。
 */
public record EngagementVerification(
        String id,
        String submissionId,
        String status,
        String checksJson,
        String latestRunId,
        String engineVersion,
        String taskContextSnapshotJson,
        String evidenceSnapshotJson,
        Instant lastCheckedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public EngagementVerification(String id, String submissionId, String status, String checksJson,
                                  Instant lastCheckedAt, Instant createdAt, Instant updatedAt) {
        this(id, submissionId, status, checksJson, null, "v1", null, null,
                lastCheckedAt, createdAt, updatedAt);
    }
}
