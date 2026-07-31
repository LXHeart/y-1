package com.grassland.identity.notification;

import java.util.List;

/**
 * 标记已读请求体。草场 Slice 12。
 *
 * @param ids 想标记为已读的通知 id；最多 100 条（防超长 IN/数组请求）
 */
public record MarkNotificationsReadRequest(List<String> ids) {
}
