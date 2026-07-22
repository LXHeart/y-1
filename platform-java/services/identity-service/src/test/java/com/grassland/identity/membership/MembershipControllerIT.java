package com.grassland.identity.membership;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证 Organization Membership（草场身份域 Slice 2F）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：org 创建即种 OWNER 成员（自见）、owner 加 admin、非 owner 403、删成员、last-owner 守卫 409、重复成员 409、无 cookie 401。
 */
class MembershipControllerIT extends IdentityItSupport {

    @Test
    void ownerSeesSelfAfterOrgCreate() {
        var owner = seedAccount("m-owner@example.com");
        String orgId = createOrg(owner.cookie(), "成员主体");

        client().get().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].role").isEqualTo("owner")
                .jsonPath("$.data[0].accountId").isEqualTo(owner.accountId());
    }

    @Test
    void ownerAddsAdminAndNonOwnerBlocked() {
        var owner = seedAccount("add-owner@example.com");
        String orgId = createOrg(owner.cookie(), "加成员主体");
        var admin = seedAccount("admin1@example.com");

        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + admin.accountId() + "\",\"role\":\"admin\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.role").isEqualTo("admin")
                .jsonPath("$.data.accountId").isEqualTo(admin.accountId());

        // admin（非 owner）尝试再加成员 → 403
        var other = seedAccount("other@example.com");
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"accountId\":\"" + other.accountId() + "\",\"role\":\"member\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void ownerRemovesMember() {
        var owner = seedAccount("rm-owner@example.com");
        String orgId = createOrg(owner.cookie(), "删成员主体");
        var member = seedAccount("member1@example.com");
        addMember(orgId, owner.cookie(), member.accountId(), "member").expectStatus().isCreated();

        client().delete().uri("/api/organizations/" + orgId + "/memberships/" + member.accountId())
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isOk();

        client().get().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(1);
    }

    @Test
    void cannotRemoveLastOwner() {
        var owner = seedAccount("last-owner@example.com");
        String orgId = createOrg(owner.cookie(), "末位主体");

        client().delete().uri("/api/organizations/" + orgId + "/memberships/" + owner.accountId())
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void duplicateMemberReturns409() {
        var owner = seedAccount("dup-owner@example.com");
        String orgId = createOrg(owner.cookie(), "重复成员主体");
        var member = seedAccount("dup@example.com");

        addMember(orgId, owner.cookie(), member.accountId(), "member").expectStatus().isCreated();
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"member\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void cannotGrantOwnerRoleViaEndpoint() {
        var owner = seedAccount("no-owner-grant@example.com");
        String orgId = createOrg(owner.cookie(), "禁止授予owner主体");
        var member = seedAccount("would-be-owner@example.com");

        // CreateMembershipRequest 校验拒绝 role=owner → 反序列化失败 → 4xx（绝不应 201 落库 owner 成员）
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"owner\"}")
                .exchange().expectStatus().is4xxClientError();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("m-noauth@example.com");
        String orgId = createOrg(owner.cookie(), "无鉴权成员主体");
        client().get().uri("/api/organizations/" + orgId + "/memberships").exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec addMember(String orgId, String cookie, String accountId, String role) {
        return client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange();
    }
}
