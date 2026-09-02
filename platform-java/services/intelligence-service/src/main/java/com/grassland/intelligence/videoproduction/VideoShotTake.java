package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 一个镜头的一个候选片段（任务书 #64 卡1，P5 抽卡）。video_shot_take 一行。
 *
 * <p>默认每镜 2 个候选，治理台可配 1-3。claim 协议（attempts / nextAttemptAt / claimedUntil /
 * claimToken）与 video_generation_job 同构，卡6 的 worker 照搬 {@code FOR UPDATE SKIP LOCKED} + lease。
 *
 * <p>{@code mediaId} 是归档进私有对象存储后的 media_reference.id（purpose=video_take）。
 * **provider 的临时 URL 永不落库、永不出响应**——沿用 VideoGenerationJobRepository 既有红线。
 */
public record VideoShotTake(
        UUID id,
        UUID shotId,
        int takeNo,
        String provider,
        String model,
        String providerTaskId,
        String status,
        int attempts,
        UUID mediaId,
        Integer durationMs,
        String errorCode,
        String errorMessage,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime claimedUntil,
        UUID claimToken,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt,
        /** 质检评分（任务书 #66 D1）：0-100，NULL=未评（advisory，不阻断挑选）。 */
        Double score,
        /** 评分标签 jsonb 原文（如「与锚定图差异大」），NULL=未评。 */
        String scoreLabels) {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final Set<String> TERMINAL =
            Set.of(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED);

    /** 终结态：worker 不再派发，成片可据此判断该镜是否齐备。 */
    @JsonIgnore
    public boolean isTerminal() {
        return TERMINAL.contains(status);
    }

    /** 可入选成片的候选：成功且已归档。 */
    @JsonIgnore
    public boolean isSelectable() {
        return STATUS_SUCCEEDED.equals(status) && mediaId != null;
    }
}
