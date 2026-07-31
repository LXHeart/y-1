package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationException;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * application 聚合端到端（草场 Epic 4 Slice 4B / 4F）。继承 {@link MarketplaceItSupport}。
 *
 * <p>覆盖 apply / accept / reject / withdraw / list 全链路（4B）+ 资金预留 Saga（4F）。身份门禁（recommender 报名、
 * merchant 且 owner 接受）、名额控制、去重、状态守卫、资源级自查、outbox 事件。
 *
 * <p>4F：资金型任务（bounty&gt;0）accept 走异步 Saga（202 + 轮询）。finance HTTP 边界用 {@code @MockBean} 替身——
 * 全 Saga 编排（真 activity + 真 DB + temporal test-server）在测内跑通，仅 finance 出站 HTTP 被 mock。
 * 非资金型任务（bounty null/0）仍走 4B 直连 accept（同步 200），故既有 4B 断言不回归。
 */
class ApplicationControllerIT extends MarketplaceItSupport {

    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    /** finance 出站边界替身（Slice 4F）：真 Saga 跑通，仅 finance HTTP 被 mock，按用例桩 reserve 结果。 */
    @MockitoBean
    private FinanceEscrowClient financeClient;

    /** 争议检查替身（Slice 6A）：默认 false（无争议→settled）；held 用例桩 true。真实现 HttpDisputeChecker 调 trust。 */
    @MockitoBean
    private com.grassland.marketplace.workflow.saga.DisputeChecker disputeChecker;

    /** intelligence media 中转边界替身（Slice 11 Stage 2）：按用例桩 metadata/downloadUrl。真实现 IntelligenceMediaClient 调 intelligence。 */
    @MockitoBean
    private IntelligenceMediaClient mediaClient;

    /** 附件挂接 spy（Slice 11 Stage 2）：默认透传真实实现；原子性用例注入 attach 失败验证零残留。 */
    @MockitoSpyBean
    private SubmissionAttachmentRepository attachmentRepo;

    /** 链接可达性核验替身（Verification Stage 2/4）：免真打 example.com；按用例桩 passed/failed。 */
    @MockitoBean
    private LinkReachabilityChecker linkChecker;

    /** intelligence AI 视觉核验出站边界替身（Verification Stage 4）：按用例桩 analyze；真实现调 intelligence。 */
    @MockitoBean
    private IntelligenceVerificationClient verificationClient;

    // ---------- apply ----------

    @Test
    void recommenderAppliesAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "我能拍"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.taskId").isEqualTo(task)
                .jsonPath("$.data.recommenderAccountId").isEqualTo(rec)
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.note").isEqualTo("我能拍");

