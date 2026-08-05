package com.grassland.identity.kyb;

import java.time.Instant;
import java.util.UUID;

/** Claimed retention synchronization command. */
public record KybMediaRetentionCommand(
        UUID mediaReferenceId,
        UUID referenceId,
        String organizationId,
        String referenceType,
        String desiredState,
        Instant retainUntil,
        Instant remoteLeaseUntil,
        String syncStatus,
        int attemptCount,
        UUID claimToken) {}
