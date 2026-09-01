package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一次分镜生成（任务书 #64 卡1）。video_storyboard 一行。
 *
 * <p>{@code requestPayload} 是原始分镜请求的 JSON 文本（含 1-9 张 base64 图片）。必须落库：
 * take 生成与图文成片都在 worker 里**异步**执行，届时原 HTTP 请求早已消失，
 * 而 {@link VideoShot#anchorImageIndex()} 只能对着这份存档解析。
 */
public record VideoStoryboard(
        UUID id,
        String accountId,
        String organizationId,
        UUID contextSnapshotId,
        int targetDurationSeconds,
        String requestPayload,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_COMMITTED = "committed";

    /** 已提交成片的分镜不可再改镜头（卡4 编辑闸）。 */
    @JsonIgnore
    public boolean isCommitted() {
        return STATUS_COMMITTED.equals(status);
    }
}
