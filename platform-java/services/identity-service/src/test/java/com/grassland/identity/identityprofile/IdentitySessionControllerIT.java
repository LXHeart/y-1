package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 端到端验证多设备 session 视图/撤销 + per-session 隔离 + 审计（草场身份域 Slice 2I / HLD D-08）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：多设备活动身份独立、列 session、撤销本人/拒绝他人、激活审计行、无 cookie 401。
 */
class IdentitySessionControllerIT extends IdentityItSupport {

    @Test
    void multiDeviceActiveIdentityIndependent() {
        var acc = seedAccount("is-multi@example.com");
        String device2 = cookieFor(acc.accountId());   // 同账号第二台设备（不同 session）
        openIdentity(acc.cookie(), "merchant");          // 账号开通 merchant（一次，账号级）

        // 设备1 激活 merchant
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"merchant\"}").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEqualTo("merchant");

        // 设备2 仍是消费者（per-session 隔离：互不影响）
        client().get().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + device2)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSessionsRevokeAndAudit() {
        var acc = seedAccount("is-list@example.com");
        openIdentity(acc.cookie(), "recommender");
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isOk();

        Map<String, Object> body = client().get().uri("/api/me/sessions")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
        assertThat(data).hasSize(1);
        String token = (String) data.get(0).get("sessionToken");

        client().delete().uri("/api/me/sessions/" + token).header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk();
        // 撤销的是**自己这台**，于是自己也被登出——这正是「撤销=真登出」的语义，
        // 前端据 current 标记提示用户「撤销这条会把你登出」。
        client().get().uri("/api/me/sessions").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isUnauthorized();
        Long remaining = db.sql("SELECT COUNT(*)::int AS c FROM identity_session"
                        + " WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(remaining).isZero();

        Long revoked = db.sql("SELECT COUNT(*)::int AS c FROM identity_audit_log"
                        + " WHERE account_id = CAST(:acct AS uuid) AND action = 'revoke_session'")
                .bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(revoked).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revokeOtherAccountSessionForbidden() {
        var a = seedAccount("is-own@example.com");
        var b = seedAccount("is-other@example.com");
        openIdentity(a.cookie(), "recommender");
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + a.cookie())
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isOk();

        Map<String, Object> body = client().get().uri("/api/me/sessions")
                .header("Cookie", "y1.sid=" + a.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        String aToken = (String) ((List<Map<String, Object>>) body.get("data")).get(0).get("sessionToken");

        client().delete().uri("/api/me/sessions/" + aToken).header("Cookie", "y1.sid=" + b.cookie())
                .exchange().expectStatus().isForbidden();
    }

    /**
     * 只登录、从没切换过身份的设备**也必须出现在清单里**。
     *
     * <p>`identity_session` 是懒创建的，早先按它列清单会漏掉这类设备——安全界面上给出一个
     * 看起来完整的子集，用户会据此认定「没有异常登录」，比不做更危险。浏览器实测正是栽在这里：
     * 卡片在本机激活身份之前就拉了列表，于是一条「本机」都没标出来。
     */
    @Test
    @SuppressWarnings("unchecked")
    void listsDeviceThatNeverActivatedAnIdentity() {
        var acc = seedAccount("is-never-activated@example.com");
        String device2 = cookieFor(acc.accountId());   // 只登录，不激活任何身份

        Map<String, Object> body = client().get().uri("/api/me/sessions")
                .header("Cookie", "y1.sid=" + device2).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

        assertThat(data).hasSize(2);   // 两台设备都在（都只是登录过）
        Map<String, Object> self = data.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("current"))).findFirst().orElseThrow();
        assertThat(self.get("activeIdentityType")).isNull();   // 消费者：右表无行
    }

    /** 设备列表要能认出「这台就是我」，否则前端无从提示「撤销这条会把自己登出」。 */
    @Test
    @SuppressWarnings("unchecked")
    void listMarksOnlyTheRequestingDeviceAsCurrent() {
        var acc = seedAccount("is-current@example.com");
        String device2 = cookieFor(acc.accountId());
        openIdentity(acc.cookie(), "recommender");
        activate(acc.cookie());
        activate(device2);

        Map<String, Object> body = client().get().uri("/api/me/sessions")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");

        assertThat(data).hasSize(2);
        assertThat(data.stream().filter(s -> Boolean.TRUE.equals(s.get("current"))).count()).isEqualTo(1);
    }

    /**
     * 撤销另一台设备后，那台设备必须**真的登出**（登录会话行被删），而不只是清掉活动身份——
     * 只清活动身份的话对方 cookie 依然有效、照样能操作，「撤销」名不副实。
     */
    @Test
    @SuppressWarnings("unchecked")
    void revokingAnotherDeviceLogsItOut() {
        var acc = seedAccount("is-logout@example.com");
        String device2 = cookieFor(acc.accountId());
        openIdentity(acc.cookie(), "recommender");
        activate(device2);

        // 撤销前：设备2 已登录
        client().get().uri("/api/auth/me").header("Cookie", "y1.sid=" + device2)
                .exchange().expectStatus().isOk();

        Map<String, Object> body = client().get().uri("/api/me/sessions")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        // 按 current 挑出「不是本机」的那台，不依赖列表顺序
        String device2Token = (String) ((List<Map<String, Object>>) body.get("data")).stream()
                .filter(s -> !Boolean.TRUE.equals(s.get("current")))
                .findFirst().orElseThrow().get("sessionToken");

        client().delete().uri("/api/me/sessions/" + device2Token)
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk();

        // 撤销后：设备2 登出，发起撤销的设备1 不受影响
        client().get().uri("/api/auth/me").header("Cookie", "y1.sid=" + device2)
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/auth/me").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk();
    }

    @Test
    void activateWritesAuditRow() {
        var acc = seedAccount("is-audit@example.com");
        openIdentity(acc.cookie(), "recommender");
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isOk();

        Long activated = db.sql("SELECT COUNT(*)::int AS c FROM identity_audit_log"
                        + " WHERE account_id = CAST(:acct AS uuid) AND action = 'activate'"
                        + " AND from_identity_type IS NULL AND to_identity_type = 'recommender'")
                .bind("acct", acc.accountId()).map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(activated).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditQueryIsAccountScopedFilteredAndCursorPaged() {
        var acc = seedAccount("is-audit-query@example.com");
        var other = seedAccount("is-audit-query-other@example.com");
        openIdentity(acc.cookie(), "recommender");
        openIdentity(other.cookie(), "recommender");

        activate(acc.cookie());
        client().delete().uri("/api/me/active-identity")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk();
        activate(acc.cookie());
        activate(other.cookie());

        Map<String, Object> firstBody = client().get().uri(builder -> builder
                        .path("/api/me/identity-audit").queryParam("limit", 2).build())
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> firstData = (Map<String, Object>) firstBody.get("data");
        List<Map<String, Object>> firstItems = (List<Map<String, Object>>) firstData.get("items");
        assertThat(firstItems).hasSize(2);
        assertThat(firstItems).extracting(item -> item.get("action"))
                .containsExactly("activate", "deactivate");
        assertThat(firstItems).allSatisfy(item -> {
            assertThat(item.get("sessionFingerprint")).asString().hasSize(12);
            assertThat(item).doesNotContainKey("sessionToken");
        });

        String cursor = (String) firstData.get("nextCursor");
        Map<String, Object> secondBody = client().get().uri(builder -> builder
                        .path("/api/me/identity-audit").queryParam("limit", 2)
                        .queryParam("cursor", cursor).build())
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> secondItems = (List<Map<String, Object>>)
                ((Map<String, Object>) secondBody.get("data")).get("items");
        assertThat(secondItems).hasSize(1);
        assertThat(secondItems.getFirst().get("action")).isEqualTo("activate");

        client().get().uri(builder -> builder.path("/api/me/identity-audit")
                        .queryParam("action", "deactivate").build())
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].action").isEqualTo("deactivate");
    }

    @Test
    void auditQueryRejectsInvalidInputsAndAnonymousRequests() {
        var acc = seedAccount("is-audit-invalid@example.com");
        client().get().uri("/api/me/identity-audit?action=unknown")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isBadRequest();
        client().get().uri("/api/me/identity-audit?cursor=not-base64")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isBadRequest();
        client().get().uri("/api/me/identity-audit?limit=101")
                .header("Cookie", "y1.sid=" + acc.cookie()).exchange().expectStatus().isBadRequest();
        client().get().uri("/api/me/identity-audit").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        client().get().uri("/api/me/sessions").exchange().expectStatus().isUnauthorized();
    }

    /** 激活 recommender：identity_session 行是懒创建的，不激活就不会出现在设备列表里。 */
    private void activate(String cookie) {
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"recommender\"}").exchange().expectStatus().isOk();
    }

    private void openIdentity(String cookie, String type) {
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"" + type + "\"}").exchange().expectStatus().isCreated();
    }
}
