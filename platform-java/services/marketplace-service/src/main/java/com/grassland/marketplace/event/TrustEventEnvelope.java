package com.grassland.marketplace.event;

import com.fasterxml.jackson.databind.JsonNode;

public record TrustEventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        JsonNode payload) {}
