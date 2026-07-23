package com.grassland.identity.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 端到端验证商家权限审核工作流（草场身份域 Slice 2H D-05 地基 + Slice 2L 完整规则）。继承 {@link IdentityItSupport}。
 *
 * <p>覆盖：owner 申请升级（带合规 materials）+PermissionRequested、缺料 400、同级 409、非 owner 403、
 * admin 列 pending、批准→tier 升级、拒绝→tier 不变、终态再审 409、非 admin 403、无 cookie 401；
 * Slice 2L：申诉（rejected→201 引用原申请 / 非 rejected→409）、额度查询、slaStatus（within/overdue/completed）、行业快照。
 */
class PermissionRequestControllerIT extends IdentityItSupport {

    @Test
    void ownerRequestsUpgradePendingAndEvent() {
        var owner = seedAccount("pr-owner@example.com");
        String orgId = createOrg(owner.cookie(), "审核主体");
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(bodyFor("basic_publish"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.requestedTier").isEqualTo("basic_publish")
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.industry").isEqualTo("other")
                .jsonPath("$.data.slaStatus").isEqualTo("within")
                .jsonPath("$.data.reviewDeadline").isNotEmpty();

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'PermissionRequested' AND payload->>'organizationId' = :org")
                .bind("org", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void missingRequiredMaterialReturns400() {
        var owner = seedAccount("pr-missing@example.com");
        String orgId = createOrg(owner.cookie(), "缺料主体");
        // finance_transaction 缺 financial_qualification/legal_representative → 400
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"requestedTier\":\"finance_transaction\",\"materials\":{\"business_license\":\"BL\",\"contact_info\":\"c\"}}")
                .exchange().expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").value(m -> assertThat((String) m).contains("financial_qualification"));
    }

    @Test
    void requestSameTierReturns409() {
        var owner = seedAccount("pr-same@example.com");
        String orgId = createOrg(owner.cookie(), "同级主体"); // 默认 draft
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"requestedTier\":\"draft\"}") // 同级 draft（tier 检查先于材料校验）
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerRequestForbidden() {
        var owner = seedAccount("pr-owner2@example.com");
        String orgId = createOrg(owner.cookie(), "成员守卫主体");
        var member = seedAccount("pr-member@example.com");
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountId\":\"" + member.accountId() + "\",\"role\":\"member\"}")
                .exchange().expectStatus().isCreated();
        // member 申请 → 403（需 OWNER，鉴权先于材料校验）
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue(bodyFor("basic_publish"))
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
                .jsonPath("$.data.slaStatus").isEqualTo("completed")
                .jsonPath("$.data.reviewerAccountId").isEqualTo(admin.accountId());

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
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"approve\"}").exchange().expectStatus().isOk();
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\"}").exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonAdminReviewForbidden() {
        var owner = seedAccount("pr-na-owner@example.com");
        String orgId = createOrg(owner.cookie(), "非admin主体");
        String requestId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var user = seedAccount("pr-na-user@example.com");
        client().post().uri("/api/admin/permission-requests/" + requestId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + user.cookie())
                .bodyValue("{\"decision\":\"approve\"}").exchange().expectStatus().isForbidden();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("pr-nocookie@example.com");
        String orgId = createOrg(owner.cookie(), "无cookie主体");
        client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(bodyFor("basic_publish"))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/admin/permission-requests").exchange().expectStatus().isUnauthorized();
    }

    // ---- Slice 2L：申诉 / 额度 / SLA ----

    @Test
    void appealRejectedCreatesPendingReferencingOriginal() {
        var owner = seedAccount("pr-appeal@example.com");
        String orgId = createOrg(owner.cookie(), "申诉主体");
        String originalId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var admin = seedAdmin("pr-admin-appeal@example.com");
        // 先拒
        client().post().uri("/api/admin/permission-requests/" + originalId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\",\"note\":\"证照过期\"}").exchange().expectStatus().isOk();
        // 申诉 → 201，引用原申请
        client().post().uri("/api/organizations/" + orgId + "/permission-requests/" + originalId + "/appeal")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"materials\":{\"business_license\":\"BL-new\",\"contact_info\":\"c\"},\"note\":\"已更新证照\"}")
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.originalRequestId").isEqualTo(originalId)
                .jsonPath("$.data.appealNote").isEqualTo("已更新证照");
    }

    @Test
    void appealNonRejectedReturns409() {
        var owner = seedAccount("pr-appeal409@example.com");
        String orgId = createOrg(owner.cookie(), "申诉409主体");
        String originalId = submitRequest(orgId, owner.cookie(), "basic_publish"); // 仍 pending
        client().post().uri("/api/organizations/" + orgId + "/permission-requests/" + originalId + "/appeal")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"materials\":{\"business_license\":\"BL\",\"contact_info\":\"c\"}}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void getQuotaReturnsTierPolicy() {
        var owner = seedAccount("pr-quota@example.com");
        String orgId = createOrg(owner.cookie(), "额度主体"); // draft
        client().get().uri("/api/organizations/" + orgId + "/quota")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.tier").isEqualTo("draft")
                .jsonPath("$.data.quota.maxActiveTasks").isEqualTo(0)
                .jsonPath("$.data.quota.maxTxAmountCents").isEqualTo(0);
    }

    @Test
    void slaStatusOverdueWhenDeadlinePassed() {
        var owner = seedAccount("pr-overdue@example.com");
        String orgId = createOrg(owner.cookie(), "逾期主体");
        String requestId = submitRequest(orgId, owner.cookie(), "basic_publish");
        var admin = seedAdmin("pr-admin-overdue@example.com");
        // 直接把 review_deadline 改到过去 → overdue
        db.sql("UPDATE merchant_permission_request SET review_deadline = now() - interval '2 days'"
                + " WHERE id = CAST(:id AS uuid)").bind("id", requestId).then().block();
        client().get().uri("/api/admin/permission-requests/" + requestId)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.slaStatus").isEqualTo("overdue");
    }

    /** owner 提交升级申请（带合规 materials），返回 requestId。 */
    @SuppressWarnings("unchecked")
    private String submitRequest(String orgId, String cookie, String tier) {
        Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/permission-requests")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue(bodyFor(tier))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) body.get("data")).get("id");
    }

    /** 按 tier 组装带合规 materials 的请求体。 */
    private static String bodyFor(String tier) {
        String materials = switch (tier) {
            case "finance_transaction" ->
                    "\"materials\":{\"business_license\":\"BL\",\"legal_representative\":\"LR\","
                            + "\"financial_qualification\":\"FQ\",\"contact_info\":\"c\"}";
            default -> "\"materials\":{\"business_license\":\"BL\",\"contact_info\":\"c\"}";
        };
        return "{\"requestedTier\":\"" + tier + "\"," + materials + "}";
    }
}
