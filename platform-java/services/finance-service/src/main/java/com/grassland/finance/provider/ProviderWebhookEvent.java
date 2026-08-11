package com.grassland.finance.provider;

import java.time.Instant;

public record ProviderWebhookEvent(
        String eventId, String provider, String eventType, String providerRef, String operationId,
        String status, String errorMessage, Instant receivedAt, Instant processedAt) {}
