package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/** Authoritative retention deadline returned by intelligence. */
public record KybMediaRetentionReceipt(
        UUID mediaReferenceId,
        UUID referenceId,
        String referenceType,
        Instant leaseUntil,
        Instant retainedUntil) {}
