package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/** 人工改判记录（GL-P2-ADMIN-004）。passed=人工确认通过 / failed=人工判定不通过。 */
public record VerificationOverride(
        String id,
        String submissionId,
        String status,
        String reviewerAccountId,
        String reviewNote,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
