package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.milestone.EngagementMilestone;
import com.grassland.marketplace.milestone.EngagementMilestoneRepository;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.DeliveryDeadlineInput;
import com.grassland.marketplace.workflow.saga.DeliveryLifecycleActivity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #96 C96-02 履约里程碑与取消补偿 IT（TC96-002/006/007/008/009/010 + 提案联锁）。
 *
 * <p>bean 覆盖集合与 ApplicationControllerIT 一致（复用 Spring 上下文缓存）；finance 出站 mock。
 * 赏金快照用 SQL 后门注入（真 Saga 造资金型需整套 Saga 桩，按冻结列直验——补偿金额读的是
 * {@code bounty_cents} 冻结列，语义一致）。脚本/成品里程碑按生产形态经仓储落行（草稿批准流的
 * 写入接线在 C96-04，引擎与本卡端点先行）。
 */
@SuppressWarnings("unchecked")
class EngagementMilestoneSettlementIT extends MarketplaceItSupport {

    private static final String H = "X-Grassland-Identity";

    @MockitoBean
    private FinanceEscrowClient financeClient;

    @MockitoBean
    private DisputeChecker disputeChecker;

    @MockitoBean
    private IntelligenceMediaClient mediaClient;

    @MockitoBean
    private com.grassland.marketplace.taskcatalog.LinkReachabilityChecker linkChecker;

    @MockitoBean
    private IntelligenceVerificationClient verificationClient;

    @MockitoBean
    private TrustDisputeClient trustDisputeClient;

    @MockitoSpyBean
    private SubmissionAttachmentRepository attachmentRepo;

    @MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.MerchantRejectionReviewWorkflowStarter rejectionStarter;

    @MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter settlementStarter;

    @MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.AcceptanceWorkflowStarter acceptanceStarter;

    @MockitoSpyBean
    private ReputationService reputationService;

    @Autowired
    private TaskApplicationRepository applicationRepo;

    @Autowired
    private EngagementMilestoneRepository milestoneRepo;

    @Autowired
    private DeliveryLifecycleActivity deliveryActivity;

    @Autowired
    private com.grassland.marketplace.milestone.EngagementMilestoneService milestoneService;

    // ---------- TC96-006：已有脚本确认后取消 → 20% 结算，差额退商家，引用里程碑行 ----------

    @Test
    void cancelWithConfirmedScriptMilestoneSettlesTwentyPercent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        injectBounty(appId, 10_000L);
        seedConfirmedMilestone(appId, rec, merchant, EngagementMilestone.KIND_SCRIPT);
        stubSettlementFunds();

