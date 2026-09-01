package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 分镜下的一个镜头（任务书 #64 卡1）。video_shot 一行，用户可在第 2 步编辑。
 *
 * <p>{@code anchorImageIndex} 是 **1 基**图片序号，0 = 无锚定图（图文成片复用相邻锚定图，
 * 任务书范围外「不做无图镜头的 AI 补图」）。上界是 {@link VideoStoryboard#requestPayload()}
 * 里的运行期图片数，DDL 看不见，由 API 层按「∈ [0, 图片数]」校验并 400。
 */
public record VideoShot(
        UUID id,
        UUID storyboardId,
        int seq,
        String visual,
        String narration,
        int plannedSeconds,
        String cameraMove,
        int anchorImageIndex,
        String prompt,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    /** 无锚定图镜头：纯文生或图文成片复用相邻图。 */
    @JsonIgnore
    public boolean isWithoutAnchorImage() {
        return anchorImageIndex <= 0;
    }
}
