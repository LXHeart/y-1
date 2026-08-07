package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 运营处置台 API 集成测试（GL-P1-OPS-001 Stage 1）。
 *
 * <p>覆盖角色闸门、队列/详情、提审→审批→收单全链、乐观锁 409、以及<b>双人审批</b>
 * （自己审自己 → 409，DB 约束为第二道）。
 */
class OpsCaseControllerIT extends MarketplaceItSupport {

    private static final String OPS_A = "11111111-1111-4111-8111-111111111111";
    private static final String OPS_B = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private OpsCaseRegistrar registrar;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_case_audit").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case").fetch().rowsUpdated().block();
    }

    private OpsCase givenCase(String reason) {
        return registrar.register(OpsCaseSource.SETTLEMENT_HELD, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), reason).block();
    }

    @Test
    @DisplayName("未登录 401；商家身份 403（运营端点按平台角色判定，业务身份不可达）")
    void requiresOpsRole() {
        client().get().uri("/api/ops/cases").exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/ops/cases")
                .header("X-Grassland-Identity", sign("33333333-3333-4333-8333-333333333333", "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("customer_service 与 admin 都可读队列；默认只列未终态")
    void listQueue() {
        givenCase("open_dispute");
        for (String role : new String[] {"customer_service", "admin"}) {
            client().get().uri("/api/ops/cases")
                    .header("X-Grassland-Identity", signWithRole(OPS_A, role))
                    .exchange().expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.data.length()").isEqualTo(1)
                    .jsonPath("$.data[0].status").isEqualTo("open")
                    .jsonPath("$.data[0].sourceKind").isEqualTo("settlement_held");
        }
    }

    @Test
    @DisplayName("商家异议按 accept 时专属客服权益优先，并返回客服标识")
    void merchantRejectionQueuePrioritizesPremiumSupportSnapshot() {
        String standardApp = seedAcceptedApplication(false);
        String premiumApp = seedAcceptedApplication(true);
        registrar.register(OpsCaseSource.MERCHANT_REJECTION, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), standardApp, "standard support").block();
        registrar.register(OpsCaseSource.MERCHANT_REJECTION, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), premiumApp, "premium support").block();

        client().get().uri("/api/ops/cases")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "customer_service"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].applicationId").isEqualTo(premiumApp)
                .jsonPath("$.data[0].premiumSupport").isEqualTo(true)
                .jsonPath("$.data[0].supportPriority").isEqualTo(100)
                .jsonPath("$.data[1].applicationId").isEqualTo(standardApp)
                .jsonPath("$.data[1].premiumSupport").isEqualTo(false);
    }

    @Test
    @DisplayName("登记即写 registered 审计（actor 为空 + role=system），详情带审计时间线")
    void detailCarriesAudit() {
        OpsCase c = givenCase("verification_failed");

        client().get().uri("/api/ops/cases/" + c.id())
                .header("X-Grassland-Identity", signWithRole(OPS_A, "customer_service"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.case.reason").isEqualTo("verification_failed")
                .jsonPath("$.data.audits.length()").isEqualTo(1)
                .jsonPath("$.data.audits[0].action").isEqualTo("registered")
                .jsonPath("$.data.audits[0].actorRole").isEqualTo("system")
                .jsonPath("$.data.audits[0].actorAccountId").isEmpty()
                .jsonPath("$.data.audits[0].toStatus").isEqualTo("open");
    }

    @Test
    @DisplayName("不存在的处置单 → 404")
    void detailMissing() {
        client().get().uri("/api/ops/cases/" + UUID.randomUUID())
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("提审→审批→收单全链，每步 version+1 且各写一条审计")
    void fullLifecycle() {
        OpsCase c = givenCase("finance_blocked");

        client().post().uri("/api/ops/cases/" + c.id() + "/submit")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "customer_service"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":1,\"note\":\"需人工追偿\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("in_review")
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.submittedBy").isEqualTo(OPS_A);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":2,\"approve\":true}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("approved")
                .jsonPath("$.data.version").isEqualTo(3)
                .jsonPath("$.data.approvedBy").isEqualTo(OPS_B);

        client().post().uri("/api/ops/cases/" + c.id() + "/resolve")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":3,\"resolution\":\"compensated\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("resolved")
                .jsonPath("$.data.version").isEqualTo(4)
                .jsonPath("$.data.resolution").isEqualTo("compensated");

        Long audits = db.sql("SELECT count(*) AS n FROM ops_case_audit WHERE case_id = CAST(:id AS uuid)")
                .bind("id", c.id()).map(r -> r.get("n", Long.class)).one().block();
        assertThat(audits).isEqualTo(4L);  // registered + submitted + approved + resolved

        // 终态不再出现在默认队列里。
        client().get().uri("/api/ops/cases")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("双人审批：提审人不能自己审批 → 409，且状态与审计都不动")
    void twoPersonApproval() {
        OpsCase c = givenCase("open_dispute");
        submitAs(c.id(), OPS_A);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":2,\"approve\":true}")
                .exchange().expectStatus().isEqualTo(409);

        String status = db.sql("SELECT status FROM ops_case WHERE id = CAST(:id AS uuid)")
                .bind("id", c.id()).map(r -> r.get("status", String.class)).one().block();
        assertThat(status).isEqualTo("in_review");

        Long audits = db.sql("SELECT count(*) AS n FROM ops_case_audit WHERE case_id = CAST(:id AS uuid)")
                .bind("id", c.id()).map(r -> r.get("n", Long.class)).one().block();
        assertThat(audits).isEqualTo(2L);  // registered + submitted，审批未发生
    }

    @Test
    @DisplayName("乐观锁：过期 expectedVersion → 409；缺失 → 400")
    void optimisticLock() {
        OpsCase c = givenCase("open_dispute");
        submitAs(c.id(), OPS_A);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":1,\"approve\":true}")
                .exchange().expectStatus().isEqualTo(409);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"approve\":true}")
                .exchange().expectStatus().isEqualTo(400);
    }

    @Test
    @DisplayName("approve 缺失 → 400（不默认放行：通过意味着 Stage 2 资金动作被解锁）")
    void approveRequired() {
        OpsCase c = givenCase("open_dispute");
        submitAs(c.id(), OPS_A);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":2}")
                .exchange().expectStatus().isEqualTo(400);
    }

    @Test
    @DisplayName("驳回是终态：rejected 后不可收单 → 409")
    void rejectedIsTerminal() {
        OpsCase c = givenCase("open_dispute");
        submitAs(c.id(), OPS_A);

        client().post().uri("/api/ops/cases/" + c.id() + "/decide")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":2,\"approve\":false}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("rejected");

        client().post().uri("/api/ops/cases/" + c.id() + "/resolve")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":3,\"resolution\":\"compensated\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("未审批通过不可收单（open 直接 resolve → 409）")
    void resolveRequiresApproval() {
        OpsCase c = givenCase("open_dispute");

        client().post().uri("/api/ops/cases/" + c.id() + "/resolve")
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":1,\"resolution\":\"compensated\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("status 参数可查终态（审计回看）")
    void listByStatus() {
        OpsCase c = givenCase("open_dispute");
        submitAs(c.id(), OPS_A);

        client().get().uri("/api/ops/cases?status=in_review")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(c.id());

        client().get().uri("/api/ops/cases?status=resolved")
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    private void submitAs(String caseId, String accountId) {
        client().post().uri("/api/ops/cases/" + caseId + "/submit")
                .header("X-Grassland-Identity", signWithRole(accountId, "customer_service"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":1}")
                .exchange().expectStatus().isOk();
    }

    private String seedAcceptedApplication(boolean premiumSupport) {
        String taskId = UUID.randomUUID().toString();
        String applicationId = UUID.randomUUID().toString();
        db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, status)"
                        + " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), 'support task', 'published')")
                .bind("id", taskId).bind("owner", UUID.randomUUID().toString())
                .bind("org", UUID.randomUUID().toString()).then().block();
        db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents,"
                        + " reputation_level_at_accept, reputation_policy_version_at_accept,"
                        + " settlement_delay_days_at_accept, commission_bonus_bps_at_accept,"
                        + " premium_support_at_accept)"
                        + " VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), 'accepted', 0,"
                        + " 1, 1, 2, 0, :premium)")
                .bind("id", applicationId).bind("task", taskId).bind("rec", UUID.randomUUID().toString())
                .bind("premium", premiumSupport).then().block();
        return applicationId;
    }
}
