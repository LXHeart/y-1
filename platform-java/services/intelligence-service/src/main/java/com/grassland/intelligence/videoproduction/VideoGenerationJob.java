package com.grassland.intelligence.videoproduction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record VideoGenerationJob(
        UUID id, String accountId, String organizationId, String idempotencyKey, UUID runId,
        UUID contextSnapshotId,
        String provider, String model, String providerTaskId, String status, int progress,
        String inputPayload, String resultUrl, int requestedDurationSeconds,
        Integer actualDurationSeconds, String aspectRatio, String pricingVersion,
        int unitPriceCents, int estimatedCostCents, Integer actualCostCents,
        UUID budgetId, LocalDate budgetReservationDate, Integer reservedCostCents,
        int platformModelVersion, String providerConfigFingerprint,
        int attemptCount, Instant nextAttemptAt,
        String errorCode, String errorMessage) {}
