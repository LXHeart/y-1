package com.grassland.finance.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderOperation(
        UUID id, String provider, String operationId, String operationType, String reference,
        long amountCents, String currency, String providerRef, String status,
        Instant createdAt, Instant updatedAt, Instant completedAt) {}
