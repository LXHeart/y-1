package com.grassland.intelligence.contentlibrary;

import java.util.Locale;

/**
 * 素材状态机（草场 PRD §4.8 / Slice 14）。公共库走审核流；个人/商家库默认 active（不经审核）。
 *
 * <ul>
 *   <li>{@link #DRAFT} — 草稿（公共库可先存草稿再提交审核）。</li>
 *   <li>{@link #PENDING_REVIEW} — 待审核（公共库提交后进入，等 CONTENT_REVIEWER 审）。</li>
 *   <li>{@link #ACTIVE} — 已上架（个人/商家库创建即 active；公共库审核通过转 active）。</li>
 *   <li>{@link #REJECTED} — 审核驳回（公共库被拒，可改后重提）。</li>
 *   <li>{@link #EXPIRED} — 已过期（valid_until 到期；列表查询自动排除，由调度或读时判定标记）。</li>
 * </ul>
 */
public enum AssetStatus {
    DRAFT("draft"),
    PENDING_REVIEW("pending_review"),
    ACTIVE("active"),
    REJECTED("rejected"),
    EXPIRED("expired");

    private final String db;

    AssetStatus(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    /** 按数据库值解析；null/非法值返回 null。 */
    public static AssetStatus fromDb(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (AssetStatus status : values()) {
            if (status.db.equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
