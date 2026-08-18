package com.grassland.intelligence.embedding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted semantic index work item and, once ready, its routing/version snapshot. */
public record ContentAssetEmbedding(
        UUID id,
        UUID assetId,
        int assetVersion,
        String contentHash,
        String status,
        String provider,
        String model,
        String modelVersionKey,
        String algorithmVersion,
        Integer dimensions,
        List<Double> embedding,
        UUID aiRunId,
        String failureCode,
        int attemptCount,
        Instant nextAttemptAt,
        UUID claimToken,
        Instant claimedUntil,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {}
