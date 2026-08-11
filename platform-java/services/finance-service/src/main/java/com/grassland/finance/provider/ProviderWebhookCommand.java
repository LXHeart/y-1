package com.grassland.finance.provider;

/** Provider-neutral webhook envelope used by the sandbox simulator and future signed adapters. */
public record ProviderWebhookCommand(
        String eventId, String provider, String eventType, String providerRef,
        String operationId, String payloadJson) {}
