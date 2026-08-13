package com.grassland.intelligence.creationcontext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable task creation context captured at the AI-center handoff. */
public record CreationContextSnapshot(
        UUID id,
        String accountId,
        String organizationId,
        String taskId,
        String applicationId,
        int taskVersion,
        String platformId,
        String contentFormId,
        Map<String, Object> taskSnapshot,
        Map<String, Object> platformRulesSnapshot,
        Map<String, Object> materialSnapshot,
        Map<String, Object> aiConfigSnapshot,
        Instant createdAt) {
}
