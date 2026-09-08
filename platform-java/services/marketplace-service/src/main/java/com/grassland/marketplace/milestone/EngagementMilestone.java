package com.grassland.marketplace.milestone;

import java.time.Instant;

/**
 * 履约里程碑事实行（任务书 #96 C96-02 / D96-03）：脚手架事实记录而非状态机——application.status 不因此
 * 增加中间态，展示与结算从事实派生。
 *
 * <p>{@code kind}：script（脚本）/ deliverable（成品）/ published（按约发布）；{@code version} 同类里程碑的
 * 重做版次（UNIQUE(application_id, kind, version)），结算只认每类「最高已确认版本」一次。
 * {@code proposedBy} 提出方（提交事实的推荐官或草稿作者），{@code confirmedBy}/{@code confirmedAt}
 * 为对方互签——双方确认制（提出方不能自签）。{@code amountCents} 由取消/超时结算回填的审计快照
 * （guarded 一次写入，之后不可变），结算事件同时引用里程碑 id 列表，全程可审计。
 */
public record EngagementMilestone(
        String id,
        String applicationId,
        String kind,
        int version,
        String evidenceSubmissionId,
        String proposedBy,
        String confirmedBy,
        Instant confirmedAt,
        Long amountCents,
        Instant createdAt,
        Instant updatedAt) {

    public static final String KIND_SCRIPT = "script";
    public static final String KIND_DELIVERABLE = "deliverable";
    public static final String KIND_PUBLISHED = "published";

    public static boolean isValidKind(String kind) {
        return KIND_SCRIPT.equals(kind) || KIND_DELIVERABLE.equals(kind) || KIND_PUBLISHED.equals(kind);
    }

    public boolean confirmed() {
        return confirmedAt != null;
    }
}
