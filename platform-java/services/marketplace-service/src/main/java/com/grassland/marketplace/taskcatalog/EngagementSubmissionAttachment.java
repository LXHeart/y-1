package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/**
 * 履约交付物附件：一条已挂接到 {@link EngagementSubmission} 的 media_reference（草场 Slice 11 Stage 2）。
 *
 * <p>{@code mediaReferenceId} 是跨服务引用 intelligence 的 media_reference（无 FK）；{@code mimeType}/{@code sizeBytes}
 * 在挂接时快照——media 日后被删后，marketplace 仍可向商家展示「曾是图片 / xx KB」的残留信息（下载置灰），
 * 下载时再经 {@code IntelligenceMediaClient} 中转取短时 presigned URL。
 */
public record EngagementSubmissionAttachment(
        String id,
        String submissionId,
        String mediaReferenceId,
        String mimeType,
        Long sizeBytes,
        Instant createdAt
) {}
