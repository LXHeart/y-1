package com.grassland.intelligence.moments;

import com.grassland.intelligence.security.IntelligenceException;

/** 朋友圈风格模板（PRD §4.4：生活化 / 活动通知 / 到店体验 / 朋友分享）。 */
public enum MomentsStyle {

    LIFESTYLE("lifestyle", "生活化"),
    EVENT("event", "活动通知"),
    STORE_VISIT("store-visit", "到店体验"),
    FRIENDS_SHARE("friends-share", "朋友分享");

    private final String key;
    private final String label;

    MomentsStyle(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 解析请求风格键；空/未知键一律 400（前端固定四选一，不接受默认值）。 */
    public static MomentsStyle fromKey(String key) {
        if (key != null) {
            String trimmed = key.trim();
            for (MomentsStyle style : values()) {
                if (style.key.equals(trimmed)) {
                    return style;
                }
            }
        }
        throw new IntelligenceException(400, "朋友圈风格不合法");
    }
}
