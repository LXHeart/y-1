package com.grassland.finance.provider;

/** One immutable line imported from a provider reconciliation statement. */
public record ProviderReconciliationCommand(
        String provider, String statementRef, String providerRef, String operationId,
        String operationType, long amountCents, String currency, String payloadJson) {}
