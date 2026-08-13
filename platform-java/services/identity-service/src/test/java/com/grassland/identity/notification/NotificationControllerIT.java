package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 收件箱端到端（Slice 12 Stage 1）。覆盖：
 * <ul>
 *   <li>列表只看自己的、unreadOnly 过滤、keyset 分页游标、limit 夹取、unreadCount 随页返回；</li>
 *   <li>unread-count；</li>
 *   <li>read 只影响自己（他人 id 传入 → 0，对方仍未读）+ 幂等（二次 → 0）；read-all；</li>
 *   <li>非法 UUID → 400；未登录 → 401。</li>
 * </ul>
 *
 * <p>Stage 1 尚无事件写入方，故通知通过 {@link NotificationRepository#insertIfAbsent} 直接 seed
 * （Stage 2 起由消费者写入）。沿用 {@link IdentityItSupport} 的单例容器与 Flyway V11 建表。
 */
@SuppressWarnings("unchecked")
class NotificationControllerIT extends IdentityItSupport {

    private static final Map<String, Object> PAYLOAD = Map.of("organizationId", "org-1");

    @Autowired
    private NotificationRepository notifications;

    @Test
    void listReturnsOnlyMineNewestFirst() {
        var me = seedAccount("notif-mine@example.com");
        var other = seedAccount("notif-other@example.com");
        seed(me.accountId(), "MembershipInvited", "给我的", "a-1");
        seed(other.accountId(), "MembershipInvited", "给别人的", "a-2");

        client().get().uri("/api/me/notifications").header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("给我的")
                .jsonPath("$.data.unreadCount").isEqualTo(1)
                .jsonPath("$.data.items[0].read").isEqualTo(false)
                .jsonPath("$.data.nextBefore").doesNotExist();
    }

    @Test
    void unreadOnlyFilter() {
        var me = seedAccount("notif-unread@example.com");
        seed(me.accountId(), "MembershipInvited", "未读", "b-1");
        var read = seed(me.accountId(), "MembershipInvited", "已读", "b-2");
        markReadDirect(me.accountId(), List.of(read.id()));

        client().get().uri("/api/me/notifications?unreadOnly=true").header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("未读")
                .jsonPath("$.data.unreadCount").isEqualTo(1);
    }

    @Test
    void keysetPagingUsesCursor() {
        var me = seedAccount("notif-page@example.com");
        seed(me.accountId(), "MembershipInvited", "n1", "c-1");
        seed(me.accountId(), "MembershipInvited", "n2", "c-2");
        seed(me.accountId(), "MembershipInvited", "n3", "c-3");

        // 第一页 limit=2
        var first = client().get()
                .uri(b -> b.path("/api/me/notifications").queryParam("limit", "2").build())
                .header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        List<?> page1 = (List<?>) data(first).get("items");
        assertThat(page1).hasSize(2);
        String before = data(first).get("nextBefore").toString();
        String beforeId = (String) data(first).get("nextBeforeId");
        assertThat(beforeId).isNotNull();
        java.util.Set<String> seen = titles(page1);

        // 第二页用游标 → 剩下 1 条，且与第一页无重叠（keyset 不重复、不漏）
        var second = client().get().uri(b -> b.path("/api/me/notifications")
                        .queryParam("limit", "2").queryParam("before", before).queryParam("beforeId", beforeId).build())
                .header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        List<?> page2 = (List<?>) data(second).get("items");
        assertThat(page2).hasSize(1);
        assertThat(titles(page2)).doesNotContainAnyElementsOf(seen);
        assertThat(data(second).get("nextBefore")).isNull();
    }

    @Test
    void limitClampedToMax() {
        var me = seedAccount("notif-clamp@example.com");
        for (int i = 0; i < 3; i++) {
            seed(me.accountId(), "MembershipInvited", "n" + i, "clamp-" + i);
        }
        // limit=10000 应被夹到 50，3 条全部返回且不报错
        client().get().uri("/api/me/notifications?limit=10000").header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(3);
    }

    @Test
    void readOnlyAffectsMine() {
        var me = seedAccount("notif-read-mine@example.com");
        var other = seedAccount("notif-read-other@example.com");
        var mineUnread = seed(me.accountId(), "MembershipInvited", "我的未读", "d-1");
        var othersNotification = seed(other.accountId(), "MembershipInvited", "别人的未读", "d-2");

        // 我尝试把「别人的」也标已读 → 该 id 对 me 不存在，不计入 updated
        long updated = markReadDirect(me.accountId(), List.of(mineUnread.id(), othersNotification.id()));
        assertThat(updated).isEqualTo(1);

        // 别人的通知丝毫未动，对方仍未读
        Long otherUnread = notifications.countUnread(other.accountId()).block();
        assertThat(otherUnread).isEqualTo(1L);
    }

    @Test
    void readIsIdempotent() {
        var me = seedAccount("notif-idemp@example.com");
        var n = seed(me.accountId(), "MembershipInvited", "x", "e-1");
        long first = markReadDirect(me.accountId(), List.of(n.id()));
        long second = markReadDirect(me.accountId(), List.of(n.id()));
        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(0L);
    }

    @Test
    void readAllMarksEverything() {
        var me = seedAccount("notif-all@example.com");
        seed(me.accountId(), "MembershipInvited", "x", "f-1");
        seed(me.accountId(), "MembershipInvited", "y", "f-2");

        client().post().uri("/api/me/notifications/read-all")
                .header("Cookie", "y1.sid=" + me.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.updated").isEqualTo(2);

        assertThat(notifications.countUnread(me.accountId()).block()).isZero();
    }

    @Test
    void readRejectsInvalidUuid() {
        var me = seedAccount("notif-badid@example.com");
        client().post().uri("/api/me/notifications/read")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + me.cookie())
                .bodyValue("{\"ids\":[\"not-a-uuid\"]}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(v ->
                        assertThat(v.toString()).contains("通知 id"));
    }

    @Test
    void requiresAuthentication() {
        client().get().uri("/api/me/notifications").exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/me/notifications/unread-count").exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/me/notifications/read-all").exchange().expectStatus().isUnauthorized();
    }

    // ---- helpers ----

    private Notification seed(String accountId, String eventType, String title, String sourceEventId) {
        return notifications.insertIfAbsent(accountId, NotificationCategory.INVITATION, eventType, title,
                        "正文", "/me/invitations", sourceEventId, PAYLOAD).block();
    }

    private long markReadDirect(String accountId, List<String> ids) {
        return notifications.markRead(accountId, ids).block();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    private static java.util.Set<String> titles(List<?> items) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Object o : items) {
            out.add((String) ((Map<?, ?>) o).get("title"));
        }
        return out;
    }
}