        Map<String, Object> body = cancelTask(merchant, org, task);
        assertThat(((Number) body.get("settledWithCompensation")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("refundedCount")).intValue()).isZero();

        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.status()).isEqualTo("refunded");
        assertThat(app.exitKind()).isEqualTo("merchant_cancel");
        assertThat(app.exitedAt()).isNotNull();
        // 结算引用里程碑行可审计：金额回填到行 + 事件引用 id + capture 金额 = 20%
        EngagementMilestone script = milestoneRepo.findByApplication(appId)
                .filter(m -> EngagementMilestone.KIND_SCRIPT.equals(m.kind())).blockLast();
        assertThat(script.amountCents()).isEqualTo(2_000L);
        assertThat(outboxCountForApp("EngagementCancelledWithSettlement", appId)).isEqualTo(1);
        assertThat(outboxSettlementTotal("EngagementCancelledWithSettlement", appId)).isEqualTo(2_000L);
        verify(financeClient).captureVerified(org, appId, 10_000L, rec, 2_000L);
        verify(financeClient).release(org, appId);
    }

    // ---------- TC96-007：无里程碑取消 → 全额退（现状保持，无 capture） ----------

    @Test
    void cancelWithoutMilestonesKeepsFullRefundStatusQuo() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        injectBounty(appId, 10_000L);
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());

        Map<String, Object> body = cancelTask(merchant, org, task);
        assertThat(((Number) body.get("refundedCount")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("settledWithCompensation")).intValue()).isZero();

        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.status()).isEqualTo("refunded");
        assertThat(app.exitKind()).isNull();  // 全额退路径不写退出事实
        verify(financeClient, never()).captureVerified(anyString(), anyString(), anyLong(), anyString(), any());
        assertThat(outboxCountForApp("EngagementRefundedOnCancel", appId)).isEqualTo(1);
    }

    // ---------- TC96-008：三类全确认 → 补偿 = 预留上限；floor 取整不超预留 ----------

    @Test
    void compensationCappedAtReservedBountyAndFloorsPerKind() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        injectBounty(appId, 10_000L);
        for (String kind : List.of(EngagementMilestone.KIND_SCRIPT, EngagementMilestone.KIND_DELIVERABLE,
                EngagementMilestone.KIND_PUBLISHED)) {
            seedConfirmedMilestone(appId, rec, merchant, kind);
        }
        var breakdown = milestoneService.computeSettlement(applicationRepo.findById(appId).block()).block();
        assertThat(breakdown.totalCents()).isEqualTo(10_000L);  // 20%+60%+20% = 预留上限
        assertThat(breakdown.scriptCents()).isEqualTo(2_000L);
        assertThat(breakdown.deliverableCents()).isEqualTo(6_000L);
        assertThat(breakdown.publishedCents()).isEqualTo(2_000L);

        // floor 语义：9999 分赏金按 20% = 1999.8 → 1999，任何一类都不产生超出预留的尾差
        injectBounty(appId, 9_999L);
        var floored = milestoneService.computeSettlement(applicationRepo.findById(appId).block()).block();
        assertThat(floored.totalCents()).isEqualTo(9_997L);  // 1999+5999+1999 = 9997 ≤ 9999
    }

    // ---------- TC96-009：存量 accepted（政策版本外）取消 → 全额退，不回填里程碑 ----------

    @Test
    void legacyApplicationWithoutPolicyStaysFullRefundOnCancel() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        injectBounty(appId, 5_000L);
        db.sql("UPDATE task_application SET engagement_policy_version = NULL WHERE id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());

        Map<String, Object> body = cancelTask(merchant, org, task);
        assertThat(((Number) body.get("refundedCount")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("settledWithCompensation")).intValue()).isZero();
        assertThat(milestoneRepo.findByApplication(appId).collectList().block()).isEmpty();
        verify(financeClient, never()).captureVerified(anyString(), anyString(), anyLong(), anyString(), any());
    }

    // ---------- TC96-010：双方确认制 + 确认后事实不可变 ----------

    @Test
    void milestoneConfirmationRequiresCounterpartyAndIsImmutable() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        EngagementMilestone proposed = milestoneRepo.create(appId, EngagementMilestone.KIND_SCRIPT, 1, null, rec)
                .block();

        // 提出方自签 → 409（双方确认制）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/milestones/"
                + proposed.id() + "/confirm")
                .header(H, sign(rec, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
        // 非任一方 → 403/404（越权/不泄露，§6 状态码口径）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/milestones/"
                + proposed.id() + "/confirm")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().value(status -> assertThat(status).isIn(403, 404));

        // 对方（商家）确认 → 200
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/milestones/"
                + proposed.id() + "/confirm")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.confirmedBy").isEqualTo(merchant)
                .jsonPath("$.data.kind").isEqualTo("script");
        assertThat(outboxCountForApp("MilestoneConfirmed", appId)).isEqualTo(1);

        // 重复确认 → 幂等 200，事实不变
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/milestones/"
                + proposed.id() + "/confirm")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();
        EngagementMilestone after = milestoneRepo.findById(proposed.id()).block();
        assertThat(after.confirmedBy()).isEqualTo(merchant);
        assertThat(outboxCountForApp("MilestoneConfirmed", appId)).isEqualTo(1);
    }

    // ---------- TC96-002：有责终结按已确认里程碑结算 ----------

    @Test
    void timeoutTerminationSettlesConfirmedMilestones() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        injectBounty(appId, 10_000L);
        seedConfirmedMilestone(appId, rec, merchant, EngagementMilestone.KIND_SCRIPT);
        stubSettlementFunds();
        backdateRemedy(appId);

        var outcome = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(outcome.status()).isEqualTo("terminated");

        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.status()).isEqualTo("refunded");
        assertThat(app.exitKind()).isEqualTo("timeout");
        verify(financeClient).captureVerified(org, appId, 10_000L, rec, 2_000L);
        verify(financeClient).release(org, appId);
        EngagementMilestone script = milestoneRepo.findByApplication(appId)
                .filter(m -> EngagementMilestone.KIND_SCRIPT.equals(m.kind())).blockLast();
        assertThat(script.amountCents()).isEqualTo(2_000L);
        assertThat(outboxSettlementTotal("DeliveryTimeoutTerminated", appId)).isEqualTo(2_000L);
    }

    // ---------- 提案联锁：提交 → published 提案；履约确认 → 商家互签 ----------

    @Test
    void submissionProposesPublishedMilestoneAndConfirmCountersigns() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);

        Map<String, Object> created = client().post()
                .uri("/api/tasks/" + task + "/applications/" + appId + "/submissions")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", "https://www.xiaohongshu.com/p/abc123", "note", "已发布"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String submissionId = (String) ((Map<String, Object>) created.get("data")).get("id");

        EngagementMilestone proposal = milestoneRepo.findByApplication(appId)
                .filter(m -> EngagementMilestone.KIND_PUBLISHED.equals(m.kind())).blockLast();
        assertThat(proposal).isNotNull();
        assertThat(proposal.confirmed()).isFalse();
        assertThat(proposal.evidenceSubmissionId()).isEqualTo(submissionId);
        assertThat(proposal.proposedBy()).isEqualTo(rec);

        // 商家确认履约（走既有 confirm 端点）→ published 提案完成互签
        lenient().when(linkChecker.check(anyString())).thenReturn(
                reactor.core.publisher.Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/confirm")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().value(status -> assertThat(status).isIn(200, 202));
        EngagementMilestone after = milestoneRepo.findById(proposal.id()).block();
        assertThat(after.confirmed()).isTrue();
        assertThat(after.confirmedBy()).isEqualTo(merchant);
    }

    // ---------- 造数 helper ----------

    private String publishTask(String merchant, String org) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "里程碑任务");
        b.put("platform", "xiaohongshu");
        b.put("storeId", UUID.randomUUID().toString());
        b.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                + "WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
        return stripStoreScope(taskId);
    }

    private String applyAndAccept(String merchant, String org, String taskId, String rec) {
        Map<String, Object> applied = client().post().uri("/api/tasks/" + taskId + "/applications")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "接"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();
        return appId;
    }

    private void injectBounty(String appId, long bountyCents) {
        db.sql("UPDATE task_application SET bounty_cents = :b WHERE id = CAST(:id AS uuid)")
                .bind("b", bountyCents).bind("id", appId).then().block();
    }

    private void seedConfirmedMilestone(String appId, String proposer, String confirmer, String kind) {
        EngagementMilestone proposed = milestoneRepo.create(appId, kind, 1, null, proposer).block();
        EngagementMilestone confirmed = milestoneRepo.confirm(proposed.id(), confirmer).block();
        assertThat(confirmed).isNotNull();
    }

    private void stubSettlementFunds() {
        lenient().when(financeClient.captureVerified(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(Mono.just(FinanceEscrowClient.CaptureOutcome.capturedNow()));
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
    }

    private Map<String, Object> cancelTask(String merchant, String org, String taskId) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + taskId + "/cancel")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) resp.get("data");
    }

    private void backdateRemedy(String appId) {
        db.sql("UPDATE task_application SET remedy_deadline_at = now() - interval '1 second',"
                        + " delivery_deadline_at = now() - interval '121 second'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();
    }

    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private Long outboxSettlementTotal(String eventType, String appId) {
        return db.sql("SELECT (payload->'settlement'->>'totalCents')::bigint AS t FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app LIMIT 1")
                .bind("et", eventType).bind("app", appId).map(r -> r.get("t", Long.class)).one().block();
    }

    private DeliveryDeadlineInput input(String appId, String taskId, String org) {
        return new DeliveryDeadlineInput(appId, taskId, org, 0, 0, 0);
    }
}
