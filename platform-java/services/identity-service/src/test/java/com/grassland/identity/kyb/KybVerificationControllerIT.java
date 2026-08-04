package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 平台 admin KYB 审核队列。GL-P3-MERCHANT-001。
 *
 * <p>最高危回归：**这三个端点此前完全没有认证**。它们接了 {@code ServerHttpRequest} 却从未校验，
 * 而 identity-service 没有全局 {@code SecurityWebFilterChain}——任何能触达端口的人都能批准自己的 KYB。
 *
 * <p>其次锁住：审核人写的是真实 admin UUID（此前硬编码 {@code "admin"} 字面量，
 * 绑进 {@code CAST(:reviewer AS uuid)} 必然运行期 SQL 报错），以及 rejected 后可改可重提。
 */
class KybVerificationControllerIT extends IdentityItSupport {

    @Autowired
    private KybVerificationRequestRepository verificationRequests;

    @DynamicPropertySource
    static void kek(DynamicPropertyRegistry r) {
        r.add("crypto.kek.encoded", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static int uscc = 0;

    /** 完整资料请求体（POST/PUT 都是全量替换语义，故复用同一份）。 */
    private static String profileBody(String label, String code, String street) {
        return """
                {"legalName":"审核测试 %s","unifiedSocialCreditCode":"%s","businessType":"company",
                 "legalPersonName":"李四","legalPersonIdNumber":"310101199002025678",
                 "businessAddress":{"province":"上海市","city":"上海市","district":"静安区","address":"%s"},
                 "contactPhone":"13900139000"}
                """.formatted(label, code, street);
    }

    /** 建一个资料齐全并已提交的商家，返回 orgId 与其审核请求 id。 */
    private Submitted submitMerchant(String label) {
        var owner = seedAccount("kyb-review-" + label + "-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Review " + label);
        String code = String.format("91310000MA1KR%03d", ++uscc);
        client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(profileBody(label, code, "南京西路 2 号"))
                .exchange().expectStatus().isOk();
        for (String type : new String[]{"business_license", "legal_person_id_front", "legal_person_id_back"}) {
            client().post().uri("/api/organizations/" + orgId + "/merchant-attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", "y1.sid=" + owner.cookie())
                    .bodyValue("{\"attachmentType\":\"" + type + "\",\"mediaReferenceId\":\""
                            + UUID.randomUUID() + "\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":1024}")
                    .exchange().expectStatus().isEqualTo(201);
        }
        client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isEqualTo(201);

        String requestId = db.sql("SELECT id::text FROM kyb_verification_request WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
        return new Submitted(orgId, owner.cookie(), requestId, code);
    }

    private record Submitted(String orgId, String ownerCookie, String requestId, String uscc) {}

    private int reviewStatus(String requestId, String action, String cookie, String note,
                             CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for concurrent KYB review start");
        }
        return client().post().uri("/api/admin/kyb-requests/" + requestId + "/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"note\":\"" + note + "\"}")
                .exchange()
                .returnResult(Void.class)
                .getStatus()
                .value();
    }

    @Test
    @DisplayName("回归：未登录 401、非 admin 成员 403（此前三个端点零认证）")
    void reviewEndpointsRequireAdmin() {
        Submitted s = submitMerchant("authz");

        client().get().uri("/api/admin/kyb-requests").exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/approve")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isUnauthorized();
        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/reject")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"note\":\"nope\"}")
                .exchange().expectStatus().isUnauthorized();

        // 商家自己（普通用户）不能批自己的 KYB。
        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + s.ownerCookie())
                .bodyValue("{}")
                .exchange().expectStatus().isForbidden();

        String status = db.sql("SELECT status FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", s.orgId()).map(row -> row.get(0, String.class)).one().block();
        assertThat(status).isEqualTo("pending");
    }

    @Test
    @DisplayName("admin 能看到待审队列，含审核时限")
    void adminListsPending() {
        Submitted s = submitMerchant("list");
        var admin = seedAdmin("kyb-admin-list-" + UUID.randomUUID() + "@example.com");

        client().get().uri("/api/admin/kyb-requests")
                .header("Cookie", "y1.sid=" + admin.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[?(@.organizationId == '" + s.orgId() + "')].status").isEqualTo("pending")
                .jsonPath("$.data[?(@.organizationId == '" + s.orgId() + "')].requesterAccountId").exists()
                .jsonPath("$.data[?(@.organizationId == '" + s.orgId() + "')].materials").exists()
                .jsonPath("$.data[?(@.organizationId == '" + s.orgId() + "')].reviewDeadline").exists();
    }

    @Test
    @DisplayName("approve：资料转 approved，reviewer 是真实 admin UUID，submittedAt 保留")
    void approveWritesRealReviewer() {
        Submitted s = submitMerchant("approve");
        var admin = seedAdmin("kyb-admin-ok-" + UUID.randomUUID() + "@example.com");

        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"note\":\"材料齐全\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("approved");

        Map<String, Object> row = db.sql("SELECT status, reviewer_account_id::text AS reviewer, "
                        + "submitted_at IS NOT NULL AS has_submitted, reviewed_at IS NOT NULL AS has_reviewed "
                        + "FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", s.orgId())
                .fetch().one().block();
        assertThat(row).isNotNull();
        assertThat(row.get("status")).isEqualTo("approved");
        // 此前硬编码 adminId = "admin" 绑进 CAST(:reviewer AS uuid) → 运行期 SQL 报错。
        assertThat(row.get("reviewer")).isEqualTo(admin.accountId());
        // updateTargetStatus 曾对 submitted_at 传 null 做全列 SET，抹掉提交时间。
        assertThat(row.get("has_submitted")).isEqualTo(true);
        assertThat(row.get("has_reviewed")).isEqualTo(true);

        Long events = db.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :org "
                        + "AND aggregate_type = 'MerchantProfile' AND event_type = 'MerchantProfileApproved'")
                .bind("org", s.orgId()).map(r -> r.get(0, Long.class)).one().block();
        assertThat(events).isEqualTo(1L);

        // 终态请求不可再审。
        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"note\":\"改主意了\"}")
                .exchange().expectStatus().isEqualTo(409);

