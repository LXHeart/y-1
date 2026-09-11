package com.grassland.intelligence.videoproduction;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 每镜制作来源（任务书 #100 C100-11 / API-10 / §6.5）。
 *
 * <p>generated 是缺省来源（缺行即 generated）；own-media 只接受账号自有且当前可读的
 * 图片/视频 mediaId——图片 trim 必须为 null、audioMode 仅 narration/mute；视频要求整数
 * {@code 0 ≤ trimStartMs < trimEndMs ≤ 实测时长} 且截取长度等于 plannedSeconds×1000，
 * audioMode=source 要求素材真实存在音轨。规则校验在 {@link VideoShotSourceService}
 * （读库 + ffprobe 实测），本类型只承载字段与枚举。
 */
public record VideoShotSource(
        UUID shotId,
        UUID storyboardId,
        String sourceKind,
        UUID mediaId,
        Long trimStartMs,
        Long trimEndMs,
        String audioMode,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static final String KIND_GENERATED = "generated";
    public static final String KIND_OWN_MEDIA = "own-media";
    public static final String AUDIO_SOURCE = "source";
    public static final String AUDIO_NARRATION = "narration";
    public static final String AUDIO_MUTE = "mute";

    /** 单个制作来源文件上限（沿用视频归档口径，§6.5）。 */
    public static final long MAX_SOURCE_BYTES = 200L * 1024 * 1024;

    public boolean isOwnMedia() {
        return KIND_OWN_MEDIA.equals(sourceKind);
    }

    /** API 视图（两模式与任务详情共用）：generated 只暴露 kind，own-media 带完整引用。 */
    public java.util.Map<String, Object> toView() {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("kind", sourceKind);
        if (isOwnMedia()) {
            view.put("mediaId", mediaId == null ? null : mediaId.toString());
            view.put("trimStartMs", trimStartMs);
            view.put("trimEndMs", trimEndMs);
            view.put("audioMode", audioMode);
        }
        return view;
    }

    /** 缺行默认视图（旧分镜兼容：generated，不带其余字段）。 */
    public static java.util.Map<String, Object> generatedView() {
        return java.util.Map.of("kind", KIND_GENERATED);
    }
}
