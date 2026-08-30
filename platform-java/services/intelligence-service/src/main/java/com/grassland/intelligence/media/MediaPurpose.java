package com.grassland.intelligence.media;

import java.util.Locale;

/**
 * media_reference 用途分类（草场 Slice 8 第二步）。校验客户端传入的 purpose，防止任意 key 前缀注入。
 *
 * <ul>
 *   <li>{@link #ARTICLE_GENERATED} — 文章生成图（服务端生成，短 TTL）。</li>
 *   <li>{@link #ENGAGEMENT_ATTACHMENT} — 履约交付物附件（Slice 11 地基）。</li>
 *   <li>{@link #MERCHANT_KYB} — 商家 KYB 附件（Slice 11+）。</li>
 *   <li>{@link #VIDEO_ASSET} — 视频改编出图等素材（Slice 9 地基）。</li>
 *   <li>{@link #USER_UPLOAD} — 通用用户上传资产。</li>
 *   <li>{@link #CONTENT_ASSET} — 内容素材库资产（Slice 14 / PRD §4.8，三类素材库的物理资产）。</li>
 *   <li>{@link #AVATAR} — 推荐官头像（任务书 #29+#30 D6）；账号级资产，仅图片 MIME。</li>
 *   <li>{@link #BRAND_LOGO} — 组织品牌 Logo（#32 D5）；org 级资产，仅图片 MIME，票据只能由 identity 服务断言代开。</li>
 *   <li>{@link #SPEECH_AUDIO} — 语音识别输入；仅受控音频 MIME 与文件签名。</li>
 *   <li>{@link #STORE_MEDIA} — 门店媒体库（#42 D1）：门头/环境/菜单图与宣传视频；org+门店级资产，
 *       图片/视频双白名单分型大小帽，票据只能由 identity 服务断言代开（domain_type='store'，domain_id=storeId）。</li>
 * </ul>
 */
public enum MediaPurpose {
    ARTICLE_GENERATED("article_generated"),
    ENGAGEMENT_ATTACHMENT("engagement_attachment"),
    MERCHANT_KYB("merchant_kyb"),
    VIDEO_ASSET("video_asset"),
    USER_UPLOAD("user_upload"),
    CONTENT_ASSET("content_asset"),
    AVATAR("avatar"),
    BRAND_LOGO("brand_logo"),
    SPEECH_AUDIO("speech_audio"),
    STORE_MEDIA("store_media"),
    /** 系列图卡生成产物（任务书 #54）：TTL 预览行与持久化永久行共用此 purpose。 */
    CARD_SERIES("card_series");

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
