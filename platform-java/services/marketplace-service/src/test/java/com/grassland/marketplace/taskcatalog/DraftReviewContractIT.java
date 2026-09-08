package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.milestone.EngagementMilestone;
import com.grassland.marketplace.milestone.EngagementMilestoneRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.time.Instant;
import java.util.HashMap;
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
 * 任务书 #96 C96-04 发布前审稿与期限可配 IT（TC96-015/016/017/018 + 合同字段快照/取消条款）。
 *
 * <p>bean 覆盖集合与 ApplicationControllerIT 一致（复用上下文缓存）；审稿派发器在基座关闭——
 * 超时转人工直接构造 {@link DraftReviewDispatcher} 驱动 seam。
 */
@SuppressWarnings("unchecked")
class DraftReviewContractIT extends MarketplaceItSupport {

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
    private SubmissionRepository submissionRepo;

    @Autowired
    private OpsCaseRegistrar opsCases;

    // ---------- TC96-015：草稿无公开链接送审；审稿闸门拦未过审的发布凭证 ----------

    @Test
    void draftSubmissionWithoutLinkAndReviewGate() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishReviewTask(merchant, org, true, 2, null);
        String appId = applyAndAccept(merchant, org, task, rec);

        // 合同要求审稿：直接交发布凭证 → 409
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/submissions")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", "https://www.xiaohongshu.com/p/abc"))
                .exchange().expectStatus().isEqualTo(409);

        // 草稿送审：纯说明文案、无公开链接（TC96-015）
        Map<String, Object> created = client().post()
                .uri("/api/tasks/" + task + "/applications/" + appId + "/submissions/draft")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "脚本初稿：开箱场景"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> draft = (Map<String, Object>) created.get("data");
        assertThat(draft.get("contentUrl")).isEqualTo("");
        assertThat(draft.get("status")).isEqualTo("submitted");
        String draftId = (String) draft.get("id");

        // 重复送审 → 409（同报名一份待审）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/submissions/draft")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "v2"))
                .exchange().expectStatus().isEqualTo(409);

        // 商家批准 = script 里程碑互签联锁过审（C96-02 端点复用）
        EngagementMilestone proposal = latestScriptProposal(appId);
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/milestones/"
                + proposal.id() + "/confirm")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();
        EngagementSubmission approved = submissionRepo.findById(draftId).block();
        assertThat(approved.status()).isEqualTo("accepted");

        // 过审后发布凭证放行（发布后验收流程不动）
        lenient().when(linkChecker.check(anyString()))
                .thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/submissions")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", "https://www.xiaohongshu.com/p/abc"))
                .exchange().expectStatus().isCreated();
    }

    /** 非审稿合同任务送审 → 409（合同无审稿要求）。 */
    @Test
    void draftSubmissionRejectedWhenContractDoesNotRequireReview() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishReviewTask(merchant, org, false, null, null);
        String appId = applyAndAccept(merchant, org, task, rec);
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/submissions/draft")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "不请自来"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- TC96-017：退改限次（与凭证共享 supplement-cap，耗尽 → 409 转争议/退出） ----------

    @Test
    void draftRevisionCapSharedWithSupplementCap() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishReviewTask(merchant, org, true, null, null);
        String appId = applyAndAccept(merchant, org, task, rec);

        for (int round = 1; round <= 2; round++) {
            String draftId = submitDraft(task, appId, rec, "第 " + round + " 稿");
            client().post().uri("/api/tasks/" + task + "/applications/" + appId
                            + "/submissions/" + draftId + "/reject")
                    .header(H, sign(merchant, "merchant", org, "basic_publish"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("note", "改一下开头"))
                    .exchange().expectStatus().isOk();
        }
        // 第三稿仍可交，但第三次退回被限次拦截（cap=2）→ 退改耗尽走争议/退出
        String thirdId = submitDraft(task, appId, rec, "第三稿");
        client().post().uri("/api/tasks/" + task + "/applications/" + appId
                        + "/submissions/" + thirdId + "/reject")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "再改"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- TC96-018：补交期限（被拒草稿 reviewed_at + 48h，IT 拨快 1h） ----------

    @Test
    void draftResubmitDeadlineEnforced() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishReviewTask(merchant, org, true, null, null);
        String appId = applyAndAccept(merchant, org, task, rec);

        String draftId = submitDraft(task, appId, rec, "初稿");
        client().post().uri("/api/tasks/" + task + "/applications/" + appId
                        + "/submissions/" + draftId + "/reject")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "重写"))
                .exchange().expectStatus().isOk();
        // 把被拒草稿的 reviewed_at 拨到补交窗之外
        db.sql("UPDATE engagement_submission SET reviewed_at = now() - interval '2 hours'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", draftId).then().block();

        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/submissions/draft")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "过期补交"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- TC96-016：审稿窗口超时 → 提醒 + 转人工（缺省方向，幂等） ----------

    @Test
    void draftReviewTimeoutEscalatesToManualReview() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishReviewTask(merchant, org, true, null, null);
        String appId = applyAndAccept(merchant, org, task, rec);
        String draftId = submitDraft(task, appId, rec, "无人审的稿");
        db.sql("UPDATE engagement_submission SET created_at = now() - interval '3 hours'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", draftId).then().block();

        // 派发器在 IT 基座关闭——手动构造驱动（窗口 2h，已过期）
        DraftReviewDispatcher dispatcher = new DraftReviewDispatcher(submissionRepo, applicationRepo,
                new com.grassland.marketplace.event.OutboxRepository(db), opsCases, 32, 2L);
        dispatcher.dispatchBatch();

        assertThat(outboxCountForApp("DraftReviewExpiring", appId)).isEqualTo(1);
        Integer cases = db.sql("SELECT COUNT(*)::int AS c FROM ops_case"
                        + " WHERE source_kind = 'draft_review_timeout' AND source_ref = :ref")
                .bind("ref", draftId).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(cases).isEqualTo(1);
        // 重放幂等：事件确定性 + 处置单 UNIQUE
        dispatcher.dispatchBatch();
        assertThat(outboxCountForApp("DraftReviewExpiring", appId)).isEqualTo(1);
        cases = db.sql("SELECT COUNT(*)::int AS c FROM ops_case"
                        + " WHERE source_kind = 'draft_review_timeout' AND source_ref = :ref")
                .bind("ref", draftId).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(cases).isEqualTo(1);
    }

    // ---------- 合同字段：交付期限天数覆盖配置缺省；取消条款模板覆盖全局比例 ----------

    @Test
    void contractFieldsSnapshotAndCancelPolicyTemplate() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        Map<String, Integer> policy = new HashMap<>();
        policy.put("script", 1_000);
        String task = publishReviewTask(merchant, org, false, 2, policy);
        String appId = applyAndAccept(merchant, org, task, rec);

        // 合同字段优先（配置缺省 1 天，合同 2 天）
        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.deliveryDeadlineAt()).isAfter(Instant.now().plusSeconds(2 * 86400L - 600));
        assertThat(app.remedyDeadlineAt()).isAfter(app.deliveryDeadlineAt());

        // 取消条款模板 10% 生效（脚本确认后取消 → 1000/10000）
        injectBounty(appId, 10_000L);
        seedConfirmedScript(appId, rec, merchant);
        lenient().when(financeClient.captureVerified(anyString(), anyString(), anyLong(), anyString(), any()))
                .thenReturn(Mono.just(FinanceEscrowClient.CaptureOutcome.capturedNow()));
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
        Map<String, Object> body = cancelTask(merchant, org, task);
        assertThat(((Number) body.get("settledWithCompensation")).intValue()).isEqualTo(1);
        verify(financeClient).captureVerified(eq(org), eq(appId), eq(10_000L), eq(rec), eq(1_000L));
    }

    // ---------- 造数 helper ----------

    private String publishReviewTask(String merchant, String org, boolean reviewRequired, Integer deliveryDays,
            Map<String, Integer> cancelPolicy) {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "审稿合同任务");
        b.put("platform", "xiaohongshu");
        b.put("storeId", UUID.randomUUID().toString());
        b.put("applicationDeadline", Instant.now().plusSeconds(3600).toString());
        b.put("reviewRequired", reviewRequired);
        if (deliveryDays != null) {
            b.put("deliveryDeadlineDays", deliveryDays);
        }
        if (cancelPolicy != null) {
            b.put("cancelPolicy", cancelPolicy);
        }
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

    private String submitDraft(String taskId, String appId, String rec, String note) {
        Map<String, Object> created = client().post()
                .uri("/api/tasks/" + taskId + "/applications/" + appId + "/submissions/draft")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", note))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) created.get("data")).get("id");
    }

    private EngagementMilestone latestScriptProposal(String appId) {
        EngagementMilestone proposal = milestoneRepo.findByApplication(appId)
                .filter(m -> EngagementMilestone.KIND_SCRIPT.equals(m.kind()) && !m.confirmed())
                .blockLast();
        assertThat(proposal).isNotNull();
        return proposal;
    }

    private void seedConfirmedScript(String appId, String proposer, String confirmer) {
        EngagementMilestone proposed = milestoneRepo.create(appId, EngagementMilestone.KIND_SCRIPT, 1, null, proposer)
                .block();
        milestoneRepo.confirm(proposed.id(), confirmer).block();
    }

    private void injectBounty(String appId, long bountyCents) {
        db.sql("UPDATE task_application SET bounty_cents = :b WHERE id = CAST(:id AS uuid)")
                .bind("b", bountyCents).bind("id", appId).then().block();
    }

    private Map<String, Object> cancelTask(String merchant, String org, String taskId) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + taskId + "/cancel")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) resp.get("data");
    }

    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }
}
