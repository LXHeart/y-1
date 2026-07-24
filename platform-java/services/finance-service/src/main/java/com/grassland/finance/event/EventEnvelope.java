package com.grassland.finance.event;

import java.time.Instant;
import java.util.Map;

/**
 * finance 事件信封（复刻 marketplace 的 EventEnvelope，finance 私有——不跨服务引 marketplace 包）。
 * Slice 4D 仅写 outbox 表；Kafka 发布器（发 topic {@code grassland.finance.events}）留后续。
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
