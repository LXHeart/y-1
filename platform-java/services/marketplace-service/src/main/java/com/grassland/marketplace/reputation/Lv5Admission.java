package com.grassland.marketplace.reputation;

import java.time.Instant;

/** Lv5 运营邀请当前态；历史变更在 reputation_admin_audit 中只追加保存。 */
public record Lv5Admission(String accountId, boolean admitted, long version,
                           String updatedBy, String note, Instant updatedAt) {

    public static Lv5Admission none(String accountId) {
        return new Lv5Admission(accountId, false, 0, null, null, null);
    }
}
