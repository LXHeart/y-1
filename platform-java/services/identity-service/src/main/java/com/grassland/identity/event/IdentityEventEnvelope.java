package com.grassland.identity.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * identity 事件消费的 5 字段 wire envelope（与所有服务的 outbox 发布格式一致）。
 * 镜像 marketplace {@code TrustEventEnvelope}。
 */
public record IdentityEventEnvelope(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        JsonNode payload) {}
