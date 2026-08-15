package com.grassland.identity.membership;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 组织角色内部授权边界端到端覆盖（镜像 InternalStoreAuthorizationControllerIT）。
 * 锁定：owner 兜底（无成员行）、member/admin/owner 单调判定、非成员 403、跨 org/组织不存在 404、
 * 非受信服务 403、非法参数 400，以及 /api/me/organization-scopes 的本人视角角色解析。
 */
class InternalOrgAuthorizationControllerIT extends IdentityItSupport {

    @Autowired
    IdentityAssertionSigner assertionSigner;

    @Test
    void resolvesOwnerAdminMemberRolesWithMonotonicMinimumRole() {
        var owner = seedAccount("internal-org-owner@example.com");
        String orgId = createOrg(owner.cookie(), "内部组织授权主体");
        var admin = seedAccount("internal-org-admin@example.com");
        var member = seedAccount("internal-org-member@example.com");
        addOrgMember(owner.cookie(), orgId, admin.accountId(), "admin");
        addOrgMember(owner.cookie(), orgId, member.accountId(), "member");

        // owner 无成员行，owner_account_id 兜底仍判 owner。
        check("intelligence", owner.accountId(), orgId, "owner")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("owner");
        check("marketplace", admin.accountId(), orgId, "admin")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("admin");
        check("intelligence", admin.accountId(), orgId, "owner")
                .expectStatus().isForbidden();
        check("marketplace", member.accountId(), orgId, "member")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("member");
        // member 管理组织级资源（要求 admin）→ 403。
        check("intelligence", member.accountId(), orgId, "admin")
                .expectStatus().isForbidden();

        // 本人视角：成员行角色 + owner 兜底组织都可见。
        client().get().uri("/api/me/organization-scopes")
                .header("Cookie", "y1.sid=" + member.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].organizationId").isEqualTo(orgId)
                .jsonPath("$.data[0].role").isEqualTo("member");
        client().get().uri("/api/me/organization-scopes")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].role").isEqualTo("owner");
    }

    @Test
    void rejectsNonMemberUnknownOrgUntrustedAndMalformedRequests() {
        var ownerA = seedAccount("internal-org-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        var ownerB = seedAccount("internal-org-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "主体B");
        var outsider = seedAccount("internal-org-outsider@example.com");

        // 非成员（无成员行且非 owner）→ 403。
        check("marketplace", outsider.accountId(), orgA, "member")
                .expectStatus().isForbidden();
        // 组织不存在 → 404。
        check("intelligence", ownerA.accountId(), UUID.randomUUID().toString(), "member")
                .expectStatus().isNotFound();
        // 非受信服务 principal → 403。
        check("trust", ownerA.accountId(), orgA, "member")
                .expectStatus().isForbidden();
        // 非法 minimumRole / accountId → 400。
        client().post().uri("/internal/identity/organization-authorizations/check")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", ownerA.accountId(), "organizationId", orgA,
                        "minimumRole", "superuser"))
                .exchange().expectStatus().isBadRequest();
        client().post().uri("/internal/identity/organization-authorizations/check")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", "bad", "organizationId", orgA, "minimumRole", "member"))
                .exchange().expectStatus().isBadRequest();
        // 未登录调用 /api/me/organization-scopes → 401。
        client().get().uri("/api/me/organization-scopes")
                .exchange().expectStatus().isUnauthorized();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec check(
            String principal, String accountId, String orgId, String minimumRole) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("organizationId", orgId);
        body.put("minimumRole", minimumRole);
        return client().post().uri("/internal/identity/organization-authorizations/check")
                .header("X-Grassland-Identity", serviceAssertion(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange();
    }

    private void addOrgMember(String cookie, String orgId, String accountId, String role) {
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", accountId, "role", role))
                .exchange().expectStatus().isCreated();
    }

    private String serviceAssertion(String principal) {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper.serviceSigner(principal, "grassland-identity").sign(new IdentityAssertion(
                "service:" + principal, null, null, null, null,
                "service", "internal", null, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "grassland-identity", now, now.plusSeconds(30), "service", principal));
    }
}
