package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.FinanceReconciliationClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 受限处置动作 API（GL-P1-OPS-001 Stage 2）：审批前置、幂等回放、动作与来源匹配、失败留痕。
 */
class OpsCaseActionControllerIT extends MarketplaceItSupport {

    private static final String OPS_A = "11111111-1111-4111-8111-111111111111";
    private static final String OPS_B = "22222222-2222-4222-8222-222222222222";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";
    private static final String APP = "55555555-5555-5555-5555-555555555555";

    @MockitoBean
    private FinanceEscrowClient escrow;

    @MockitoBean
    private FinanceReconciliationClient reconciliation;

    @Autowired
    private OpsCaseRegistrar registrar;

    @Autowired
    private OpsCaseRepository cases;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_case_action").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case_audit").fetch().rowsUpdated().block();
        db.sql("DELETE FROM ops_case").fetch().rowsUpdated().block();
        db.sql("DELETE FROM settlement_reconciliation").fetch().rowsUpdated().block();
    }

    /** 开一张已审批通过的 held 单（提审人 ≠ 审批人）。 */
    private OpsCase approvedHeldCase() {
        OpsCase c = registrar.register(OpsCaseSource.SETTLEMENT_HELD, UUID.randomUUID().toString(),
                ORG, APP, "open_dispute").block();
        cases.submit(c.id(), 1L, OPS_A, null).block();
        return cases.decide(c.id(), 2L, OPS_B, true, null).block();
    }

    private OpsCase openHeldCase() {
        return registrar.register(OpsCaseSource.SETTLEMENT_HELD, UUID.randomUUID().toString(),
                ORG, APP, "open_dispute").block();
    }

    private String act(String caseId) {
        return "/api/ops/cases/" + caseId + "/actions";
    }

    @Test
    @DisplayName("未审批通过的 case 不能执行动作 → 409，且不打 finance")
    void requiresApproval() {
        OpsCase c = openHeldCase();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_A, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-1\"}")
                .exchange().expectStatus().isEqualTo(409);

        verify(escrow, never()).release(anyString(), anyString());
        assertThat(actionCount()).isZero();
    }

    @Test
    @DisplayName("商家身份 403（动作端点同样只认平台角色）")
    void requiresOpsRole() {
        OpsCase c = approvedHeldCase();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", sign("33333333-3333-4333-8333-333333333333", "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-1\"}")
                .exchange().expectStatus().isForbidden();

        verify(escrow, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("release_funds 打 finance release，落 succeeded 台账 + action_executed 审计")
    void releaseFunds() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.empty());
        OpsCase c = approvedHeldCase();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-release-1\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.action").isEqualTo("release_funds")
                .jsonPath("$.data.status").isEqualTo("succeeded")
                .jsonPath("$.data.outcome").isEqualTo("released")
                .jsonPath("$.data.requestedBy").isEqualTo(OPS_B);

        verify(escrow, times(1)).release(ORG, APP);
        assertThat(auditActions(c.id())).contains("action_executed");
    }

    @Test
    @DisplayName("幂等：同一 operationId 重复提交回放台账，finance 只被调一次")
    void idempotentReplay() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.empty());
        OpsCase c = approvedHeldCase();
        String body = "{\"action\":\"release_funds\",\"operationId\":\"op-same\"}";

        for (int i = 0; i < 3; i++) {
            client().post().uri(act(c.id()))
                    .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");
        }

        verify(escrow, times(1)).release(ORG, APP);
        assertThat(actionCount()).isEqualTo(1L);
        // 审计也只有一条 action_executed：回放不重复留痕。
        assertThat(auditActions(c.id()).stream().filter("action_executed"::equals).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("operationId 跨 case 复用 → 409（不静默返回别人的结果）")
    void operationIdReuseRejected() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.empty());
        OpsCase first = approvedHeldCase();
        OpsCase second = approvedHeldCase();

        client().post().uri(act(first.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-reuse\"}")
                .exchange().expectStatus().isOk();

        client().post().uri(act(second.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-reuse\"}")
                .exchange().expectStatus().isEqualTo(409);

        verify(escrow, times(1)).release(ORG, APP);
    }

    @Test
    @DisplayName("下游失败 → 台账 failed + action_failed 审计（失败的补偿尝试也要留痕）")
    void downstreamFailureIsRecorded() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.error(new IllegalStateException("finance 502")));
        OpsCase c = approvedHeldCase();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-fail\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("failed")
                .jsonPath("$.data.error").isEqualTo("finance 502");

        assertThat(auditActions(c.id())).contains("action_failed");
    }

    @Test
    @DisplayName("动作与来源不匹配 → 400，且不烧掉 operationId（改对动作后同键可重试）")
    void actionMustMatchSource() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.empty());
        OpsCase held = approvedHeldCase();

        client().post().uri(act(held.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"retry_reconciliation\",\"operationId\":\"op-mismatch\"}")
                .exchange().expectStatus().isEqualTo(400);

        verify(reconciliation, never()).reconcile(anyString(), anyString(), anyString());
        assertThat(actionCount()).isZero();

        // 同一个 operationId 换成相容的动作仍可用。
        client().post().uri(act(held.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-mismatch\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("succeeded");
    }

    @Test
    @DisplayName("未知动作 → 400；缺 operationId → 400")
    void requestValidation() {
        OpsCase c = approvedHeldCase();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"capture_funds\",\"operationId\":\"op-x\"}")
                .exchange().expectStatus().isEqualTo(400);

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\"}")
                .exchange().expectStatus().isEqualTo(400);

        assertThat(actionCount()).isZero();
    }

    @Test
    @DisplayName("retry_reconciliation 从对账行取回原 finalDecision 打 finance，不接受入参判决")
    void retryReconciliationUsesStoredDecision() {
        String sourceEventId = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO settlement_reconciliation(source_event_id, dispute_id, application_id,
                        organization_id, workflow_id, final_decision, status, reason)
                VALUES (:src, :dispute, :app, :org, :wf, 'for_merchant', 'blocked', 'finance_blocked')
                """)
                .bind("src", sourceEventId).bind("dispute", UUID.randomUUID().toString())
                .bind("app", APP).bind("org", ORG).bind("wf", "wf-" + sourceEventId)
                .fetch().rowsUpdated().block();

        when(reconciliation.reconcile(ORG, APP, "for_merchant"))
                .thenReturn(Mono.just(new FinanceReconciliationClient.Result("repaired", "refunded")));

        OpsCase c = registrar.register(OpsCaseSource.SETTLEMENT_BLOCKED, sourceEventId, ORG, APP,
                "finance_blocked").block();
        cases.submit(c.id(), 1L, OPS_A, null).block();
        cases.decide(c.id(), 2L, OPS_B, true, null).block();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"retry_reconciliation\",\"operationId\":\"op-retry-1\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("succeeded")
                .jsonPath("$.data.outcome").isEqualTo("repaired/refunded");

        verify(reconciliation, times(1)).reconcile(ORG, APP, "for_merchant");
    }

    @Test
    @DisplayName("对账仍未通过 → 台账 failed（不谎报成功）")
    void retryReconciliationStillBlocked() {
        String sourceEventId = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO settlement_reconciliation(source_event_id, dispute_id, application_id,
                        organization_id, workflow_id, final_decision, status, reason)
                VALUES (:src, :dispute, :app, :org, :wf, 'for_merchant', 'blocked', 'finance_blocked')
                """)
                .bind("src", sourceEventId).bind("dispute", UUID.randomUUID().toString())
                .bind("app", APP).bind("org", ORG).bind("wf", "wf-" + sourceEventId)
                .fetch().rowsUpdated().block();

        when(reconciliation.reconcile(ORG, APP, "for_merchant"))
                .thenReturn(Mono.just(new FinanceReconciliationClient.Result(
                        "blocked", "manual_clawback_required")));

        OpsCase c = registrar.register(OpsCaseSource.SETTLEMENT_BLOCKED, sourceEventId, ORG, APP,
                "finance_blocked").block();
        cases.submit(c.id(), 1L, OPS_A, null).block();
        cases.decide(c.id(), 2L, OPS_B, true, null).block();

        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"retry_reconciliation\",\"operationId\":\"op-retry-2\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("failed")
                .jsonPath("$.data.error").value(org.hamcrest.Matchers.containsString("manual_clawback_required"));
    }

    @Test
    @DisplayName("详情带动作台账（运营能看到做过什么）")
    void detailIncludesActions() {
        when(escrow.release(ORG, APP)).thenReturn(Mono.empty());
        OpsCase c = approvedHeldCase();
        client().post().uri(act(c.id()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-detail\"}")
                .exchange().expectStatus().isOk();

        client().get().uri("/api/ops/cases/" + c.id())
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.actions.length()").isEqualTo(1)
                .jsonPath("$.data.actions[0].operationId").isEqualTo("op-detail")
                .jsonPath("$.data.actions[0].status").isEqualTo("succeeded");
    }

    @Test
    @DisplayName("不存在的 case → 404")
    void missingCase() {
        client().post().uri(act(UUID.randomUUID().toString()))
                .header("X-Grassland-Identity", signWithRole(OPS_B, "admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"action\":\"release_funds\",\"operationId\":\"op-404\"}")
                .exchange().expectStatus().isNotFound();
    }

    private Long actionCount() {
        return db.sql("SELECT count(*) AS n FROM ops_case_action")
                .map(r -> r.get("n", Long.class)).one().block();
    }

    private java.util.List<String> auditActions(String caseId) {
        return db.sql("SELECT action FROM ops_case_audit WHERE case_id = CAST(:id AS uuid) ORDER BY id")
                .bind("id", caseId)
                .map(r -> r.get("action", String.class)).all().collectList().block();
    }
}
