package com.grassland.identity.notification;

import java.time.Instant;
import java.util.Map;

/**
 * 一条站内通知（{@code notification} 表一行）。草场 Slice 12。
 *
 * @param sourceEventId 派生自哪个领域事件；{@code null} = 非事件派生（系统通知）
 * @param payload       前端渲染所需的少量结构化字段（orgId/taskId…），恒非 null（DB 默认 {@code '{}'}）
 * @param readAt        {@code null} = 未读
 */
public record Notification(
        String id,
        String accountId,
        NotificationCategory category,
        String eventType,
        String title,
        String body,
        String linkPath,
        String sourceEventId,
        Map<String, Object> payload,
        Instant readAt,
        Instant createdAt) {

    public boolean isRead() {
        return readAt != null;
    }
}
