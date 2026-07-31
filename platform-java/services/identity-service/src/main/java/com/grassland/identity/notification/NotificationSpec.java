package com.grassland.identity.notification;

import java.util.List;
import java.util.Map;

/**
 * 一条领域事件解析后的「待发通知」描述。草场 Slice 12 Stage 2。
 *
 * <p>{@link #recipients} 已是解析完成的收件人 accountId 列表（去重、排除操作者本人），
 * 由 {@code NotificationRecipientResolver} 异步查出后填入。一个 spec 对 N 个收件人 → N 条通知
 * （第二条幂等闸门 {@code UNIQUE(source_event_id, account_id)} 兜底）。
 */
public record NotificationSpec(
        NotificationCategory category,
        String title,
        String body,
        String linkPath,
        Map<String, Object> payload,
        List<String> recipients) {
}
