package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证 Store-scoped Membership（草场身份域 Slice 2G）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：org admin 加门店成员/列/移除、重复 409、非 org 成员 403、跨 org storeId 404、StoreMembershipGranted 事件、无 cookie 401。
 */
class StoreMembershipControllerIT extends IdentityItSupport {

    @Test
    void addListRemoveAndEvent() {
        var owner = seedAccount("sm-owner@example.com");
        String orgId = createOrg(owner.cookie(), "门店成员主体");
        String storeId = createStore(orgId, owner.cookie(), "测试门店");
        var member = seedAccount("sm-member@example.com");

        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"staff\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.role").isEqualTo("staff")
                .jsonPath("$.data.accountId").isEqualTo(member.accountId());

        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1);

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'StoreMembershipGranted' AND payload->>'storeId' = :sid")
                .bind("sid", storeId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);

        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships/" + member.accountId())
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isOk();
        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void duplicateReturns409() {
        var owner = seedAccount("sm-dup@example.com");
        String orgId = createOrg(owner.cookie(), "重复门店成员主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        var member = seedAccount("sm-dup-m@example.com");
        addMember(orgId, storeId, owner.cookie(), member.accountId(), "staff").expectStatus().isCreated();
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"staff\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOrgMemberForbidden() {
        var ownerA = seedAccount("sm-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        String storeA = createStore(orgA, ownerA.cookie(), "A店");
        var ownerB = seedAccount("sm-b@example.com"); // 非 orgA 成员
        var member = seedAccount("sm-m@example.com");
        client().post().uri("/api/organizations/" + orgA + "/stores/" + storeA + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerB.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"staff\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void crossOrgStoreReturns404() {
        var ownerA = seedAccount("sm-ca@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        String storeA = createStore(orgA, ownerA.cookie(), "A店");
        var ownerB = seedAccount("sm-cb@example.com");
        String orgB = createOrg(ownerB.cookie(), "主体B");
        var member = seedAccount("sm-cm@example.com");
        // ownerB 在自己 orgB 下管理 storeA（实属 orgA）→ store 不在 orgB → 404（跨 org 隔离）
        client().post().uri("/api/organizations/" + orgB + "/stores/" + storeA + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerB.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"staff\"}")
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("sm-na@example.com");
        String orgId = createOrg(owner.cookie(), "无鉴权门店主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec addMember(String orgId, String storeId, String cookie, String accountId, String role) {
        return client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange();
    }
}
