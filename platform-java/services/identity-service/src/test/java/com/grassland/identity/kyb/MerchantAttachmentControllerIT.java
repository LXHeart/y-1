package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * KYB 附件。GL-P3-MERCHANT-001。
 *
 * <p>核心回归：**删除必须按 (id, organizationId) 双条件定位**。此前只按 id 查删，
 * 任意组织 ADMIN 拿到别家附件 id 即可删除对方审核材料。
 */
class MerchantAttachmentControllerIT extends IdentityItSupport {

    private String createAttachment(String orgId, String cookie, String type, int expectedStatus) {
        var body = client().post().uri("/api/organizations/" + orgId + "/merchant-attachments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"attachmentType\":\"" + type + "\",\"mediaReferenceId\":\""
                        + UUID.randomUUID() + "\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":2048}")
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();
        if (expectedStatus != 201) {
            return null;
        }
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) body.get("data");
        return (String) data.get("id");
    }

    @Test
    @DisplayName("同一类型证件重复上传 → 409（唯一部分索引）")
    void duplicateDocumentTypeIsConflict() {
        var owner = seedAccount("kyb-att-dup-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Dup Org");

        createAttachment(orgId, owner.cookie(), "business_license", 201);
        createAttachment(orgId, owner.cookie(), "business_license", 409);
        // 非证件类（门店照片）允许多张。
        createAttachment(orgId, owner.cookie(), "store_photo", 201);
        createAttachment(orgId, owner.cookie(), "store_photo", 201);
    }

    @Test
    @DisplayName("附件类型非法 → 400")
    void invalidTypeIsBadRequest() {
        var owner = seedAccount("kyb-att-bad-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Bad Org");

        createAttachment(orgId, owner.cookie(), "passport_scan", 400);
    }

    @Test
    @DisplayName("回归：跨组织删除他人附件 → 404，且原附件仍在")
    void crossOrgDeleteIsNotFound() {
        var victim = seedAccount("kyb-att-victim-" + UUID.randomUUID() + "@example.com");
        String victimOrg = createOrg(victim.cookie(), "Attachment Victim Org");
        String attachmentId = createAttachment(victimOrg, victim.cookie(), "business_license", 201);

        var attacker = seedAccount("kyb-att-attacker-" + UUID.randomUUID() + "@example.com");
        String attackerOrg = createOrg(attacker.cookie(), "Attachment Attacker Org");

        // 攻击者在自己 org 下是 ADMIN，authz 放行；只有仓储层的 organization_id 条件挡得住。
        client().delete().uri("/api/organizations/" + attackerOrg + "/merchant-attachments/" + attachmentId)
                .header("Cookie", "y1.sid=" + attacker.cookie())
                .exchange()
                .expectStatus().isNotFound();

        Long remaining = db.sql("SELECT count(*) FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", attachmentId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    @DisplayName("本组织删除自己的附件 → 200 且真的删掉")
    void ownDeleteSucceeds() {
        var owner = seedAccount("kyb-att-own-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Own Org");
        String attachmentId = createAttachment(orgId, owner.cookie(), "business_license", 201);

        client().delete().uri("/api/organizations/" + orgId + "/merchant-attachments/" + attachmentId)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isOk();

        Long remaining = db.sql("SELECT count(*) FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", attachmentId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("畸形附件 ID → 400（此前 UUID.fromString 冒成 500）")
    void malformedIdIsBadRequest() {
        var owner = seedAccount("kyb-att-uuid-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Uuid Org");

        client().delete().uri("/api/organizations/" + orgId + "/merchant-attachments/not-a-uuid")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("列表按组织隔离，未登录 401")
    void listIsScopedToOrganization() {
        var owner = seedAccount("kyb-att-list-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment List Org");
        createAttachment(orgId, owner.cookie(), "business_license", 201);

        client().get().uri("/api/organizations/" + orgId + "/merchant-attachments")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1);

        client().get().uri("/api/organizations/" + orgId + "/merchant-attachments")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
