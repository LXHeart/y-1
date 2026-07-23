package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证 IdentityProfile 活动身份（草场身份域 Slice 2G）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：开通推荐官/商家、商家须自有 org（非 owner 403）、重复开通 409、激活/切换/切回消费者、激活未开通 409、
 * IdentityOpened/ActiveIdentityChanged 事件、无 cookie 401。outbox 计数按 account 限定。
 */
class IdentityProfileControllerIT extends IdentityItSupport {

    @Test
    void openRecommenderListAndEvent() {
        var acc = seedAccount("ip-rec@example.com");
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.identityType").isEqualTo("recommender")
                .jsonPath("$.data.organizationId").isEmpty();

        client().get().uri("/api/me/identities").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].identityType").isEqualTo("recommender");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'IdentityOpened' AND payload->>'accountId' = :acct")
                .bind("acct", acc.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void openMerchantWithOwnedOrg() {
        var owner = seedAccount("ip-merchant@example.com");
        String orgId = createOrg(owner.cookie(), "商家主体");
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"type\":\"merchant\",\"organizationId\":\"" + orgId + "\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.identityType").isEqualTo("merchant")
                .jsonPath("$.data.organizationId").isEqualTo(orgId);
    }

    @Test
    void openMerchantWithNonOwnedOrgForbidden() {
        var ownerA = seedAccount("ip-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        var ownerB = seedAccount("ip-b@example.com");
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerB.cookie())
                .bodyValue("{\"type\":\"merchant\",\"organizationId\":\"" + orgA + "\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void duplicateOpenReturns409() {
        var acc = seedAccount("ip-dup@example.com");
        openIdentity(acc.cookie(), "recommender").expectStatus().isCreated();
        client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void activateSwitchAndDeactivate() {
        var acc = seedAccount("ip-act@example.com");
        openIdentity(acc.cookie(), "merchant");
        openIdentity(acc.cookie(), "recommender");

        // 激活 merchant
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"merchant\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEqualTo("merchant");

        // 切到 recommender（同一时间仅一个）
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEqualTo("recommender");

        // GET 当前活动身份
        client().get().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeIdentityType").isEqualTo("recommender");

        // 切回消费者 → DB active_identity_type 为 NULL（用 COUNT 避免把可空列映射进 Flux 触发 reactor 禁止 null）
        client().delete().uri("/api/me/active-identity").header("Cookie", "y1.sid=" + acc.cookie())
                .exchange().expectStatus().isOk();
        Long nullActive = db.sql("SELECT COUNT(*)::int AS c FROM account_active_identity"
                        + " WHERE account_id = CAST(:acct AS uuid) AND active_identity_type IS NULL")
                .bind("acct", acc.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(nullActive).isEqualTo(1);
    }

    @Test
    void activateUnopenedReturns409() {
        var acc = seedAccount("ip-unopen@example.com");
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void activeIdentityChangedEvent() {
        var acc = seedAccount("ip-evt@example.com");
        openIdentity(acc.cookie(), "recommender");
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + acc.cookie())
                .bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isOk();

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'ActiveIdentityChanged' AND aggregate_id = :acct")
                .bind("acct", acc.accountId())
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        client().get().uri("/api/me/identities").exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/me/active-identity")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"type\":\"recommender\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec openIdentity(String cookie, String type) {
        return client().post().uri("/api/me/identities")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"type\":\"" + type + "\"}")
                .exchange();
    }
}
