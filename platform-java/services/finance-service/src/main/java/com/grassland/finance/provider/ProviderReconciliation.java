package com.grassland.finance.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderReconciliation(
        UUID id, String provider, String statementRef, String providerRef, String operationId,
        String operationType, long amountCents, String currency, String status, Instant createdAt) {}
