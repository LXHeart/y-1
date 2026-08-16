package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient.VerificationAnalysis;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 点赞互动任务全链 IT（任务书 #23 / ADR-D13）：受控值集 + 交叉校验（R1/R2）→ 提交契约 platformHandle（R3）
 * → 核验检查表（R4：复用 link/platform、互动 evidence 分支、新增 interaction_screenshot、跳过 ai_visual）。
 * 互动任务不带资金字段 → accept 走直连路径（无 Saga）。bean 覆盖集合与 ApplicationControllerIT 完全一致以复用上下文。
 */
@SuppressWarnings("unchecked")
class InteractionTaskFlowIT extends MarketplaceItSupport {

    private static final String H = "X-Grassland-Identity";

    @MockitoBean
    private com.grassland.marketplace.workflow.FinanceEscrowClient financeClient;

    @MockitoBean
    private DisputeChecker disputeChecker;

    @MockitoBean
    private IntelligenceMediaClient mediaClient;

    @MockitoBean
    private LinkReachabilityChecker linkChecker;

    @MockitoBean
    private IntelligenceVerificationClient verificationClient;

    @MockitoBean
    private TrustDisputeClient trustDisputeClient;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private SubmissionAttachmentRepository attachmentRepo;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.MerchantRejectionReviewWorkflowStarter rejectionStarter;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter settlementStarter;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.AcceptanceWorkflowStarter acceptanceStarter;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.grassland.marketplace.reputation.ReputationService reputationService;

    // ---------- R1/R2：值集 + 交叉校验 ----------

    @Test
    void contentFormValueSetAndCrossBindingValidated() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        // 未知值 → 400（受控值集）
        createTask(merchant, org, Map.of("organizationId", org, "title", "直播任务", "contentForm", "livestream"))
                .expectStatus().isBadRequest();

        // interaction 无配置块 → 400（单边违反）
        createTask(merchant, org, Map.of("organizationId", org, "title", "互动任务", "contentForm", "interaction"))
                .expectStatus().isBadRequest();

        // 非 interaction 带块 → 400
        Map<String, Object> wrongBlock = new LinkedHashMap<>();
        wrongBlock.put("organizationId", org);
        wrongBlock.put("title", "图文带块");
        wrongBlock.put("contentForm", "image");
        wrongBlock.put("requirements", Map.of("interaction",
                Map.of("targetUrl", "https://www.xiaohongshu.com/post/1", "actionType", "like")));
        createTask(merchant, org, wrongBlock).expectStatus().isBadRequest();

