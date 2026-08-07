package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationException;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    /** trust 出站边界替身（D-03 contest）：开 merchant_rejection / SLA auto-finalize。 */
    @MockitoBean
    private TrustDisputeClient trustDisputeClient;

    /** 客服 SLA 启窗 spy（D-03 审阅 F3）：默认透传真实 starter；提交后失败用例注入异常验证不误判。 */
    @MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.MerchantRejectionReviewWorkflowStarter rejectionStarter;

    @MockitoSpyBean
    private com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter settlementStarter;

    /** 声誉聚合 spy：列表排序必须批量读取，不能按报名数产生 N+1。 */
    @MockitoSpyBean
    private ReputationService reputationService;

    @org.springframework.beans.factory.annotation.Autowired
    private TaskApplicationRepository applicationRepo;

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

    @Test
    void recommenderBelowTaskMinimumLevelCannotApply() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskAtMinimumLevel(merchant, org, 2);

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
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
                .jsonPath("$.data.reviewedByAccountId").isEqualTo(merchant)
                .jsonPath("$.data.reputationLevelAtAccept").isEqualTo(1)
                .jsonPath("$.data.reputationPolicyVersionAtAccept").isEqualTo(1)
                .jsonPath("$.data.settlementDelayDaysAtAccept").isEqualTo(2)
                .jsonPath("$.data.commissionBonusBpsAtAccept").isEqualTo(0)
                .jsonPath("$.data.premiumSupportAtAccept").isEqualTo(false);

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

    // ---------- 商家确认窗口（D-03） ----------

    /** 推荐官提交履约即起确认窗口：deadline 落库 + outbox ConfirmationWindowEntered + 轮询 awaiting_confirmation。 */
    @Test
    void confirmationWindowEnteredOnSubmit() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        submit(recommender, task, app);   // 提交即起确认窗口

        // 轮询 confirmation → awaiting_confirmation + deadline（窗口未到期）
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/confirmation")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("awaiting_confirmation")
                .jsonPath("$.data.deadline").isNotEmpty()
                .jsonPath("$.data.remainingSeconds").isNumber();
        assertThat(outboxCount("ConfirmationWindowEntered", task)).isEqualTo(1);
    }

    /** 商家在窗口内确认 → confirmed_at 设；轮询 confirmation → confirmed（与到期自动结算经 confirmed_at 条件安全收敛）。 */
    @Test
    void merchantConfirmDuringWindowShowsConfirmed() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.capture(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");
        submit(recommender, task, app);

        // 商家确认 → confirmed_at 设
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();

        // 重复确认幂等：200 confirmed，不重启第二个 workflow、不重发 MerchantConfirmed。
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("confirmed")
                .jsonPath("$.data.applicationId").isEqualTo(app);
        assertThat(outboxCount("MerchantConfirmed", task)).isEqualTo(1);

        // 轮询 confirmation → confirmed
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/confirmation")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("confirmed");
    }

    @Test
    void retryAfterInitialSettlementStartFailureConvergesWithoutDuplicateCapture() {
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
        submit(recommender, task, app);

        doReturn(Mono.error(new RuntimeException("settlement start unavailable")))
                .doCallRealMethod()
                .when(settlementStarter).start(any(Task.class), any(TaskApplication.class));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().is5xxServerError();

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("confirmed")
                .jsonPath("$.data.applicationId").isEqualTo(app);

        verify(settlementStarter, times(2)).start(any(Task.class), any(TaskApplication.class));
        verify(financeClient, timeout(5_000).times(1)).capture(org, app);
        assertThat(outboxCount("MerchantConfirmed", task)).isEqualTo(1);
    }

    // ---------- 商家取消任务主动退款（D-03 §5） ----------

    /** cancel 任务 → 已 accept 未提交凭证的 engagement 全额返还商家（finance release）+ 事件 + refundedCount=1。 */
    @Test
    void cancelRefundsAcceptedNoSubmissionEngagement() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.release(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 未提交凭证即取消 → 退款该 engagement
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("cancelled")
                .jsonPath("$.data.refundedCount").isEqualTo(1);

        verify(financeClient).release(org, app);
        assertThat(outboxCountByType("EngagementRefundedOnCancel")).isGreaterThanOrEqualTo(1);

        // 退款后 application 必须置终态 refunded：留在 accepted 会让推荐官侧一直显示「进行中」。
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].status").isEqualTo("refunded");

        // 重复 cancel 幂等：不再重复 release、不再重复通知。
        long refundEvents = outboxCountByType("EngagementRefundedOnCancel");
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.refundedCount").isEqualTo(0);
        verify(financeClient, times(1)).release(org, app);
        assertThat(outboxCountByType("EngagementRefundedOnCancel")).isEqualTo(refundEvents);
    }

    /** 已提交凭证的 engagement 不被退款（照常结算）；cancel 后任务不再接受新提交 → 409。 */
    @Test
    void submitRejectedOnCancelledTask() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(recommender, task);
        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString())).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.release(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 取消任务（该 engagement 未提交 → 被退款）
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk();

        // 取消后提交履约 → 409
        submitRaw(recommender, task, app, "https://example.com/late").expectStatus().isEqualTo(409);
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

    /**
     * D-03 规则 4：补证（退回重交）上限 2 次。第 3 次退回 → 409，submission 留 submitted，
     * 确认窗口照常跑到自动结算（不再允许补证）。
     */
    @Test
    void rejectDeliverableCappedAtTwoSupplements() {
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

        String merchantHeader = sign(merchant, "merchant", org, "basic_publish");
        // 补证 1：提交 → 退回
        String s1 = submit(recommender, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + s1 + "/reject")
                .header("X-Grassland-Identity", merchantHeader)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "补证1"))
                .exchange().expectStatus().isOk();
        // 补证 2：重交 → 退回（达上限）
        String s2 = submit(recommender, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + s2 + "/reject")
                .header("X-Grassland-Identity", merchantHeader)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "补证2"))
                .exchange().expectStatus().isOk();
        // 第 3 次退回 → 409（已达上限），第 3 份 submission 留 submitted
        String s3 = submit(recommender, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions/" + s3 + "/reject")
                .header("X-Grassland-Identity", merchantHeader)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "补证3"))
                .exchange().expectStatus().isEqualTo(409);
        // 列表（created_at DESC）：第 3 份（最新）仍 submitted，前两份 rejected ⇒ 第 3 次退回被拒
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", merchantHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.submissions.length()").isEqualTo(3)
                .jsonPath("$.data.submissions[0].status").isEqualTo("submitted")
                .jsonPath("$.data.submissions[1].status").isEqualTo("rejected")
                .jsonPath("$.data.submissions[2].status").isEqualTo("rejected");
        // 达上限后，不存在的 submissionId 仍必须回 404（审阅 F4）：
        // 上限判定排在存在性校验之后，否则「id 写错」会被报成「补证次数已达上限」，
        // 把调用方引向确认履约/开争议这类不可逆动作。
        client().post().uri("/api/tasks/" + task + "/applications/" + app
                        + "/submissions/" + UUID.randomUUID() + "/reject")
                .header("X-Grassland-Identity", merchantHeader)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "不存在"))
                .exchange().expectStatus().isNotFound();
    }

    /** 退回端点 submissionId 必须属于 URL 中的 application，跨履约 IDOR → 404。 */
    @Test
    void rejectDeliverableRejectsForeignSubmissionId() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String recA = UUID.randomUUID().toString();
        String recB = UUID.randomUUID().toString();
        String appA = apply(recA, task);
        String appB = apply(recB, task);
        accept(merchant, task, appA);
        accept(merchant, task, appB);
        String submissionA = submit(recA, task, appA);
        submit(recB, task, appB);

        client().post().uri("/api/tasks/" + task + "/applications/" + appB
                        + "/submissions/" + submissionA + "/reject")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "越界"))
                .exchange().expectStatus().isNotFound();
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

    @Test
    void ownerApplicationListRanksHigherReputationFirst() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String lv1 = UUID.randomUUID().toString();
        String lv2 = UUID.randomUUID().toString();
        seedCompletedEngagements(lv2, merchant, org, 6);
        apply(lv1, task);
        apply(lv2, task);
        clearInvocations(reputationService);

        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].recommenderAccountId").isEqualTo(lv2)
                .jsonPath("$.data[0].reputationLevel").isEqualTo(2)
                .jsonPath("$.data[0].taskPriorityWeight").isEqualTo(110)
                .jsonPath("$.data[1].reputationLevel").isEqualTo(1);

        verify(reputationService).snapshots(List.of(lv1, lv2));
        verify(reputationService, never()).snapshot(anyString());
    }

    @Test
    void ownerApplicationListUsesIdAsStableFinalTieBreaker() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        String lowerId = "10000000-0000-0000-0000-000000000001";
        String higherId = "f0000000-0000-0000-0000-000000000002";
        String firstRecommender = UUID.randomUUID().toString();
        String secondRecommender = UUID.randomUUID().toString();
        db.sql("""
                INSERT INTO task_application(
                    id, task_id, recommender_account_id, status, bounty_cents, created_at, updated_at)
                VALUES
                    (CAST(:higherId AS uuid), CAST(:taskId AS uuid), CAST(:firstRec AS uuid),
                     'pending', 0, TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:00:00Z'),
                    (CAST(:lowerId AS uuid), CAST(:taskId AS uuid), CAST(:secondRec AS uuid),
                     'pending', 0, TIMESTAMPTZ '2026-01-01 00:00:00Z', TIMESTAMPTZ '2026-01-01 00:00:00Z')
                """)
                .bind("higherId", higherId).bind("lowerId", lowerId).bind("taskId", task)
                .bind("firstRec", firstRecommender).bind("secondRec", secondRecommender)
                .fetch().rowsUpdated().block();

        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].id").isEqualTo(lowerId)
                .jsonPath("$.data[1].id").isEqualTo(higherId);
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

    /** D-03：仅系统核实 passed 的履约可 contest，落商家异议 + ops_case + outbox，轮询显示 contested。 */
    @Test
    void merchantContestsVerifiedSubmissionToCustomerService() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);
        when(linkChecker.check(anyString()))
                .thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("passed");
        String disputeId = UUID.randomUUID().toString();
        when(trustDisputeClient.openMerchantRejection(org, app, merchant, "核实截图与实际不符"))
                .thenReturn(Mono.just(disputeId));
        when(trustDisputeClient.autoFinalizeMerchantRejection(org, disputeId)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/contest")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "核实截图与实际不符"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("contested")
                .jsonPath("$.data.disputeId").isEqualTo(disputeId)
                .jsonPath("$.data.reason").isEqualTo("核实截图与实际不符");

        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/confirmation")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("contested")
                .jsonPath("$.data.disputeId").isEqualTo(disputeId);
        // confirmed_at 只为 reconciliation 资金门控，普通 confirm 必须拒绝而非误报幂等成功。
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);

        assertThat(outboxCountByType("MerchantContested")).isGreaterThanOrEqualTo(1);
        Integer cases = db.sql("SELECT COUNT(*)::int AS c FROM ops_case"
                        + " WHERE source_kind='merchant_rejection' AND source_ref=:dispute AND application_id=:app"
                        + " AND severity='high'")
                .bind("dispute", disputeId).bind("app", app).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(cases).isEqualTo(1);
    }

    /**
     * D-03 审阅 F3：contest 本地事务已提交、仅 SLA 启窗失败时**不得** auto-finalize ——
     * 那等于无客服裁定就把商家异议判给推荐官。要求：报错让客户端重试，异议标记留库，
     * 重试走幂等分支补启 workflow 并返回 contested。
     */
    @Test
    void contestDoesNotAutoFinalizeWhenOnlyWorkflowStartFails() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        String submissionId = submit(rec, task, app);
        when(linkChecker.check(anyString()))
                .thenReturn(Mono.just(new LinkReachabilityChecker.CheckResult("passed", "HTTP 200")));
        runChecksRaw(merchant, org, task, app, submissionId).expectStatus().isOk();
        String disputeId = UUID.randomUUID().toString();
        when(trustDisputeClient.openMerchantRejection(org, app, merchant, "核实截图与实际不符"))
                .thenReturn(Mono.just(disputeId));
        when(trustDisputeClient.autoFinalizeMerchantRejection(org, disputeId)).thenReturn(Mono.empty());
        doReturn(Mono.error(new IllegalStateException("temporal down")))
                .when(rejectionStarter).start(eq(app), eq(disputeId), eq(org), anyLong());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/contest")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "核实截图与实际不符"))
                .exchange().expectStatus().is5xxServerError();

        verify(trustDisputeClient, never()).autoFinalizeMerchantRejection(org, disputeId);
        client().get().uri("/api/tasks/" + task + "/applications/" + app + "/confirmation")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("contested");

        // 重启恢复：同一 disputeId 补启窗后返回 contested（不重复开 trust 案）。
        doReturn(Mono.empty()).when(rejectionStarter).start(eq(app), eq(disputeId), eq(org), anyLong());
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/contest")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "核实截图与实际不符"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.disputeId").isEqualTo(disputeId);
        verify(trustDisputeClient, times(1)).openMerchantRejection(org, app, merchant, "核实截图与实际不符");
    }

    /** F6：本地 contest claim 已落时，Timer 的 guarded autoConfirm 必须输掉，且不依赖 trust 远端可见性。 */
    @Test
    void contestClaimDeterministicallyBlocksAutoConfirm() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];

        TaskApplication claimed = applicationRepo.claimContest(app, task, "并发门闩").block();
        assertThat(claimed).isNotNull();
        assertThat(claimed.contestRequestedAt()).isNotNull();
        assertThat(applicationRepo.autoConfirm(app, task).block()).isNull();

        TaskApplication current = applicationRepo.findById(app).block();
        assertThat(current.confirmedAt()).isNull();
        assertThat(current.autoConfirmedAt()).isNull();
    }

    /** F6 对称赢家：auto-confirm 已提交后，contest claim 必须返回空，不能在 capture 之后反向接管。 */
    @Test
    void autoConfirmDeterministicallyBlocksContestClaim() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];

        assertThat(applicationRepo.autoConfirm(app, task).block()).isNotNull();
        assertThat(applicationRepo.claimContest(app, task, "太晚了").block()).isNull();
        assertThat(applicationRepo.findById(app).block().contestRequestedAt()).isNull();
    }

    /** F6 真并发回归：同一行上的 claimContest/autoConfirm 同时开始，数据库只能返回一个赢家。 */
    @Test
    void concurrentContestAndAutoConfirmHaveExactlyOneWinner() throws Exception {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String task = s[2], app = s[3];
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<TaskApplication> contestResult = new AtomicReference<>();
        AtomicReference<TaskApplication> confirmResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread contest = Thread.ofPlatform().start(() -> runConcurrentUpdate(
                ready, start, failure, () -> contestResult.set(applicationRepo.claimContest(app, task, "同时开始").block())));
        Thread confirm = Thread.ofPlatform().start(() -> runConcurrentUpdate(
                ready, start, failure, () -> confirmResult.set(applicationRepo.autoConfirm(app, task).block())));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        contest.join(10_000L);
        confirm.join(10_000L);
        assertThat(contest.isAlive()).isFalse();
        assertThat(confirm.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat((contestResult.get() != null) ^ (confirmResult.get() != null)).isTrue();

        TaskApplication current = applicationRepo.findById(app).block();
        assertThat(current).isNotNull();
        assertThat(current.contestRequestedAt() != null && current.autoConfirmedAt() != null).isFalse();
        if (contestResult.get() != null) {
            assertThat(current.contestRequestedAt()).isNotNull();
            assertThat(current.confirmedAt()).isNull();
        } else {
            assertThat(current.autoConfirmedAt()).isNotNull();
            assertThat(current.contestRequestedAt()).isNull();
        }
    }

    /** 未核实 / 非 passed 的履约不得使用客服拒绝通道。 */
    @Test
    void contestRequiresPassedVerification() {
        String rec = UUID.randomUUID().toString();
        String[] s = acceptedNonMonetary(rec);
        String merchant = s[0], org = s[1], task = s[2], app = s[3];
        submit(rec, task, app);

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/contest")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("reason", "不同意"))
                .exchange().expectStatus().isEqualTo(409);
        verify(trustDisputeClient, never()).openMerchantRejection(any(), any(), any(), any());
    }

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

    // ---------- GL-P1-TASK-001 Stage 1：close/cancel/deadline 只门控「新报名」 ----------

    /** 报名截止（指定时间，已过）→ 新报名 409。 */
    @Test
    void applyRejectedAfterApplicationDeadline() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTaskWithDeadline(merchant, org, Instant.now().minusSeconds(3600));

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "迟到的报名"))
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 关闭报名后 → 新报名 409（任务 published→closed）。 */
    @Test
    void applyRejectedAfterClose() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/close")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 取消任务后 → 新报名 409（任务 published→cancelled）。 */
    @Test
    void applyRejectedAfterCancel() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk();

        client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    private void runConcurrentUpdate(CountDownLatch ready, CountDownLatch start,
                                     AtomicReference<Throwable> failure, Runnable update) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent update start timed out");
            }
            update.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
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
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        markPublished(taskId);
        return taskId;
    }

    @SuppressWarnings("unchecked")
    private String publishTaskAtMinimumLevel(String merchant, String org, int minimumLevel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", org);
        body.put("title", "等级专属任务");
        body.put("minRecommenderLevel", minimumLevel);
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) response.get("data")).get("id");
        markPublished(taskId);
        return taskId;
    }

    private void seedCompletedEngagements(String recommender, String merchant, String org, int count) {
        for (int i = 0; i < count; i++) {
            String taskId = UUID.randomUUID().toString();
            String applicationId = UUID.randomUUID().toString();
            db.sql("INSERT INTO task(id, owner_account_id, organization_id, title, status, published_at) "
                            + "VALUES (CAST(:task AS uuid), CAST(:merchant AS uuid), CAST(:org AS uuid), :title, 'published', now())")
                    .bind("task", taskId).bind("merchant", merchant).bind("org", org).bind("title", "历史任务 " + i)
                    .then().block();
            db.sql("INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents, "
                            + "decided_at, confirmed_at, reputation_level_at_accept,"
                            + "reputation_policy_version_at_accept, settlement_delay_days_at_accept,"
                            + "commission_bonus_bps_at_accept, premium_support_at_accept)"
                            + " VALUES (CAST(:app AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid),"
                            + " 'accepted', 0, now(), now(), 1, 1, 2, 0, false)")
                    .bind("app", applicationId).bind("task", taskId).bind("rec", recommender)
                    .then().block();
        }
    }

    private void markPublished(String taskId) {
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
    }

    @SuppressWarnings("unchecked")
    private String publishTaskWithDeadline(String merchant, String org, Instant applicationDeadline) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "截止任务");
        b.put("applicationDeadline", applicationDeadline.toString());
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        markPublished(taskId);
        return taskId;
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
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        markPublished(taskId);
        return taskId;
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
