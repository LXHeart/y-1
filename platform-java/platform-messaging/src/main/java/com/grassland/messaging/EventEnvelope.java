package com.grassland.messaging;

import java.time.Instant;
import java.util.Map;

/**
 * 领域事件信封（8 字段完整形态），事务 outbox 追加时的入参。
 *
 * <p>发布到 Kafka 时由 {@code OutboxPublisher} 折叠为 5 字段 wire 格式
 * （eventId/eventType/aggregateType/aggregateId/payload），aggregateVersion 等
 * 审计字段只留在库表里。原五服务各自持有一份逐字相同的 record，2026-08-20 下沉到本库。
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        Map<String, Object> payload) {}