        // 合法组合 → 201，requirements 回带 interaction 块
        Map<String, Object> resp = createTask(merchant, org, interactionBody(org)).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> requirements = (Map<String, Object>) ((Map<String, Object>) resp.get("data")).get("requirements");
        Map<String, Object> interaction = (Map<String, Object>) requirements.get("interaction");
        assertThat(interaction).containsEntry("targetUrl", "https://www.xiaohongshu.com/post/1")
                .containsEntry("actionType", "like");
    }

    @Test
    void interactionBlockInvalidTargetOrActionRejected() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        // 内网地址 → 400（复用 LinkUrlGuard，不新写一套）
        Map<String, Object> ssrf = interactionBodyWith(
                "http://127.0.0.1:8080/admin", "like");
        createTask(merchant, org, ssrf).expectStatus().isBadRequest();
        // 非法动作类型（评论不做）→ 400
        Map<String, Object> comment = interactionBodyWith(
                "https://www.xiaohongshu.com/post/1", "comment");
        createTask(merchant, org, comment).expectStatus().isBadRequest();
    }

    // ---------- R3：提交契约 ----------

    @Test
    void interactionSubmissionRequiresPlatformHandle() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishInteractionTask(merchant, org);
        String app = applyAndAccept(recommender, task, merchant, org);

        // 缺 platformHandle → 400
        submitRaw(recommender, task, app, null).expectStatus().isBadRequest();

        // 带 handle → 201，contentUrl=目标链接，platformHandle 落库
        Map<String, Object> resp = submitRaw(recommender, task, app, "@seedhunter").expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertThat(data.get("platformHandle")).isEqualTo("@seedhunter");
        assertThat(data.get("contentUrl")).isEqualTo("https://www.xiaohongshu.com/post/1");
    }

    // ---------- R4：核验检查表 ----------

    @Test
    void interactionVerificationWithoutScreenshotsFailsCompletenessAndSkipsAiVisual() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishInteractionTask(merchant, org);
        String app = applyAndAccept(recommender, task, merchant, org);

        stubLinkPassed();
        String submission = submit(recommender, task, app, "@seedhunter");

        String checks = awaitRunChecks(submission);
        assertThat(checks).contains("\"evidence_completeness\"").contains("failed");
        assertThat(checks).doesNotContain("\"ai_visual\"");
        assertThat(checks).doesNotContain("\"interaction_screenshot\"");  // 无截图跳过模型，不烧调用
        verify(verificationClient, never()).analyze(anyString(), anyList(), anyString(), any(), any());
    }

    @Test
    void interactionScreenshotCheckUsesModelWithInteractionContext() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishInteractionTask(merchant, org);
        String app = applyAndAccept(recommender, task, merchant, org);

        UUID mediaId = UUID.randomUUID();
        when(mediaClient.metadata(org, mediaId, "application", app))
                .thenReturn(Mono.just(mediaMeta(mediaId, recommender, app)));
        stubLinkPassed();
        when(verificationClient.analyzeInteraction(eq(org), eq(List.of(mediaId)), anyString(), any(), any(),
                eq("https://www.xiaohongshu.com/post/1"), eq("like"), eq("@seedhunter")))
                .thenReturn(Mono.just(new VerificationAnalysis("passed",
                        List.of(new IntelligenceVerificationClient.MediaResult(mediaId, "passed", "三项均成立")))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
        body.put("platformHandle", "@seedhunter");
        body.put("mediaIds", List.of(mediaId.toString()));
        Map<String, Object> resp = client().post()
                .uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String submission = (String) ((Map<String, Object>) resp.get("data")).get("id");

        verify(verificationClient, timeout(8_000)).analyzeInteraction(eq(org), eq(List.of(mediaId)),
                anyString(), any(), any(), eq("https://www.xiaohongshu.com/post/1"), eq("like"), eq("@seedhunter"));
        verify(verificationClient, never()).analyze(anyString(), anyList(), anyString(), any(), any());
        String checks = awaitRunChecks(submission);
        assertThat(checks).contains("\"interaction_screenshot\"").contains("passed");
        assertThat(checks).contains("\"evidence_completeness\"").contains("passed");
        assertThat(checks).doesNotContain("\"ai_visual\"");
        assertThat(verificationStatus(submission)).isEqualTo("passed");
    }

    @Test
    void inconclusiveModelResultAggregatesToManualQueue() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishInteractionTask(merchant, org);
        String app = applyAndAccept(recommender, task, merchant, org);

        UUID mediaId = UUID.randomUUID();
        when(mediaClient.metadata(org, mediaId, "application", app))
                .thenReturn(Mono.just(mediaMeta(mediaId, recommender, app)));
        stubLinkPassed();
        when(verificationClient.analyzeInteraction(anyString(), anyList(), anyString(), any(), any(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new VerificationAnalysis("inconclusive",
                        List.of(new IntelligenceVerificationClient.MediaResult(mediaId, "inconclusive", "截图模糊")))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
        body.put("platformHandle", "@seedhunter");
        body.put("mediaIds", List.of(mediaId.toString()));
        Map<String, Object> resp = client().post()
                .uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String submission = (String) ((Map<String, Object>) resp.get("data")).get("id");

        assertThat(verificationStatus(submission)).as("不确定即人工（VERIFICATION 待判定队列）")
                .isEqualTo("inconclusive");
    }

    // ---------- helpers ----------

    private static Map<String, Object> interactionBody(String org) {
        Map<String, Object> body = interactionBodyWith("https://www.xiaohongshu.com/post/1", "like");
        body.put("organizationId", org);  // 与签发断言同 org，否则 requireScope 403
        return body;
    }

    private static Map<String, Object> interactionBodyWith(String targetUrl, String actionType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", UUID.randomUUID().toString());
        body.put("title", "互动任务");
        body.put("contentForm", "interaction");
        body.put("requirements", Map.of("interaction",
                Map.of("targetUrl", targetUrl, "actionType", actionType)));
        return body;
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec createTask(
            String merchant, String org, Map<String, Object> body) {
        if (!body.containsKey("organizationId")) {
            body.put("organizationId", org);
        }
        return client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    private String publishInteractionTask(String merchant, String org) {
        Map<String, Object> resp = createTask(merchant, org, interactionBody(org)).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
        return taskId;
    }

    private String applyAndAccept(String recommender, String task, String merchant, String org) {
        Map<String, Object> applied = client().post().uri("/api/tasks/" + task + "/applications")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
        // 互动任务无资金字段 → 非资金型，accept 直连（无 Saga）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/accept")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();
        return appId;
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec submitRaw(
            String recommender, String task, String app, String platformHandle) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentUrl", "https://www.xiaohongshu.com/post/1");
        body.put("note", "已完成点赞");
        if (platformHandle != null) {
            body.put("platformHandle", platformHandle);
        }
        return client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    private String submit(String recommender, String task, String app, String platformHandle) {
        Map<String, Object> resp = submitRaw(recommender, task, app, platformHandle).expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private void stubLinkPassed() {
        when(linkChecker.check(anyString()))
                .thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "链接可达")));
    }

    /** 轮询该 submission 的最新核验 run checks JSON（提交自动触发核验，异步完成）。 */
    private String awaitRunChecks(String submissionId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String checks = null;
        while (System.currentTimeMillis() < deadline) {
            checks = db.sql("SELECT checks::text AS c FROM engagement_verification_run"
                            + " WHERE submission_id = CAST(:s AS uuid) ORDER BY run_number DESC LIMIT 1")
                    .bind("s", submissionId)
                    .map(r -> r.get("c", String.class)).one().block();
            if (checks != null) {
                return checks;
            }
            sleep(150L);
        }
        throw new AssertionError("verification run not recorded for " + submissionId);
    }

    private String verificationStatus(String submissionId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = db.sql("SELECT status FROM engagement_verification"
                            + " WHERE submission_id = CAST(:s AS uuid)")
                    .bind("s", submissionId)
                    .map(r -> r.get("status", String.class)).one().block();
            if (status != null) {
                return status;
            }
            sleep(150L);
        }
        throw new AssertionError("verification not recorded for " + submissionId + " (last=" + status + ")");
    }

    private IntelligenceMediaClient.MediaMetadata mediaMeta(UUID id, String owner, String applicationId) {
        return new IntelligenceMediaClient.MediaMetadata(
                id, owner, "engagement_attachment", "application", applicationId, "active",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "image/png", 1234L,
                Instant.parse("2026-12-31T00:00:00Z"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