        assertThat(outboxCount("ApplicationSubmitted", task)).isEqualTo(1);
        // Slice 12 Stage 3：发射端补齐商家侧收件人，identity 通知中心不反查 marketplace。
        assertThat(outboxPayloadField("ApplicationSubmitted", task, "taskOwnerId")).isEqualTo(merchant);
    }

    @Test
    void applyWithoutAssertionUnauthorized() {
        String task = publishTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null);
        client().post().uri("/api/tasks/" + task + "/applications")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void merchantCannotApply() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void applyUnknownTaskNotFound() {
        client().post().uri("/api/tasks/" + ZERO_UUID + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void applyClosedTaskConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        db.sql("UPDATE task SET status = 'closed' WHERE id = CAST(:id AS uuid)").bind("id", task).then().block();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void applyRejectedWhenSlotsFull() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, 1);  // 名额=1
        String appA = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, appA);  // accepted=1，名额满

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);  // fail-fast
    }

    @Test
    void duplicateApplyConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        apply(rec, task);  // 201
        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);  // 已报名
    }

    // ---------- accept（非资金型：4B 直连同步 200） ----------

    @Test
    void merchantAcceptsAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);  // 无 bounty → 非资金型
        String app = apply(UUID.randomUUID().toString(), task);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()  // 同步 200（非资金型）
                .jsonPath("$.data.status").isEqualTo("accepted")
                .jsonPath("$.data.reviewedByAccountId").isEqualTo(merchant);

        assertThat(outboxCount("ApplicationAccepted", task)).isEqualTo(1);
        assertThat(acceptedCount(task)).isEqualTo(1);
    }

    @Test
    void acceptUnknownAppNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/applications/" + ZERO_UUID + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void acceptAlreadyProcessedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, app);  // 200
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerAcceptForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void recommenderCannotAccept() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void acceptBlockedWhenSlotsFull() {
        // 两份 pending 先报名（accepted=0 < 名额 1），再 accept 第一份占满，accept 第二份 → 409。
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, 1);
        String appA = apply(UUID.randomUUID().toString(), task);
        String appB = apply(UUID.randomUUID().toString(), task);
        accept(merchant, task, appA);  // 名额占满
        client().post().uri("/api/tasks/" + task + "/applications/" + appB + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- accept（资金型：4F 异步 Saga 202 + 轮询） ----------

    @Test
    void monetaryAcceptStartsSagaAndActivates() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);  // bounty=500 → 资金型
        String app = apply(UUID.randomUUID().toString(), task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted().expectBody()  // 异步 202
                .jsonPath("$.data.status").isEqualTo("reserving")
                .jsonPath("$.data.applicationId").isEqualTo(app)
                .jsonPath("$.data.workflowId").isEqualTo("accept-" + app);

        awaitReservation(merchant, task, app, "accepted");
        assertThat(appStatus(app)).isEqualTo("accepted");
        assertThat(outboxCount("ApplicationAccepted", task)).isEqualTo(1);
    }

    @Test
    void monetaryAcceptInsufficientFundsCompensates() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 600L);  // bounty=600，余额不足场景由 mock 决定
        String app = apply(UUID.randomUUID().toString(), task);

        when(financeClient.reserve(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.just(ReserveResult.insufficientFunds()));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();

        awaitReservation(merchant, task, app, "compensated");
        assertThat(appStatus(app)).isEqualTo("pending");  // 回退可重试
        assertThat(failureReason(app)).isEqualTo("insufficient_funds");
        assertThat(outboxCount("ApplicationReservationFailed", task)).isEqualTo(1);
    }

    @Test
    void reservationPollRequiresOwner() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(UUID.randomUUID().toString(), task);
        // 非 owner 轮询 → 403
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/reservation")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- confirm / settlement（Slice 5A） ----------

    @Test
    void monetaryConfirmSettlesAfterWindow() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.capture(org, app)).thenReturn(Mono.empty());

        // 4F accept → 202 → 轮询 accepted（reserve 成功）
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 确认前必须有交付物（V5：confirm 不再是凭空点的）
        submit(recommender, task, app);

        // 5A confirm → 202 settling
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted().expectBody()
                .jsonPath("$.data.status").isEqualTo("settling")
                .jsonPath("$.data.workflowId").isEqualTo("settle-" + app);

        // 轮询 settlement → settled（窗口到期后 capture）
        awaitSettlement(merchant, task, app, "settled");
        assertThat(outboxCount("EngagementSettled", task)).isEqualTo(1);
    }

    @Test
    void confirmRejectedWhenNotAccepted() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(UUID.randomUUID().toString(), task);  // 仍 pending
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);  // 非 accepted
    }

    @Test
    void confirmHeldWhenDisputeOpen() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(disputeChecker.hasOpenDispute(anyString(), anyString())).thenReturn(true);  // 开争议 → held

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");
        submit(recommender, task, app);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitSettlement(merchant, task, app, "held");  // 窗口到期查到争议 → held（不 capture）
    }

    // ---------- 履约交付物（V5） ----------

    /**
     * 完整循环：提交 → 商家退回补交 → 修改重交 → 确认。
     *
     * <p>被退回的交付物不占 partial unique 的位，所以推荐官能改好重交——这正是「退回补交」要成立的前提。
     */
    @Test
    void submitRejectResubmitThenConfirm() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString()))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.capture(org, app)).thenReturn(Mono.empty());
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        String first = submit(recommender, task, app);
        // 已有待核验的一份 → 重复提交 409
        submitRaw(recommender, task, app, "https://example.com/again").expectStatus().isEqualTo(409);

        // 商家退回补交（带原因）
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + first + "/reject")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "缺少门店实拍"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("rejected")
                .jsonPath("$.data.reviewNote").isEqualTo("缺少门店实拍");
        assertThat(outboxCountByType("DeliverableRejected")).isGreaterThanOrEqualTo(1);

        // 退回后可以重交
        submit(recommender, task, app);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        // 确认即核验通过：历史两条 = 一条 rejected + 一条 accepted
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.submissions.length()").isEqualTo(2)
                .jsonPath("$.data.submissions[0].status").isEqualTo("accepted");
    }

    /** 没有交付物就确认 → 409。此前 confirm 是凭空点的，这条守卫是本 slice 的要点。 */
    @Test
    void confirmRejectedWithoutSubmission() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString()))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void submissionRejectsForeignRecommenderAndBadUrl() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString()))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 别人的履约 → 403
        submitRaw(UUID.randomUUID().toString(), task, app, "https://example.com/x")
                .expectStatus().isForbidden();
        // 非 http(s) 链接 → 4xx（凭证必须可核验）
        submitRaw(recommender, task, app, "已经发布了").expectStatus().is4xxClientError();
        // 无关第三方查看交付物 → 403
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- 履约交付物附件（Slice 11 Stage 2） ----------

    /** 非资金型任务建一份已接受报名（附件测试用，避开资金 Saga）。返回 {merchant, org, task, app}。 */
    private String[] acceptedNonMonetary(String recommender) {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(recommender, task);
        accept(merchant, task, app);
        return new String[]{merchant, org, task, app};
    }

    private IntelligenceMediaClient.MediaMetadata mediaMeta(UUID id, String owner) {
        return new IntelligenceMediaClient.MediaMetadata(
                id, owner, "engagement_attachment", "active", "image/png", 1234L,
                Instant.parse("2026-12-31T00:00:00Z"));
    }

    @Test
    void submitWithAttachmentsCreatesRowsAndEvent() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        when(mediaClient.metadata(org, m2)).thenReturn(Mono.just(mediaMeta(m2, rec)));

        Map<String, Object> data = submitWithMedia(rec, task, app, List.of(m1.toString(), m2.toString()));
        String submissionId = (String) data.get("id");

        assertThat(((List<?>) data.get("attachments"))).hasSize(2);
        assertThat(submissionCount(app)).isEqualTo(1);
        assertThat(attachmentCount(submissionId)).isEqualTo(2);
        // outbox 事件携带 mediaIds
        assertThat(outboxMediaIds(submissionId)).contains(m1.toString(), m2.toString());
    }

    /** IDOR：挂接他人附件 → 403，且无 submission/附件残留（校验在事务外，本就不写）。 */
    @Test
    void submitRejectsForeignAttachmentNoResidue() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        // metadata 放行（purpose/active 合法）但 owner 是别人 → IDOR 守卫 403
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, UUID.randomUUID().toString())));

        submitWithMediaRaw(rec, task, app, List.of(m1.toString())).expectStatus().isForbidden();

        assertThat(submissionCount(app)).isZero();
        assertThat(attachmentCountForApp(app)).isZero();
    }

    /** media 不可用（intelligence 已过滤 purpose 不符/非活跃/过期/已删 → 404→empty）→ 404，无残留。 */
    @Test
    void submitRejectsUnavailableAttachment() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.empty());  // intelligence 404

        submitWithMediaRaw(rec, task, app, List.of(m1.toString())).expectStatus().isNotFound();
        assertThat(submissionCount(app)).isZero();
    }

    /** 附件超量（7 > 6）→ 400（CreateSubmissionRequest 构造器抛 IllegalArgumentException→400）。 */
    @Test
    void submitRejectsTooManyAttachments() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];
        List<String> seven = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(7).map(UUID::toString).toList();
        submitWithMediaRaw(rec, task, app, seven).expectStatus().isBadRequest();
    }

    /** 无附件回归：mediaIds 省略仍可提交，attachments 为空。 */
    @Test
    void submitWithoutAttachmentsStillWorks() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];
        Map<String, Object> data = submitWithMedia(rec, task, app, List.of());
        assertThat(((List<?>) data.get("attachments"))).isEmpty();
    }

    @Test
    void listSubmissionsIncludesAttachments() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        String submissionId = (String) submitWithMedia(rec, task, app, List.of(m1.toString())).get("id");

        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.submissions[0].id").isEqualTo(submissionId)
                .jsonPath("$.data.submissions[0].attachments[0].mediaId").isEqualTo(m1.toString())
                .jsonPath("$.data.submissions[0].attachments[0].mimeType").isEqualTo("image/png");
    }

    /** 下载 URL：owner 与提交人都可见，外人 403。 */
    @Test
    void downloadUrlAuthorizedForOwnerAndSubmitter() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        when(mediaClient.downloadUrl(org, m1)).thenReturn(Mono.just(new IntelligenceMediaClient.MediaDownload(
                URI.create("https://minio.local/media/x?sig=abc"), Instant.parse("2026-12-31T00:00:00Z"))));
        String submissionId = (String) submitWithMedia(rec, task, app, List.of(m1.toString())).get("id");
        String uri = "/api/tasks/" + task + "/applications/" + app + "/submissions/" + submissionId
                + "/attachments/" + m1 + "/download-url";

        // 提交人可取
        client().get().uri(uri).header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.downloadUrl").isEqualTo("https://minio.local/media/x?sig=abc");
        // 商家（owner）可取
        client().get().uri(uri).header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk();
        // 无关第三方 403
        client().get().uri(uri).header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    /** 未挂接到该 submission 的 mediaId → findOne 空 → 404。 */
    @Test
    void downloadUrlRejectsUnattachedMedia() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];
        String submissionId = (String) submitWithMedia(rec, task, app, List.of()).get("id");
        UUID stranger = UUID.randomUUID();
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + submissionId
                        + "/attachments/" + stranger + "/download-url")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    /** media 已不可用（中转 404→empty）→ 404。 */
    @Test
    void downloadUrlRejectsUnavailableMedia() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        when(mediaClient.downloadUrl(org, m1)).thenReturn(Mono.empty());  // media 被删
        String submissionId = (String) submitWithMedia(rec, task, app, List.of(m1.toString())).get("id");

        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + submissionId
                        + "/attachments/" + m1 + "/download-url")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    /** 附件挂接失败（注入冲突→empty）→ 409，且整事务回滚，零残留（7C 原子性）。 */
    @Test
    void submitAttachmentFailureRollsBackNoResidue() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        doReturn(Mono.empty()).when(attachmentRepo).attach(anyString(), anyList());  // 模拟冲突

        submitWithMediaRaw(rec, task, app, List.of(m1.toString())).expectStatus().isEqualTo(409);

        assertThat(submissionCount(app)).isZero();
        assertThat(attachmentCountForApp(app)).isZero();
        assertThat(outboxCountForApp("DeliverableSubmitted", app)).isZero();
    }

    // ---------- reject ----------

    @Test
    void merchantRejectsAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String app = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/reject")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("rejected");
        assertThat(outboxCount("ApplicationRejected", task)).isEqualTo(1);
    }

    // ---------- list ----------

    @Test
    void ownerListsApplications() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        apply(UUID.randomUUID().toString(), task);
        apply(UUID.randomUUID().toString(), task);
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isEqualTo(2));
    }

    /**
     * 非 owner 只看得到**自己的**报名，不相干的人看到空列表。
     *
     * <p>此前是一律 403，后果是推荐官在自己的工作台里永远看不到自己报了什么——
     * 提交履约、开争议这些本该由推荐官发起的动作也就无从挂载（浏览器实测发现）。
     */
    @Test
    void nonOwnerSeesOnlyOwnApplication() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String mine = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        apply(mine, task);
        apply(UUID.randomUUID().toString(), task);   // 别人的报名

        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(mine, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].recommenderAccountId").isEqualTo(mine);

        // 与该任务无关的账号：空列表（不泄露有几个人报名）
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);
    }

    // ---------- withdraw ----------

    @Test
    void recommenderWithdrawsOwnPending() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        String app = apply(rec, task);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/withdraw")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("withdrawn");
        assertThat(outboxCount("ApplicationWithdrawn", task)).isEqualTo(1);
        assertThat(outboxPayloadField("ApplicationWithdrawn", task, "taskOwnerId")).isEqualTo(merchant);
    }

    @Test
    void withdrawAcceptedConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String rec = UUID.randomUUID().toString();
        String app = apply(rec, task);
        accept(merchant, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/withdraw")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void withdrawOthersApplicationForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String appA = apply(UUID.randomUUID().toString(), task);
        client().post().uri("/api/tasks/" + task + "/applications/" + appA + "/withdraw")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- 履约核验（Verification v1 Stage 2/4） ----------

    /** 商家触发核验：附件 + 链接 → link_reachability + ai_visual 两项，聚合 passed。 */
    @Test
    void verificationChecksRunLinkAndAi() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        String submissionId = (String) submitWithMedia(rec, task, app, List.of(m1.toString())).get("id");

        when(linkChecker.check(anyString())).thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        when(verificationClient.analyze(eq(org), eq(List.of(m1)), any(), any(), any()))
                .thenReturn(Mono.just(new IntelligenceVerificationClient.VerificationAnalysis("passed",
                        List.of(new IntelligenceVerificationClient.MediaResult(m1, "passed", "真实")))));

        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("passed")
                .jsonPath("$.data.checks.length()").isEqualTo(2);
        verify(verificationClient).analyze(eq(org), eq(List.of(m1)), any(), any(), any());
    }

    /** 无附件 → 仅 link_reachability，AI check 跳过（verificationClient 从不被调）。 */
    @Test
    void verificationChecksLinkOnlyWithoutAttachments() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);  // 无附件

        when(linkChecker.check(anyString())).thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));

        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("passed")
                .jsonPath("$.data.checks.length()").isEqualTo(1)
                .jsonPath("$.data.checks[0].type").isEqualTo("link_reachability");
        verify(verificationClient, never()).analyze(any(), any(), any(), any(), any());
    }

    /** intelligence 不可用 → ai_visual 降级 inconclusive，拖累整体 inconclusive，但不 fail 也不 5xx。 */
    @Test
    void verificationChecksAiUnavailableIsInconclusive() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        UUID m1 = UUID.randomUUID();
        when(mediaClient.metadata(org, m1)).thenReturn(Mono.just(mediaMeta(m1, rec)));
        String submissionId = (String) submitWithMedia(rec, task, app, List.of(m1.toString())).get("id");

        when(linkChecker.check(anyString())).thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        when(verificationClient.analyze(any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new IntelligenceVerificationException("intelligence down")));

        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("inconclusive")
                .jsonPath("$.data.checks.length()").isEqualTo(2)
                .jsonPath("$.data.checks[1].type").isEqualTo("ai_visual")
                .jsonPath("$.data.checks[1].status").isEqualTo("inconclusive");
    }

    /** 非任务 owner 的商家触发核验 → 403。 */
    @Test
    void verificationChecksRejectsNonOwner() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);
        runChecksRaw(UUID.randomUUID().toString(), null, task, app, submissionId).expectStatus().isForbidden();
    }

    /** 未知 submissionId → 404。 */
    @Test
    void verificationChecksRejectsUnknownSubmission() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        submit(rec, task, app);
        runChecksRaw(merchant, org, task, app, ZERO_UUID).expectStatus().isNotFound();
    }

    /** 核验 failed → 可选 confirm 闸门阻断（仅 failed→409；absent/passed/inconclusive 照常）。 */
    @Test
    void confirmBlockedWhenVerificationFailed() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);

        when(linkChecker.check(anyString())).thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("failed", "HTTP 404")));
        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("failed");

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 核验记录落库后 → listSubmissions 内联带出 verification。 */
    @Test
    void listSubmissionsIncludesVerificationFold() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);

        when(linkChecker.check(anyString())).thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk();

        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.submissions[0].verification.status").isEqualTo("passed")
                .jsonPath("$.data.submissions[0].verification.checks[0].type").isEqualTo("link_reachability");
    }

    // ---------- helpers ----------

    private void accept(String merchant, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk();
    }

    /** 商家触发履约核验（须任务 owner）。 */
    private WebTestClient.ResponseSpec runChecksRaw(String merchant, String org, String task, String app, String submissionId) {
        return client().post().uri("/api/tasks/" + task + "/applications/" + app
                        + "/submissions/" + submissionId + "/verification/checks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange();
    }

    /** 提交一份交付物，返回 submissionId。 */
    @SuppressWarnings("unchecked")
    private String submit(String recommender, String task, String app) {
        Map<String, Object> resp = submitRaw(recommender, task, app, "https://example.com/post/" + app)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private WebTestClient.ResponseSpec submitRaw(String recommender, String task, String app, String url) {
        return client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", url, "note", "已按要求发布"))
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> submitWithMedia(String recommender, String task, String app, List<String> mediaIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentUrl", "https://example.com/post/" + app);
        body.put("note", "已按要求发布");
        body.put("mediaIds", mediaIds);
        Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) resp.get("data");
    }

    private WebTestClient.ResponseSpec submitWithMediaRaw(String recommender, String task, String app, List<String> mediaIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentUrl", "https://example.com/post/" + app);
        body.put("note", "已按要求发布");
        body.put("mediaIds", mediaIds);
        return client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    private long submissionCount(String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM engagement_submission WHERE application_id = CAST(:a AS uuid)")
                .bind("a", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private long attachmentCount(String submissionId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM engagement_submission_attachment WHERE submission_id = CAST(:s AS uuid)")
                .bind("s", submissionId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private long attachmentCountForApp(String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM engagement_submission_attachment a "
                        + "JOIN engagement_submission s ON a.submission_id = s.id WHERE s.application_id = CAST(:a AS uuid)")
                .bind("a", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private String outboxMediaIds(String submissionId) {
        return db.sql("SELECT payload->'mediaIds' AS m FROM marketplace_outbox "
                        + "WHERE event_type = 'DeliverableSubmitted' AND aggregate_id = :sid")
                .bind("sid", submissionId).map(r -> r.get("m", String.class)).one().block();
    }

    /** 按 applicationId 限定的事件计数（共享 testcontainer DB 跨用例累积，原子性用例需按 app 限定）。 */
    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId)
                .map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private long outboxCountByType(String eventType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox WHERE event_type = :et")
                .bind("et", eventType).map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    @SuppressWarnings("unchecked")
    private String apply(String recommender, String task) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String publishTask(String merchant, String org, Integer maxSlots) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "任务");
        if (maxSlots != null) {
            b.put("maxSlots", maxSlots);
        }
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String publishTaskBounty(String merchant, String org, Integer maxSlots, Long bountyCents) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "赏金任务");
        if (maxSlots != null) {
            b.put("maxSlots", maxSlots);
        }
        if (bountyCents != null) {
            b.put("bountyCents", bountyCents);
        }
        // D-05：资金型任务（bountyCents>0）须由可交易 tier 发布——basic_publish 的 maxTxAmountCents=0 → 403。
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    /** 轮询预留结局，至 expected 或超时（10s，temporal test-server 通常 ms 级完成）。 */
    private void awaitReservation(String merchant, String task, String app, String expected) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = pollReservationStatus(merchant, task, app);
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("reservation did not reach " + expected + " (last=" + status + ")");
    }

    @SuppressWarnings("unchecked")
    private String pollReservationStatus(String merchant, String task, String app) {
        Map<String, Object> resp = client().get()
                .uri("/api/tasks/" + task + "/applications/" + app + "/reservation")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("status");
    }

    /** 轮询结算结局，至 expected 或超时（窗口 + activity；test-server + mock finance 通常 <10s）。 */
    private void awaitSettlement(String merchant, String task, String app, String expected) {
        long deadline = System.currentTimeMillis() + 30_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = pollSettlementStatus(merchant, task, app);
            if (expected.equals(status)) {
                return;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("settlement did not reach " + expected + " (last=" + status + ")");
    }

    @SuppressWarnings("unchecked")
    private String pollSettlementStatus(String merchant, String task, String app) {
        Map<String, Object> resp = client().get()
                .uri("/api/tasks/" + task + "/applications/" + app + "/settlement")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("status");
    }

    private String appStatus(String app) {
        return db.sql("SELECT status FROM task_application WHERE id = CAST(:id AS uuid)")
                .bind("id", app)
                .map(r -> r.get("status", String.class)).one().block();
    }

    private String failureReason(String app) {
        return db.sql("SELECT payload->>'reason' AS r FROM marketplace_outbox"
                        + " WHERE event_type = 'ApplicationReservationFailed' AND aggregate_id = :id")
                .bind("id", app)
                .map(r -> r.get("r", String.class)).one().block();
    }

    /** 读取按 taskId 限定的事件 payload 顶层字段（Slice 12 Stage 3 收件人字段断言）。 */
    private String outboxPayloadField(String eventType, String taskId, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(r -> r.get("v", String.class)).one().block();
    }

    private long outboxCount(String eventType, String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private int acceptedCount(String taskId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM task_application"
                        + " WHERE task_id = CAST(:tid AS uuid) AND status = 'accepted'")
                .bind("tid", taskId)
                .map(r -> r.get("c", Integer.class)).one().block();
    }
}
