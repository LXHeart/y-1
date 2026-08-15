package com.grassland.marketplace.matching;

import java.time.Instant;

/** Frozen score evidence for one merchant invitation. */
public record TaskRecommenderInvitation(
        String id, String taskId, String recommenderAccountId, String invitedByAccountId,
        String scoringVersion, String scoreSnapshotJson, Instant createdAt, Instant appliedAt) {}
