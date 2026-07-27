package com.grassland.intelligence.event;

import java.time.Instant;
import java.util.Map;

/**
 * intelligence 事件信封（复刻 marketplace 的 EventEnvelope，intelligence 私有）。
 * Slice 1 仅写 outbox 表；Kafka 发布器发 topic {@code grassland.intelligence.events}。
 */
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
