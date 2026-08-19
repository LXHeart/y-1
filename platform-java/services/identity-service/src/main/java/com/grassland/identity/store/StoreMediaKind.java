package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import java.util.Locale;
import java.util.Set;

/**
 * 门店媒体分类（#42 D1/D7）：四类素材各自的每店数量帽、MIME 白名单与单文件大小帽。
 * identity 开票前置校验与 intelligence 端点二次校验（纵深）共用同一套口径。
 */
public enum StoreMediaKind {

    STOREFRONT("storefront", 6, Constants.IMAGE_MIME_TYPES, Constants.IMAGE_MAX_BYTES),
    ENVIRONMENT("environment", 12, Constants.IMAGE_MIME_TYPES, Constants.IMAGE_MAX_BYTES),
    MENU("menu", 12, Constants.IMAGE_MIME_TYPES, Constants.IMAGE_MAX_BYTES),
    VIDEO("video", 3, Constants.VIDEO_MIME_TYPES, Constants.VIDEO_MAX_BYTES);

    public static final Set<String> IMAGE_MIME_TYPES = Constants.IMAGE_MIME_TYPES;
    public static final long IMAGE_MAX_BYTES = Constants.IMAGE_MAX_BYTES;
    public static final Set<String> VIDEO_MIME_TYPES = Constants.VIDEO_MIME_TYPES;
    public static final long VIDEO_MAX_BYTES = Constants.VIDEO_MAX_BYTES;

    /** 枚举常量初始化早于本类 static 字段，常量集中放嵌套类避前向引用。 */
    private static final class Constants {
        static final Set<String> IMAGE_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
        static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
        static final Set<String> VIDEO_MIME_TYPES = Set.of("video/mp4", "video/quicktime", "video/webm");
        static final long VIDEO_MAX_BYTES = 20L * 1024 * 1024;
    }

    private final String db;
    private final int maxPerStore;
    private final Set<String> mimeTypes;
    private final long maxBytes;

    StoreMediaKind(String db, int maxPerStore, Set<String> mimeTypes, long maxBytes) {
        this.db = db;
        this.maxPerStore = maxPerStore;
        this.mimeTypes = mimeTypes;
        this.maxBytes = maxBytes;
    }

    public String db() {
        return db;
    }

    /** 每店每类数量帽（D7）：门头 6、环境 12、菜单 12、视频 3。 */
    public int maxPerStore() {
        return maxPerStore;
    }

    public Set<String> mimeTypes() {
        return mimeTypes;
    }

    /** 单文件大小帽（D7）：图片 ≤10MB、视频 ≤20MB。 */
    public long maxBytes() {
        return maxBytes;
    }

    /** 从请求解析分类，大小写不敏感（同 intelligence MediaPurpose.fromRequest 口径）；非法 → 400。 */
    public static StoreMediaKind fromRequest(String value) {
        if (value == null || value.isBlank()) {
            throw new IdentityException(400, "媒体分类无效");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (StoreMediaKind kind : values()) {
            if (kind.db.equals(normalized)) {
                return kind;
            }
        }
        throw new IdentityException(400, "媒体分类无效");
    }

    /** 从 DB 字符串解析（内部用）；未知值返回 null。 */
    public static StoreMediaKind fromDb(String value) {
        if (value == null) {
            return null;
        }
        for (StoreMediaKind kind : values()) {
            if (kind.db.equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
