package com.grassland.intelligence.media;

/**
 * media_reference 生命周期状态（草场 Slice 8 第二步）。
 *
 * <ul>
 *   <li>{@link #PENDING} — 已申请上传凭据，对象尚未 confirm（临时对象）。</li>
 *   <li>{@link #ACTIVE} — confirm 校验通过 / 服务端直接生成，正式资产。</li>
 *   <li>{@link #DELETED} — 软删（保留行作删除审计，对象已 GC）。</li>
 * </ul>
 * 「临时对象转正式资产」= {@code PENDING → ACTIVE}（confirm 时翻转）。
 */
public enum MediaStatus {
    PENDING("pending"),
    FINALIZING("finalizing"),
    ACTIVE("active"),
    DELETING("deleting"),
    DELETED("deleted");

    private final String db;

    MediaStatus(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }

    public static MediaStatus fromDb(String value) {
        if (value == null) {
            return null;
        }
        for (MediaStatus status : values()) {
            if (status.db.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
