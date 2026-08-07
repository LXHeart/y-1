package com.grassland.intelligence.creationassistant;

import java.util.Locale;

/**
 * 创作草稿来源类型（草场 PRD §4.9 / Slice 15）。镜像前端 {@code CreationSourceType}（ai-creation.ts）。
 *
 * <ul>
 *   <li>{@link #INDEPENDENT} — 独立创作（从主题/想法开始）。</li>
 *   <li>{@link #TASK} — 从履约任务创作（带 task_id + task_version，§4.12 创作上下文快照入口）。</li>
 *   <li>{@link #STORE} — 从门店创作（带 store_id + organization_id）。</li>
 *   <li>{@link #HOT_TOPIC} — 从热点创作（§4.9.5 热点→选题链路）。</li>
 *   <li>{@link #REFERENCE} — 参考素材（分析抖音/B站视频）。</li>
 * </ul>
 */
public enum DraftSourceType {
    INDEPENDENT("independent"),
    TASK("task"),
    STORE("store"),
    HOT_TOPIC("hot-topic"),
    REFERENCE("reference");

    private final String db;

    DraftSourceType(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按请求字符串解析；非法值返回 null（调用方据此 400）。大小写不敏感。 */
    public static DraftSourceType fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DraftSourceType type : values()) {
            if (type.db.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
