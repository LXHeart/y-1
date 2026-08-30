package com.grassland.intelligence.creationstyle;

/**
 * 创作 skill 三分类（任务书 #57）：标题套路（titles 注入）/ 内容体裁与文风口吻（content 注入，体裁在前文风在后）。
 *
 * <p>DB CHECK 约束同款枚举值；{@link #fromKey} 宽容解析（未知/缺失 → null，由调用方决定 400 语义）。
 */
public enum CreationStyleSkillCategory {
    TITLE_FORMULA("TITLE_FORMULA", "标题套路"),
    GENRE("GENRE", "体裁"),
    STYLE("STYLE", "文风");

    private final String key;
    private final String label;

    CreationStyleSkillCategory(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    /** 中文标签（400 文案「所选{标题套路|体裁|文风}无效或已停用」用）。 */
    public String label() {
        return label;
    }

    /** 宽容解析：null/未知 → null（区别于 Platform.fromKey 的回退默认——这里未知必须显式失败）。 */
    public static CreationStyleSkillCategory fromKey(String raw) {
        if (raw == null) {
            return null;
        }
        String k = raw.trim().toUpperCase();
        for (CreationStyleSkillCategory c : values()) {
            if (c.key.equals(k)) {
                return c;
            }
        }
        return null;
    }
}
