package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceException;

/**
 * 任务审核请求体（GL-P2-ADMIN-003）。expectedVersion 乐观锁 + note（reject 必填）。
 */
public record TaskReviewRequest(int expectedVersion, String note) {
    /** reject 时 note 必填（≤500 字）。 */
    String requireNote() {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new MarketplaceException(400, "驳回必须填写原因");
        }
        if (trimmed.length() > 500) {
            throw new MarketplaceException(400, "驳回原因过长（上限 500 字）");
        }
        return trimmed;
    }
}
