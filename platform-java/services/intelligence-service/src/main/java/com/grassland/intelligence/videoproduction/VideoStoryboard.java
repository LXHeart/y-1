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
        String resolution,
        String requestPayload,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        /** 分组与版本分支快照（任务书 #66 C3，§3 契约）：{shots:[{id,groupId}],branches:[...]}。 */
        String grouping,
        /** 分镜编辑版本（任务书 #100 C100-01/V71）：内容/集合写入口 CAS 基线，存量行从 1 起。 */
        long editVersion) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_COMMITTED = "committed";

    /** 任务书 #100 C100-01 前的旧构造（无 editVersion）兼容：占位/测试构造沿用，版本视作 1。 */
    public VideoStoryboard(UUID id, String accountId, String organizationId, UUID contextSnapshotId,
            int targetDurationSeconds, String resolution, String requestPayload, String status,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, String grouping) {
        this(id, accountId, organizationId, contextSnapshotId, targetDurationSeconds, resolution, requestPayload,
                status, createdAt, updatedAt, grouping, 1L);
    }

    /** 已提交成片的分镜不可再改镜头（卡4 编辑闸）。 */
    @JsonIgnore
    public boolean isCommitted() {
        return STATUS_COMMITTED.equals(status);
    }

    /**
     * 分辨率缺省（#65 卡1）：V63 前的存量行与占位构造无值时按竖版处理。
     */
    @JsonIgnore
    public String resolutionOrDefault() {
        return resolution == null || resolution.isBlank() ? VideoResolution.PORTRAIT : resolution;
    }
}
