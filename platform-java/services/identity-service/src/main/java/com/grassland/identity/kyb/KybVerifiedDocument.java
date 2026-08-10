package com.grassland.identity.kyb;

public record KybVerifiedDocument(
        int schemaVersion,
        String status,
        String safeResultJson,
        String provider,
        String model) {}

