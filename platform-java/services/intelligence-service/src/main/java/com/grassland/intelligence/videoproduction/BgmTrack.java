package com.grassland.intelligence.videoproduction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * BGM 曲库一条（任务书 #64 卡1/卡7，P3）。bgm_track 一行。
 *
 * <p>只上 CC0 / 免版税曲目，治理台上传 + 情绪分类；**种子为空**，运营上架指南在卡11。
 *
 * <p>{@code objectKey} 直指对象存储，**不进 media_reference**：BGM 是平台运营资产、无 owner
 * account，不参与用户配额、不随用户删除，与「用户素材」生命周期无关。
 *
 * <p>{@code enabled} 用原始 boolean 而非包装类型：它是库里 {@code NOT NULL DEFAULT true} 的
 * 真实列，不是派生标记（§3.5 禁的是新 record 的派生布尔与非 {@code @JsonIgnore} 的 isXxx()）。
 */
public record BgmTrack(
        UUID id,
        String name,
        String moodTags,
        String objectKey,
        String contentType,
        long sizeBytes,
        Integer durationMs,
        boolean enabled,
        String uploadedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** 受控情绪标签值集（P3 八个）；库里是 jsonb 数组，强制点在这里。 */
    public static final List<String> MOODS =
            List.of("轻快", "温暖", "治愈", "燃", "悬念", "舒缓", "国风", "电子");

    /** 受控音频 MIME（mp3 / m4a）。 */
    public static final Set<String> CONTENT_TYPES =
            Set.of("audio/mpeg", "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac");

    /** 单曲上限 10MB（任务书 §4.8）。 */
    public static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** 按请求字符串校验情绪标签；返回 null 表示非法（调用方据此 400）。 */
    public static String normalizeMood(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return MOODS.contains(trimmed) ? trimmed : null;
    }

    /** 按请求字符串校验 MIME；大小写不敏感，返回 null 表示非法。 */
    public static String normalizeContentType(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.contains(normalized) ? normalized : null;
    }
}
