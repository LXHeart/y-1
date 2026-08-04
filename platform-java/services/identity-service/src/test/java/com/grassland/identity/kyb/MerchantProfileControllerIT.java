package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 商家 KYB 资料与提交闭环。GL-P3-MERCHANT-001。
 *
 * <p>{@code 9def15e} 留下的 KYB 脚手架能编译但从未被守卫/接线/测试。本类锁住其中的高危回归：
 * <ul>
 *   <li>已通过审核的资料不能被一次 POST 静默打回 draft；</li>
 *   <li>提交前必填字段与证件材料齐备校验；</li>
 *   <li>提交真的写出 {@code kyb_verification_request} 行（此前 admin 队列恒空）；</li>
 *   <li>身份证号密文入库、响应体只回末 4 位。</li>
 * </ul>
 */
class MerchantProfileControllerIT extends IdentityItSupport {

    @DynamicPropertySource
    static void kek(DynamicPropertyRegistry r) {
        // 敏感字段加密 fail-closed，测试里必须显式给 KEK 才能走通含身份证号的路径。
        r.add("crypto.kek.encoded", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    private String draftBody(String usccSuffix) {
        return """
                {"legalName":"草场测试商贸有限公司",
                 "unifiedSocialCreditCode":"91310000MA1K%s",
                 "businessType":"company",
                 "legalPersonName":"张三",
                 "legalPersonIdNumber":"310101199001011234",
                 "registeredCapitalCents":100000000,
                 "establishmentDate":"2020-06-01",
                 "businessAddress":{"province":"上海市","city":"上海市","district":"黄浦区",
                                    "address":"南京东路 1 号"},
                 "contactPhone":"13800138000",
                 "contactEmail":"kyb@example.com"}
                """.formatted(usccSuffix);
    }

    private void postDraft(String orgId, String cookie, String body, int expectedStatus) {
        client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus);
    }

    private void uploadAllDocuments(String orgId, String cookie) {
        for (String type : new String[]{"business_license", "legal_person_id_front", "legal_person_id_back"}) {
            client().post().uri("/api/organizations/" + orgId + "/merchant-attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", "y1.sid=" + cookie)
                    .bodyValue("{\"attachmentType\":\"" + type + "\",\"mediaReferenceId\":\""
                            + UUID.randomUUID() + "\",\"mimeType\":\"image/jpeg\",\"sizeBytes\":102400}")
                    .exchange()
                    .expectStatus().isEqualTo(201);
        }
    }

    @Test
    @DisplayName("未登录 401；非 ADMIN 成员 403")
    void authorization() {
        var owner = seedAccount("kyb-authz-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Authz Org");

        client().post().uri("/api/organizations/" + orgId + "/merchant-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(draftBody("0001"))
                .exchange()
                .expectStatus().isUnauthorized();

        var outsider = seedAccount("kyb-authz-out-" + UUID.randomUUID() + "@example.com");
        postDraft(orgId, outsider.cookie(), draftBody("0002"), 403);
    }

    @Test
    @DisplayName("草稿 upsert：身份证号密文入库，响应只回末 4 位掩码")
    void draftEncryptsIdNumber() {
        var owner = seedAccount("kyb-draft-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Draft Org");

        postDraft(orgId, owner.cookie(), draftBody("0101"), 200);

        client().get().uri("/api/organizations/" + orgId + "/merchant-profile")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.legalPersonIdNumberMasked").isEqualTo("****1234")
                .jsonPath("$.data.status").isEqualTo("draft")
                // 明文字段名不该出现在响应里。
                .jsonPath("$.data.legalPersonIdNumber").doesNotExist();

        String stored = db.sql("SELECT legal_person_id_number FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
        assertThat(stored).isNotNull().doesNotContain("310101199001011234");
    }

    @Test
    @DisplayName("资料不完整时提交 → 400 且列出缺失字段")
    void submitRejectsIncompleteProfile() {
        var owner = seedAccount("kyb-incomplete-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Incomplete Org");

        // 只填法定名称，其余留空——此前 Map.of 装可空列直接 NPE→500。
        postDraft(orgId, owner.cookie(), "{\"legalName\":\"仅名称\"}", 200);

        client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").value(msg -> assertThat((String) msg)
                        .contains("统一社会信用代码").contains("法人姓名").contains("经营地址"));
    }

    @Test
    @DisplayName("材料不齐时提交 → 400 且列出缺失材料")
    void submitRejectsMissingDocuments() {
        var owner = seedAccount("kyb-nodocs-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB NoDocs Org");
        postDraft(orgId, owner.cookie(), draftBody("0201"), 200);

        client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").value(msg -> assertThat((String) msg).contains("营业执照"));
    }

    @Test
    @DisplayName("资料齐全提交 → pending + 写出审核队列行 + outbox 事件")
    void submitEnqueuesReviewRequest() {
        var owner = seedAccount("kyb-submit-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Submit Org");
        postDraft(orgId, owner.cookie(), draftBody("0301"), 200);
        uploadAllDocuments(orgId, owner.cookie());

        client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody().jsonPath("$.data.status").isEqualTo("pending");

        // 审核队列行必须存在——此前 create 全仓无调用方，admin 队列恒空、approve 不可达。
        Long requests = db.sql("SELECT count(*) FROM kyb_verification_request "
                        + "WHERE organization_id = CAST(:org AS uuid) AND verification_type = 'merchant_profile' AND status = 'pending'")
                .bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(requests).isEqualTo(1L);

        // 材料快照非空：审核人看到的是提交那一刻的附件集合。
        String materials = db.sql("SELECT materials::text FROM kyb_verification_request WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
        assertThat(materials).isNotNull().startsWith("[");

        Long events = db.sql("SELECT count(*) FROM outbox "
                        + "WHERE aggregate_id = :org AND event_type = 'MerchantProfileSubmitted'")
                .bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(events).isEqualTo(1L);

        // 重复提交：已在审核中，既不可编辑也不该堆出第二条待审。
        client().post().uri("/api/organizations/" + orgId + "/merchant-profile/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isEqualTo(409);
        postDraft(orgId, owner.cookie(), draftBody("0302"), 409);
    }

    @Test
    @DisplayName("回归：POST 不能把已通过审核的资料打回 draft")
    void postDoesNotRevertApprovedStatus() {
        var owner = seedAccount("kyb-approved-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB Approved Org");
        postDraft(orgId, owner.cookie(), draftBody("0401"), 200);
        db.sql("UPDATE merchant_profile SET status = 'approved' WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).then().block();

        // 此前 upsert 无条件 status = EXCLUDED.status，一次 POST 就把 approved 静默改回 draft。
        postDraft(orgId, owner.cookie(), draftBody("0402"), 409);

        String status = db.sql("SELECT status FROM merchant_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, String.class)).one().block();
        assertThat(status).isEqualTo("approved");
    }

    @Test
    @DisplayName("统一社会信用代码重复 → 409 而非 500")
    void duplicateUsccIsConflict() {
        var first = seedAccount("kyb-dup-a-" + UUID.randomUUID() + "@example.com");
        String firstOrg = createOrg(first.cookie(), "KYB Dup A");
        postDraft(firstOrg, first.cookie(), draftBody("0501"), 200);

        var second = seedAccount("kyb-dup-b-" + UUID.randomUUID() + "@example.com");
        String secondOrg = createOrg(second.cookie(), "KYB Dup B");
        postDraft(secondOrg, second.cookie(), draftBody("0501"), 409);
    }

    @Test
    @DisplayName("成立日期格式非法 → 400 而非 500")
    void malformedDateIsBadRequest() {
        var owner = seedAccount("kyb-baddate-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "KYB BadDate Org");

        postDraft(orgId, owner.cookie(),
                "{\"legalName\":\"日期测试\",\"establishmentDate\":\"2020/06/01\"}", 400);
    }
}
