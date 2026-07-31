package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link NotificationRepository} 数据访问（Slice 12 Stage 1）。覆盖 keyset 分页游标、未读计数、
 * 幂等插入（同 {@code (sourceEventId, accountId)} 第二次返回空）、越权在 SQL 层封死。
 */
class NotificationRepositoryIT extends IdentityItSupport {

    @Autowired
    private NotificationRepository notifications;

    @Test
    void insertIfAbsentDeduplicatesSameEventAndAccount() {
        var me = seedAccount("repo-dedup@example.com");

        Notification first = notifications.insertIfAbsent(me.accountId(), NotificationCategory.INVITATION,
                "MembershipInvited", "标题", "正文", "/me/invitations", "evt-dup", Map.of()).block();
        Notification second = notifications.insertIfAbsent(me.accountId(), NotificationCategory.INVITATION,
                "MembershipInvited", "标题", "正文", "/me/invitations", "evt-dup", Map.of()).block();

        assertThat(first).isNotNull();
        assertThat(second).as("同一 (sourceEventId, account_id) 第二次插入应返回空").isNull();
        assertThat(notifications.countUnread(me.accountId()).block()).isEqualTo(1L);
    }

    @Test
    void insertIfAbsentAllowsSameEventForDifferentAccounts() {
        var a = seedAccount("repo-a@example.com");
        var b = seedAccount("repo-b@example.com");

        notifications.insertIfAbsent(a.accountId(), NotificationCategory.INVITATION,
                "MembershipInvited", "给 a", null, null, "evt-shared", Map.of()).block();
        notifications.insertIfAbsent(b.accountId(), NotificationCategory.INVITATION,
                "MembershipInvited", "给 b", null, null, "evt-shared", Map.of()).block();

        assertThat(notifications.countUnread(a.accountId()).block()).isEqualTo(1L);
        assertThat(notifications.countUnread(b.accountId()).block()).isEqualTo(1L);
    }

    @Test
    void nullSourceEventIdAlwaysInserts() {
        var me = seedAccount("repo-sys@example.com");

        // sourceEventId=null 不进唯一索引（partial index）→ 两条系统通知都插入
        notifications.insertIfAbsent(me.accountId(), NotificationCategory.SYSTEM,
                "SystemNotice", "第一条", null, null, null, Map.of()).block();
        notifications.insertIfAbsent(me.accountId(), NotificationCategory.SYSTEM,
                "SystemNotice", "第二条", null, null, null, Map.of()).block();

        assertThat(notifications.countUnread(me.accountId()).block()).isEqualTo(2L);
    }

    @Test
    void keysetCursorReturnsOnlyOlderRows() {
        var me = seedAccount("repo-page@example.com");
        // 插入顺序即 created_at 升序：oldest 先、newest 后。
        var oldest = notifications.insertIfAbsent(me.accountId(), NotificationCategory.PERMISSION,
                "PermissionRequested", "1", null, null, "p-1", Map.of()).block();
        notifications.insertIfAbsent(me.accountId(), NotificationCategory.PERMISSION,
                "PermissionRequested", "2", null, null, "p-2", Map.of()).block();
        var newest = notifications.insertIfAbsent(me.accountId(), NotificationCategory.PERMISSION,
                "PermissionRequested", "3", null, null, "p-3", Map.of()).block();

        // 游标 = newest 的 (createdAt, id)：应只返回严格更早的两条，不含 newest 本身。
        var page = notifications.findByAccount(me.accountId(), false, 10,
                newest.createdAt(), newest.id()).collectList().block();
        assertThat(page).hasSize(2);
        assertThat(page).extracting(n -> n.title()).containsExactlyInAnyOrder("1", "2");
        assertThat(page).noneMatch(n -> n.id().equals(newest.id()));
        // 游标 = oldest 的 (createdAt, id)：没有更早的 → 空（证「严格更早」语义）。
        var tail = notifications.findByAccount(me.accountId(), false, 10,
                oldest.createdAt(), oldest.id()).collectList().block();
        assertThat(tail).isEmpty();
    }

    @Test
    void clampLimitBoundsPageSize() {
        assertThat(NotificationRepository.clampLimit(null)).isEqualTo(20);
        assertThat(NotificationRepository.clampLimit(0)).isEqualTo(1);
        assertThat(NotificationRepository.clampLimit(100000)).isEqualTo(50);
        assertThat(NotificationRepository.clampLimit(7)).isEqualTo(7);
    }
}
