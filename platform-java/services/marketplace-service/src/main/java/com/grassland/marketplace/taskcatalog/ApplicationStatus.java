package com.grassland.marketplace.taskcatalog;

/**
 * 推荐官报名状态（application 聚合，HLD 5.3）。草场 Epic 4 Slice 4B。
 *
 * <p>流转：{@link #PENDING}（报名）→ {@link #ACCEPTED}（商家接受）/ {@link #REJECTED}（商家拒绝）/
 * {@link #WITHDRAWN}（推荐官撤销）。后三者皆终态。仅用于校验/映射逻辑；DB 与 record 仍存小写 String
 * （house style，同 {@link TaskStatus}）。
 */
public enum ApplicationStatus {
    PENDING("pending"),
    RESERVING("reserving"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    WITHDRAWN("withdrawn");

    private final String dbValue;

    ApplicationStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从 DB 字符串解析，大小写不敏感；非法值抛 {@link IllegalArgumentException}。 */
    public static ApplicationStatus fromDb(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("application status is blank");
        }
        String normalized = value.trim().toLowerCase();
        for (ApplicationStatus status : values()) {
            if (status.dbValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown application status: " + value);
    }

    /** 是否终态（不可再 accept/reject/withdraw）。RESERVING 是瞬态（Saga 进行中），非终态。 */
    public boolean isTerminal() {
        return this != PENDING && this != RESERVING;
    }
}
