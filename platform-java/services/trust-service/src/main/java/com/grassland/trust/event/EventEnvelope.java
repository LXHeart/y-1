package com.grassland.trust.event;

import java.time.Instant;
import java.util.Map;

/**
 * trust 事件信封（草场 Epic 6 Slice 6A，复刻 finance 的 EventEnvelope，trust 私有）。
 * 仅写 outbox 表；Kafka 发布器（发 topic {@code grassland.trust.events}）留后续。
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
