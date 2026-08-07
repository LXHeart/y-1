package com.grassland.intelligence.creationassistant;

import java.util.Locale;

/**
 * 创作草稿状态（草场 PRD §4.9 / Slice 15）。
 *
 * <ul>
 *   <li>{@link #DRAFT} — 草稿（默认，创作中）。</li>
 *   <li>{@link #IN_PROGRESS} — 进行中（已进入某创作阶段，如大纲/正文）。</li>
 *   <li>{@link #COMPLETED} — 已完成（用户标记完成或已导出）。</li>
 *   <li>{@link #ARCHIVED} — 已归档（不再活跃但保留历史）。</li>
 * </ul>
 */
public enum DraftStatus {
    DRAFT("draft"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    ARCHIVED("archived");

    private final String db;

    DraftStatus(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按数据库值解析；null/非法值返回 null。 */
    public static DraftStatus fromDb(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DraftStatus status : values()) {
            if (status.db.equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
