package com.grassland.identity.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 端到端验证商家权限审核工作流（草场身份域 Slice 2H / D-05 地基）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：owner 申请升级(须高于当前)+PermissionRequested、同级/降级 409、非 owner 403、admin 列 pending、
 * admin 批准→tier 升级+MerchantPermissionGranted、admin 拒绝→tier 不变、终态再审 409、非 admin 审核 403、无 cookie 401。
 */
class PermissionRequestControllerIT extends IdentityItSupport {

    @Test
    void ownerRequestsUpgradePendingAndEvent() {
        var owner = seedAccount("pr-owner@example.com");
        String orgId = createOrg(owner.cookie(), "审核主体");
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"requestedTier\":\"basic_publish\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.requestedTier").isEqualTo("basic_publish")
                .jsonPath("$.data.status").isEqualTo("pending");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'PermissionRequested' AND payload->>'organizationId' = :org")
                .bind("org", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void requestSameTierReturns409() {
        var owner = seedAccount("pr-same@example.com");
        String orgId = createOrg(owner.cookie(), "同级主体"); // 默认 draft
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"requestedTier\":\"draft\"}") // 同级 draft
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerRequestForbidden() {
        var owner = seedAccount("pr-owner2@example.com");
        String orgId = createOrg(owner.cookie(), "成员守卫主体");
        var member = seedAccount("pr-member@example.com");
        // owner 先加 member 进 org
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"member\"}")
                .exchange().expectStatus().isCreated();
        // member 申请 → 403（需 OWNER）
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue("{\"requestedTier\":\"basic_publish\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adminListsPending() {
        var owner = seedAccount("pr-list@example.com");
        String orgId = createOrg(owner.cookie(), "队列主体");
        submitRequest(orgId, owner.cookie(), "basic_publish");
        var admin = seedAdmin("pr-admin-list@example.com");
        client().get().uri("/api/admin/permission-requests").header("Cookie", "y1.sid=" + admin.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isGreaterThan(0));
    }

    @Test
    void adminApproveUpgradesTierAndEvent() {
        var owner = seedAccount("pr-appr@example.com");
        String orgId = createOrg(owner.cookie(), "批准主体");
        String requestId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var admin = seedAdmin("pr-admin-appr@example.com");

        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"approve\",\"note\":\"材料齐全\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("approved")
                .jsonPath("$.data.reviewerAccountId").isEqualTo(admin.accountId());

        // org tier 已升级为 basic_publish
        client().get().uri("/api/organizations/" + orgId).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.permissionTier").isEqualTo("basic_publish");

        Long granted = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'MerchantPermissionGranted' AND payload->>'organizationId' = :org")
                .bind("org", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(granted).isEqualTo(1);
    }

    @Test
    void adminRejectKeepsTier() {
        var owner = seedAccount("pr-rej@example.com");
        String orgId = createOrg(owner.cookie(), "拒绝主体");
        String requestId = submitRequest(orgId, owner.cookie(), "finance_transaction");
        var admin = seedAdmin("pr-admin-rej@example.com");

        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\",\"note\":\"材料不足\"}")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("rejected");

        // tier 仍为 draft（未动）
        client().get().uri("/api/organizations/" + orgId).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.permissionTier").isEqualTo("draft");
    }

    @Test
    void reviewTerminalReturns409() {
        var owner = seedAccount("pr-term@example.com");
        String orgId = createOrg(owner.cookie(), "终态主体");
        String requestId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var admin = seedAdmin("pr-admin-term@example.com");
        // 先批准（终态）
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"approve\"}").exchange().expectStatus().isOk();
        // 再审 → 409
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\"}").exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonAdminReviewForbidden() {
        var owner = seedAccount("pr-na-owner@example.com");
        String orgId = createOrg(owner.cookie(), "非admin主体");
        String requestId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var user = seedAccount("pr-na-user@example.com"); // 普通账号，非 admin
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + user.cookie())
                .bodyValue("{\"decision\":\"approve\"}").exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("pr-nocookie@example.com");
        String orgId = createOrg(owner.cookie(), "无cookie主体");
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"requestedTier\":\"basic_publish\"}")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/permission-requests").exchange().expectStatus().isUnauthorized();
    }

    /** owner 提交升级申请，返回 requestId。 */
    @SuppressWarnings("unchecked")
    private String submitRequest(String orgId, String cookie, String tier) {
        Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"requestedTier\":\"" + tier + "\"}")
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) body.get("data")).get("id");
    }
}
