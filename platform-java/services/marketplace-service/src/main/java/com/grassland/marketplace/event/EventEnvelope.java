package com.grassland.marketplace.event;

import java.time.Instant;
import java.util.Map;

/**
 * marketplace 事件信封（复刻 identity 的 EventEnvelope，marketplace 私有——不跨服务引 identity 包）。
 * Slice 4A 仅写 outbox 表；Kafka 发布器（发 topic {@code grassland.marketplace.events}）留 4B。
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
