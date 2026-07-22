package com.grassland.identity.event;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    Instant occurredAt,
    String correlationId,
    Map<String, Object> payload
) {}
