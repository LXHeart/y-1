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
        client().get().uri("/api/me/sessions").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);

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
    void rejectsRequestsWithoutSessionCookie() {
        client().get().uri("/api/me/sessions").exchange().expectStatus().isUnauthorized();
    }

    private void openIdentity(String cookie, String type) {
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"" + type + "\"}").exchange().expectStatus().isCreated();
    }
}
