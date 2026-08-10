package com.grassland.identity.permission;

import java.time.Instant;

public record PermissionRequestAudit(
        String id,
        String requestId,
        String organizationId,
        String actorAccountId,
        String actorKind,
        String action,
        String fromStatus,
        String toStatus,
        String details,
        Instant createdAt) {}
