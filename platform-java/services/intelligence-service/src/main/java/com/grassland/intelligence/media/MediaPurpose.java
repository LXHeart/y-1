package com.grassland.intelligence.media;

import java.util.Locale;

/**
 * media_reference 用途分类（草场 Slice 8 第二步）。校验客户端传入的 purpose，防止任意 key 前缀注入。
 *
 * <ul>
 *   <li>{@link #ARTICLE_GENERATED} — 文章生成图（服务端生成，短 TTL）。</li>
 *   <li>{@link #ENGAGEMENT_ATTACHMENT} — 履约交付物附件（Slice 11 地基）。</li>
 *   <li>{@link #VIDEO_ASSET} — 视频改编出图等素材（Slice 9 地基）。</li>
 *   <li>{@link #USER_UPLOAD} — 通用用户上传资产。</li>
 * </ul>
 */
public enum MediaPurpose {
    ARTICLE_GENERATED("article_generated"),
    ENGAGEMENT_ATTACHMENT("engagement_attachment"),
    MERCHANT_KYB("merchant_kyb"),
    VIDEO_ASSET("video_asset"),
    USER_UPLOAD("user_upload");

    private final String db;

    MediaPurpose(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按请求字符串解析；非法值返回 null（调用方据此 400）。大小写不敏感。 */
    public static MediaPurpose fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MediaPurpose purpose : values()) {
            if (purpose.db.equals(normalized)) {
                return purpose;
            }
        }
        return null;
    }
}
