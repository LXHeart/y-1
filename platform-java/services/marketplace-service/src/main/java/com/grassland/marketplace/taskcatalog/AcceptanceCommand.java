package com.grassland.marketplace.taskcatalog;

import java.time.Instant;

/** Immutable accept request identity plus its durable Saga dispatch state. */
public record AcceptanceCommand(
        String id,
        String actorAccountId,
        String idempotencyKey,
        String taskId,
        String applicationId,
        String workflowId,
        String merchantAccountId,
        String organizationId,
        long amountCents,
        String status,
        String failureReason,
        Instant workflowStartedAt,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {

    public boolean monetary() {
        return amountCents > 0;
    }
}
