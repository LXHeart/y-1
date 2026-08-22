package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.grassland.identity.IdentityItSupport;

/**
 * 商家主体更名审核流 + 创建限一（V40 / 2026-08-23 产品规则）：
 * - 一个账号只能创建一个商家主体（第二次 POST /api/organizations → 409）；
 * - 更名申请 → 平台审核通过才生效；驳回留痕；
 * - 30 天冷却：自创建（或上次更名生效）起不可再次申请；
 * - 同一主体同时只有一份待审；非平台 admin 不可审核。
 */
class OrganizationRenameIT extends IdentityItSupport {

    @Autowired
    private DatabaseClient rawDb;

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Test
    void accountCanCreateOnlyOneOrganization() {
        WebTestClient client = client();
        Seeded owner = seedAccount("one-org-" + UUID.randomUUID() + "@example.com");

        client.post().uri("/api/organizations").contentType(JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"第一个主体\"}")
                .exchange().expectStatus().isCreated();

        client.post().uri("/api/organizations").contentType(JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"第二个主体\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameRequiresReviewAndAppliesOnApproval() {
        WebTestClient client = client();
        Seeded owner = seedAccount("rename-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "旧名称");

        // 冷却期内（刚创建）不可申请
        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"新名称\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(msg -> assertThat((String) msg).contains("冷却"));

        // 冷却到期：把创建时间回拨 31 天后可申请
        rawDb.sql("UPDATE organization SET created_at = now() - interval '31 days' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();
        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"新名称\"}")
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.data.status").isEqualTo("pending");

        // 待审期间重复提交 → 409
        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"再次改名\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(msg -> assertThat((String) msg).contains("待审核"));

        // 普通用户不可审核
        Seeded other = seedAccount("noadmin-" + UUID.randomUUID() + "@example.com");
        client.get().uri("/api/admin/org-rename-requests").header("Cookie", "y1.sid=" + other.cookie())
                .exchange().expectStatus().isEqualTo(403);

        // 平台 admin 审核通过 → 名称生效
        Seeded admin = seedAdmin("rename-admin-" + UUID.randomUUID() + "@example.com");
        List<Map<String, Object>> queue = (List<Map<String, Object>>) ((Map<String, Object>) client.get()
                .uri("/api/admin/org-rename-requests").header("Cookie", "y1.sid=" + admin.cookie())
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody())
                .get("data");
        String requestId = ((String) ((Map<String, Object>) queue.stream()
                .filter(r -> orgId.equals(r.get("organizationId"))).findFirst().orElseThrow()).get("id"));

        client.post().uri("/api/admin/org-rename-requests/" + requestId + "/review").contentType(JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"approve\",\"note\":\"材料齐全\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("approved");

        String name = (String) rawDb.sql("SELECT name FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).map(r -> r.get(0, String.class)).one().block();
        assertThat(name).isEqualTo("新名称");

        // 审核后立即再申请 → 冷却（自本次更名生效起算）
        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"又想改\"}")
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(msg -> assertThat((String) msg).contains("冷却"));

        // 重复审核终态 → 409
        client.post().uri("/api/admin/org-rename-requests/" + requestId + "/review").contentType(JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @SuppressWarnings("unchecked")
    void renameRejectionKeepsNameAndLeavesTrace() {
        WebTestClient client = client();
        Seeded owner = seedAccount("reject-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "保持不变");
        rawDb.sql("UPDATE organization SET created_at = now() - interval '31 days' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();

        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"想改的名字\"}")
                .exchange().expectStatus().isCreated();

        Seeded admin = seedAdmin("reject-admin-" + UUID.randomUUID() + "@example.com");
        List<Map<String, Object>> queue = (List<Map<String, Object>>) ((Map<String, Object>) client.get()
                .uri("/api/admin/org-rename-requests").header("Cookie", "y1.sid=" + admin.cookie())
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody())
                .get("data");
        String requestId = ((String) ((Map<String, Object>) queue.stream()
                .filter(r -> orgId.equals(r.get("organizationId"))).findFirst().orElseThrow()).get("id"));

        client.post().uri("/api/admin/org-rename-requests/" + requestId + "/review").contentType(JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"decision\":\"reject\",\"note\":\"名称与营业执照不符\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("rejected");

        String name = (String) rawDb.sql("SELECT name FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).map(r -> r.get(0, String.class)).one().block();
        assertThat(name).isEqualTo("保持不变");

        // 驳回后可立即重新申请（冷却按「生效」算，驳回不占周期）
        client.post().uri("/api/organizations/" + orgId + "/rename-requests").contentType(JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"修正后的名字\"}")
                .exchange().expectStatus().isCreated();
    }
}