        // 并发 loser 不能用旧的 pending 快照覆盖终态；仓储条件更新必须返回空。
        assertThat(verificationRequests.updateStatus(
                UUID.fromString(s.requestId()), "rejected", admin.accountId(), "stale review").block())
                .isNull();
    }

    @Test
    @DisplayName("并发 approve/reject 恰好一个成功，目标状态与 outbox 只有一个赢家")
    void concurrentApproveAndRejectHaveSingleWinner() throws Exception {
        Submitted s = submitMerchant("concurrent-decision");
        var approvingAdmin = seedAdmin("kyb-admin-approve-" + UUID.randomUUID() + "@example.com");
        var rejectingAdmin = seedAdmin("kyb-admin-reject-" + UUID.randomUUID() + "@example.com");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        List<Integer> statuses;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var approve = executor.submit(() -> reviewStatus(
                    s.requestId(), "approve", approvingAdmin.cookie(), "approve winner", ready, start));
            var reject = executor.submit(() -> reviewStatus(
                    s.requestId(), "reject", rejectingAdmin.cookie(), "reject winner", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            statuses = List.of(approve.get(20, TimeUnit.SECONDS), reject.get(20, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Map<String, Object> decision = db.sql("""
                        SELECT request.status AS request_status, profile.status AS profile_status,
                               request.reviewer_account_id::text AS request_reviewer,
                               profile.reviewer_account_id::text AS profile_reviewer
                        FROM kyb_verification_request request
                        JOIN merchant_profile profile ON profile.organization_id = request.organization_id
                        WHERE request.id = CAST(:requestId AS uuid)
                        """)
                .bind("requestId", s.requestId()).fetch().one().block();
        assertThat(decision).isNotNull();
        assertThat(decision.get("request_status")).isIn("approved", "rejected");
        assertThat(decision.get("profile_status")).isEqualTo(decision.get("request_status"));
        assertThat(decision.get("profile_reviewer")).isEqualTo(decision.get("request_reviewer"));
        assertThat(decision.get("request_reviewer"))
                .isIn(approvingAdmin.accountId(), rejectingAdmin.accountId());

        Long events = db.sql("""
                        SELECT count(*) FROM outbox
                        WHERE aggregate_id = :org AND aggregate_type = 'MerchantProfile'
                          AND event_type IN ('MerchantProfileApproved', 'MerchantProfileRejected')
                        """)
                .bind("org", s.orgId()).map(row -> row.get(0, Long.class)).one().block();
        assertThat(events).isEqualTo(1L);
    }

    @Test
    @DisplayName("回归：reject 后商家可改可重新提交（此前 rejected 是终态 → 永久锁死）")
    void rejectedMerchantCanResubmit() {
        Submitted s = submitMerchant("reject");
        var admin = seedAdmin("kyb-admin-rej-" + UUID.randomUUID() + "@example.com");

        client().post().uri("/api/admin/kyb-requests/" + s.requestId() + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"note\":\"营业执照模糊\"}")
                .exchange().expectStatus().isOk();

        Map<String, Object> row = db.sql("SELECT status, review_note FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", s.orgId()).fetch().one().block();
        assertThat(row).isNotNull();
        assertThat(row.get("status")).isEqualTo("rejected");
        assertThat(row.get("review_note")).isEqualTo("营业执照模糊");

        client().get().uri("/api/organizations/" + s.orgId() + "/merchant-profile")
                .header("Cookie", "y1.sid=" + s.ownerCookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.reviewNote").isEqualTo("营业执照模糊")
                .jsonPath("$.data.reviewerAccountId").isEqualTo(admin.accountId());

        // rejected 可编辑。
        client().put().uri("/api/organizations/" + s.orgId() + "/merchant-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + s.ownerCookie())
                // PUT 是全量替换（saveFields 写全部数据列），只传单字段会把必填项写空。
                .bodyValue(profileBody("reject", s.uscc(), "南京西路 3 号"))
                .exchange().expectStatus().isOk();

        // 且可重新提交——上一条请求已终态，enqueue 不再 409。
        client().post().uri("/api/organizations/" + s.orgId() + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + s.ownerCookie())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody().jsonPath("$.data.status").isEqualTo("pending");

        Long requests = db.sql("SELECT count(*) FROM kyb_verification_request WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", s.orgId()).map(r -> r.get(0, Long.class)).one().block();
        assertThat(requests).isEqualTo(2L);
    }

    @Test
    @DisplayName("不存在的审核请求 404；畸形 id 400")
    void notFoundAndMalformedId() {
        var admin = seedAdmin("kyb-admin-404-" + UUID.randomUUID() + "@example.com");

        client().post().uri("/api/admin/kyb-requests/" + UUID.randomUUID() + "/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{}")
                .exchange().expectStatus().isNotFound();

        client().post().uri("/api/admin/kyb-requests/not-a-uuid/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{}")
                .exchange().expectStatus().isBadRequest();
    }
}
