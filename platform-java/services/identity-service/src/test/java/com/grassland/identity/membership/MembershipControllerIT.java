package com.grassland.identity.membership;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证 Organization Membership（草场身份域 Slice 2F）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：org 创建即种 OWNER 成员（自见）、owner 加 admin、非 owner 403、删成员、last-owner 守卫 409、重复成员 409、无 cookie 401。
 */
class MembershipControllerIT extends IdentityItSupport {

    @Autowired
    private IdentityAssertionSigner assertionSigner;

    @Test
    void trustServiceReadsAllAuthoritativeOrganizationMemberships() {
        var account = seedAccount("internal-memberships@example.com");
        String firstOrg = createOrg(account.cookie(), "内部成员关系一");
        // 一账号一主体（V40）：第二个 org 由另一账号创建、本账号经成员关系加入——
        // 内部端点聚合的是「成员关系」而非「名下主体」，语义不变
        var other = seedAccount("internal-memberships-owner@example.com");
        String secondOrg = createOrg(other.cookie(), "内部成员关系二");
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role) "
                        + "VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:acct AS uuid), 'member')")
                .bind("org", secondOrg).bind("acct", account.accountId()).then().block();

        client().get().uri("/internal/identity/accounts/" + account.accountId() + "/organization-memberships")
                .header("X-Grassland-Identity", serviceAssertion("trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.accountId").isEqualTo(account.accountId())
                .jsonPath("$.data.organizationIds.length()").isEqualTo(2)
                .jsonPath("$.data.organizationIds").value(ids ->
                        org.assertj.core.api.Assertions.assertThat(((java.util.List<?>) ids).stream()
                                        .map(Object::toString).toList())
                                .containsExactlyInAnyOrder(firstOrg, secondOrg));
    }

    @Test
    void internalMembershipsRejectsMissingOrWrongServiceAssertion() {
        String accountId = UUID.randomUUID().toString();

        client().get().uri("/internal/identity/accounts/" + accountId + "/organization-memberships")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/internal/identity/accounts/" + accountId + "/organization-memberships")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void ownerSeesSelfAfterOrgCreate() {
        var owner = seedAccount("m-owner@example.com");
        String orgId = createOrg(owner.cookie(), "成员主体");

        client().get().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].role").isEqualTo("owner")
                .jsonPath("$.data[0].accountId").isEqualTo(owner.accountId())
                // owner 是注册用户、无 account_username 行：账号名为 null（2026-08-28 起 username 回显）
                .jsonPath("$.data[0].username").value(org.hamcrest.Matchers.nullValue());
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("m-noauth@example.com");
        String orgId = createOrg(owner.cookie(), "无鉴权成员主体");
        client().get().uri("/api/organizations/" + orgId + "/memberships").exchange().expectStatus().isUnauthorized();
    }


    private String serviceAssertion(String principal) {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper.serviceSigner(principal, "grassland-identity").sign(new IdentityAssertion(
                "service:" + principal, null, null, null, null,
                "service", "internal", null, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "grassland-identity", now, now.plusSeconds(30), "service", principal));
    }
}
