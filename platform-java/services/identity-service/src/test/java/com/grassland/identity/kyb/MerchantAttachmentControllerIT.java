package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grassland.identity.IdentityItSupport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Sinks;

/**
 * KYB 附件。GL-P3-MERCHANT-001。
 *
 * <p>核心回归：**删除必须按 (id, organizationId) 双条件定位**。此前只按 id 查删，
 * 任意组织 ADMIN 拿到别家附件 id 即可删除对方审核材料。
 */
class MerchantAttachmentControllerIT extends IdentityItSupport {

    @Autowired
    private TransactionalOperator transactions;

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
    @DisplayName("资料进入审核后附件不可新增或删除")
    void submittedProfileFreezesAttachments() {
        var owner = seedAccount("kyb-att-frozen-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Frozen Org");
        String attachmentId = createAttachment(orgId, owner.cookie(), "business_license", 201);

        db.sql("INSERT INTO merchant_profile(organization_id, status) VALUES (CAST(:org AS uuid), 'pending')")
                .bind("org", orgId).fetch().rowsUpdated().block();

        createAttachment(orgId, owner.cookie(), "store_photo", 409);
        client().delete().uri("/api/organizations/" + orgId + "/merchant-attachments/" + attachmentId)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isEqualTo(409);

        Long remaining = db.sql("SELECT count(*) FROM merchant_attachment WHERE id = CAST(:id AS uuid)")
                .bind("id", attachmentId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    @DisplayName("附件变更与资料提交按同一资料行串行化")
    void attachmentMutationWaitsForConcurrentSubmission() throws Exception {
        var owner = seedAccount("kyb-att-race-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Attachment Race Org");
        String attachmentId = createAttachment(orgId, owner.cookie(), "business_license", 201);

        CountDownLatch statusUpdated = new CountDownLatch(1);
        Sinks.One<Void> releaseSubmission = Sinks.one();
        CompletableFuture<Void> submission = transactions.transactional(
                        db.sql("SELECT id::text FROM organization "
                                        + "WHERE id = CAST(:org AS uuid) FOR UPDATE")
                                .bind("org", orgId).map(row -> row.get(0, String.class)).one()
                                .flatMap(ignored -> db.sql("INSERT INTO merchant_profile(organization_id, status) "
                                                + "VALUES (CAST(:org AS uuid), 'pending')")
                                        .bind("org", orgId).fetch().rowsUpdated())
                                .doOnNext(ignored -> statusUpdated.countDown())
                                .then(releaseSubmission.asMono()))
                .then().toFuture();

        assertThat(statusUpdated.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> deletion = CompletableFuture.supplyAsync(() -> client().delete()
                .uri("/api/organizations/" + orgId + "/merchant-attachments/" + attachmentId)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .returnResult(Void.class)
                .getStatus().value());
        try {
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                Long waiting = db.sql("SELECT count(*) FROM pg_stat_activity "
                                + "WHERE datname = current_database() AND wait_event_type = 'Lock' "
                                + "AND query ILIKE '%organization%' AND query ILIKE '%FOR UPDATE%'")
                        .map(row -> row.get(0, Long.class)).one().block();
                assertThat(waiting).isGreaterThan(0L);
            });
            assertThat(deletion).isNotDone();
        } finally {
            releaseSubmission.tryEmitEmpty();
        }

        assertThat(deletion.get(5, TimeUnit.SECONDS)).isEqualTo(409);
        submission.get(5, TimeUnit.SECONDS);
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
