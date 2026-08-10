package com.grassland.identity.store;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** End-to-end coverage for the service-only store resource authorization boundary. */
class InternalStoreAuthorizationControllerIT extends IdentityItSupport {

    @Autowired
    IdentityAssertionSigner assertionSigner;

    @Test
    void resolvesStaffManagerAndImplicitOrgAdminRoles() {
        var owner = seedAccount("internal-store-owner@example.com");
        String orgId = createOrg(owner.cookie(), "内部授权主体");
        String storeId = createStore(orgId, owner.cookie(), "旗舰店");
        var manager = seedAccount("internal-store-manager@example.com");
        var staff = seedAccount("internal-store-staff@example.com");
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager");
        addStoreMember(owner.cookie(), orgId, storeId, staff.accountId(), "staff");

        check("marketplace", staff.accountId(), orgId, storeId, "staff")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("staff")
                .jsonPath("$.data.scope").isEqualTo("store");
        check("intelligence", staff.accountId(), orgId, storeId, "manager")
                .expectStatus().isForbidden();
        check("marketplace", manager.accountId(), orgId, storeId, "manager")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.permissionTier").isEqualTo("draft");

        // Org OWNER is not required to have a store_membership row and is still an implicit manager.
        check("intelligence", owner.accountId(), orgId, storeId, "manager")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("manager");
        // Null storeId is the explicit organization-wide resource scope and requires ADMIN/OWNER.
        check("marketplace", owner.accountId(), orgId, null, "manager")
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.scope").isEqualTo("organization");
        check("marketplace", staff.accountId(), orgId, null, "staff")
                .expectStatus().isForbidden();

        client().get().uri("/api/me/store-scopes")
                .header("Cookie", "y1.sid=" + manager.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].storeId").isEqualTo(storeId)
                .jsonPath("$.data[0].organizationId").isEqualTo(orgId)
                .jsonPath("$.data[0].role").isEqualTo("manager")
                .jsonPath("$.data[0].permissionTier").isEqualTo("draft");
    }

    @Test
    void rejectsCrossOrgUntrustedAndMalformedRequests() {
        var ownerA = seedAccount("internal-store-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        String storeA = createStore(orgA, ownerA.cookie(), "A店");
        var ownerB = seedAccount("internal-store-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "主体B");

        check("marketplace", ownerB.accountId(), orgB, storeA, "staff")
                .expectStatus().isNotFound();
        check("trust", ownerA.accountId(), orgA, storeA, "staff")
                .expectStatus().isForbidden();

        client().post().uri("/internal/identity/store-authorizations/check")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", "bad", "organizationId", orgA,
                        "storeId", storeA, "minimumRole", "staff"))
                .exchange().expectStatus().isBadRequest();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec check(
            String principal, String accountId, String orgId, String storeId, String role) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("accountId", accountId);
        body.put("organizationId", orgId);
        if (storeId != null) {
            body.put("storeId", storeId);
        }
        body.put("minimumRole", role);
        return client().post().uri("/internal/identity/store-authorizations/check")
                .header("X-Grassland-Identity", serviceAssertion(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange();
    }

    private void addStoreMember(String cookie, String orgId, String storeId, String accountId, String role) {
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .header("Cookie", "y1.sid=" + cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", accountId, "role", role))
                .exchange().expectStatus().isCreated();
    }

    private String serviceAssertion(String principal) {
        Instant now = Instant.now();
        return assertionSigner.sign(new IdentityAssertion(
                "service:" + principal, null, null, null, null,
                "service", "internal", null, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "grassland-internal", now, now.plusSeconds(30), "service", principal));
    }
}
