package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 端到端验证商家三级准入权限（草场身份域 Slice 2F）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：新建 org 默认 draft、grant 单调升级 + outbox {@code MerchantPermissionGranted}、同级/降级 → 409、非 owner → 403。
 */
class PermissionGrantIT extends IdentityItSupport {

    @Test
    void newOrgDefaultsToDraftTier() {
        var owner = seedAccount("grant-owner@example.com");
        String orgId = createOrg(owner.cookie(), "权限主体");

        client().get().uri("/api/organizations/" + orgId).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.permissionTier").isEqualTo("draft");
    }

    @Test
    void grantUpgradesTierAndEmitsEvent() {
        var owner = seedAccount("grant-up@example.com");
        String orgId = createOrg(owner.cookie(), "升级主体");

        client().post().uri("/api/organizations/" + orgId + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"tier\":\"basic_publish\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.permissionTier").isEqualTo("basic_publish");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'MerchantPermissionGranted' AND payload->>'organizationId' = :orgId")
                .bind("orgId", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void grantRejectsSameOrLowerTier() {
        var owner = seedAccount("grant-rej@example.com");
        String orgId = createOrg(owner.cookie(), "拒绝降级主体");

        // 同级（draft → draft）→ 409
        client().post().uri("/api/organizations/" + orgId + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"tier\":\"draft\"}")
                .exchange().expectStatus().isEqualTo(409);

        // 先升到 finance_transaction
        client().post().uri("/api/organizations/" + orgId + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"tier\":\"finance_transaction\"}")
                .exchange().expectStatus().isOk();

        // 降级（finance → basic_publish）→ 409
        client().post().uri("/api/organizations/" + orgId + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"tier\":\"basic_publish\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerCannotGrant() {
        var owner = seedAccount("g-no@example.com");
        String orgId = createOrg(owner.cookie(), "非owner主体");
        var member = seedAccount("g-member@example.com");

        // owner 先把 member 加进组织
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"member\"}")
                .exchange().expectStatus().isCreated();

        // member 尝试 grant → 403
        client().post().uri("/api/organizations/" + orgId + "/permissions/grant")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue("{\"tier\":\"basic_publish\"}")
                .exchange().expectStatus().isForbidden();
    }
}
