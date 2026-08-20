package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 霸王餐押金任务全链 IT（任务书 #22 Stage B2 / ADR-D12）：发布（XOR + funding 闸门）→ apply → accept
 * （Saga 走 freebieReserve 扣推荐官押金）→ 结算成功路径（freebieRefund 退推荐官）→ 商家取消（押金退推荐官，
 * D6 关键差异行——与 bounty 行为相反）→ 修订押金不影响已接受履约（D7 pinning）。真 Saga + 真 DB + temporal
 * test-server，仅 finance 出站被 mock（镜像 ApplicationControllerIT）。
 */
@SuppressWarnings("unchecked")
class FreebieEscrowFlowIT extends MarketplaceItSupport {

    private static final String H = "X-Grassland-Identity";

    @MockitoBean
    private FinanceEscrowClient financeClient;

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

    // 与 ApplicationControllerIT 完全一致的 bean 覆盖集合（含以下 spy）——Spring 测试上下文缓存按
    // 覆盖组合区分，集合不同会新建上下文（多一整个连接池，曾把 testcontainer 的 max_connections 打爆）。
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

    @Autowired
    private TaskApplicationRepository applicationRepo;

    // ---------- 发布契约（XOR + funding 闸门） ----------

    // ---------- 任务书 #46 组合模式：bounty + deposit 可同设 ----------

    @Test
    void combinedBountyAndDepositPublishes() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header(H, sign(UUID.randomUUID().toString(), "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "组合资金任务",
                        "bountyCents", 500L, "freebieDepositCents", 100L))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.bountyCents").isEqualTo(500L)
                .jsonPath("$.data.freebieDepositCents").isEqualTo(100L);
    }

    @Test
    void ladderWithDepositStillRejected() {
        // 任务书 #46 D2：XOR 收窄为阶梯 × 押金（D-02 阶梯只对 bounty 腿定义）
        String org = UUID.randomUUID().toString();
        Map<String, Object> ladder = Map.of("policyVersion", "ladder-v1", "metricKey", "likes",
                "tiers", List.of(Map.of("threshold", 100, "payoutCents", 5000L)));
        client().post().uri("/api/tasks")
                .header(H, sign(UUID.randomUUID().toString(), "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "阶梯押金任务",
                        "bountyCents", 500L, "freebieDepositCents", 100L,
                        "requirements", Map.of("commissionLadder", ladder)))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("阶梯佣金不能与霸王餐押金同时启用");
    }

    @Test
    void combinedAcceptReservesBothLegs() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishCombinedTask(merchant, org, 500L, 100L);
        String app = apply(recommender, task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), eq(recommender)))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 任务书 #46 D3：两腿顺序预留（金额取自 task_application 冻结快照，非 Saga 单值）
        verify(financeClient).reserve(org, app, 500L, recommender);
        verify(financeClient).freebieReserve(org, app, 100L, recommender, merchant);
        TaskApplication accepted = applicationRepo.findById(app).block();
        assertThat(accepted.bountyCents()).isEqualTo(500L);
        assertThat(accepted.freebieDepositCents()).isEqualTo(100L);
    }

    @Test
    void combinedDepositLegFailureRollsBackBountyLeg() {
        // D3：后腿余额不足 → 就地回滚已成功 bounty 腿，回 insufficient，无部分预留
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishCombinedTask(merchant, org, 500L, 100L);
        String app = apply(recommender, task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), eq(recommender)))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.release(org, app)).thenReturn(Mono.empty());
        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.insufficientFunds()));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "compensated");

        verify(financeClient).release(org, app);
        verify(financeClient, never()).freebieRefund(org, app);
        assertThat(appStatus(app)).isEqualTo("pending");
    }

    @Test
    void combinedConfirmRefundsDepositAndCapturesBounty() {
        // D4：结算双腿——押金退推荐官 + 赏金 capture
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishCombinedTask(merchant, org, 500L, 100L);
        String app = apply(recommender, task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), eq(recommender)))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));
        when(financeClient.freebieRefund(org, app)).thenReturn(Mono.empty());
        when(financeClient.capture(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        submit(recommender, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitSettlement(merchant, task, app, "settled");

        verify(financeClient, timeout(3_000)).freebieRefund(org, app);
        verify(financeClient, timeout(3_000)).capture(org, app);
        assertThat(outboxCountForApp("EngagementSettled", app)).isEqualTo(1);
    }

    @Test
    void combinedCancelRefundsBothLegsWithDirectionBoth() {
        // D5：商家取消两腿都退，refundDirection=both
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishCombinedTask(merchant, org, 500L, 100L);
        String app = apply(recommender, task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), eq(recommender)))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));
        when(financeClient.freebieRefund(org, app)).thenReturn(Mono.empty());
        when(financeClient.release(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        client().post().uri("/api/tasks/" + task + "/cancel")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", taskVersion(task)))
                .exchange().expectStatus().isOk();

        verify(financeClient, timeout(3_000)).freebieRefund(org, app);
        verify(financeClient, timeout(3_000)).release(org, app);
        assertThat(appStatus(app)).isEqualTo("refunded");
        assertThat(outboxPayloadFieldForApp("EngagementRefundedOnCancel", app, "refundDirection"))
                .isEqualTo("both");
    }

    @Test
    void freebiePublishRequiresFinanceTransactionTier() {
        // ADR-D12 D5：押金任务涉及托管与商家收款，与 bounty 同一 funding 闸门（basic_publish maxTx=0 → 403）。
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "押金任务", "freebieDepositCents", 100L))
                .exchange().expectStatus().isForbidden();

        // finance_transaction tier → 201，响应带 freebieDepositCents
        client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "押金任务", "freebieDepositCents", 100L))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.freebieDepositCents").isEqualTo(100L);
    }

    // ---------- accept Saga：押金从推荐官钱包预付 ----------

    @Test
    void acceptReservesDepositFromRecommenderWallet() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org, 100L);
        String app = apply(recommender, task);

        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        verify(financeClient).freebieReserve(org, app, 100L, recommender, merchant);
        verify(financeClient, never()).reserve(org, app, 100L, recommender);
        TaskApplication accepted = applicationRepo.findById(app).block();
        assertThat(accepted.freebieDepositCents()).as("押金快照冻结").isEqualTo(100L);
        assertThat(taskContextSnapshot(app)).contains("\"freebieDepositCents\": 100");
    }

    @Test
    void insufficientWalletCompensatesBackToPendingAndNotifiesMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org, 100L);
        String app = apply(UUID.randomUUID().toString(), task);

        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), anyString(), anyString()))
                .thenReturn(Mono.just(ReserveResult.insufficientFunds()));

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "compensated");

        assertThat(appStatus(app)).isEqualTo("pending");
        // 无部分扣款：押金从未成功托管，无退款调用
        verify(financeClient, never()).freebieRefund(org, app);
        // 商家通知（ADR-D12 验收 #2）：ApplicationReservationFailed 带 taskOwnerId
        String owner = outboxPayloadFieldForApp("ApplicationReservationFailed", app, "taskOwnerId");
        assertThat(owner).isEqualTo(merchant);
    }

    // ---------- 结算成功路径：押金全额退推荐官（D6 第一行） ----------

    @Test
    void confirmSettlesWithDepositRefundedToRecommender() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org, 100L);
        String app = apply(recommender, task);

        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));
        when(financeClient.freebieRefund(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        submit(recommender, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitSettlement(merchant, task, app, "settled");

        // D9：结算唯一钱侧入口按资金来源分支——freebie 退推荐官，绝不走 capture
        verify(financeClient, timeout(3_000)).freebieRefund(org, app);
        verify(financeClient, never()).capture(org, app);
        assertThat(outboxCountForApp("EngagementSettled", app)).isEqualTo(1);
    }

    // ---------- 商家取消：押金退推荐官（D6 关键差异行，与 bounty 相反） ----------

    @Test
    void merchantCancelRefundsDepositToRecommenderNotMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org, 100L);
        String app = apply(recommender, task);

        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));
        when(financeClient.freebieRefund(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        client().post().uri("/api/tasks/" + task + "/cancel")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", taskVersion(task)))
                .exchange().expectStatus().isOk();

        verify(financeClient, timeout(3_000)).freebieRefund(org, app);
        verify(financeClient, never()).release(org, app);
        assertThat(appStatus(app)).isEqualTo("refunded");
        // 违约信号仍在（trust 消费），但资金方向标记为推荐官
        assertThat(outboxPayloadFieldForApp("EngagementRefundedOnCancel", app, "refundDirection"))
                .isEqualTo("recommender");
    }

    /** 对照行：bounty 任务取消仍退商家（既有行为零改动）。 */
    @Test
    void merchantCancelBountyStillReleasesToMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishBountyTask(merchant, org, 500L);
        String app = apply(UUID.randomUUID().toString(), task);

        when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString()))
                .thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.release(org, app)).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        client().post().uri("/api/tasks/" + task + "/cancel")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", taskVersion(task)))
                .exchange().expectStatus().isOk();

        verify(financeClient, timeout(3_000)).release(org, app);
        verify(financeClient, never()).freebieRefund(org, app);
        assertThat(outboxPayloadFieldForApp("EngagementRefundedOnCancel", app, "refundDirection"))
                .isEqualTo("merchant");
    }

    // ---------- D7：修订押金只影响新报名 ----------

    @Test
    void depositRevisionPinsAcceptedEngagement() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org, 100L);
        String app = apply(recommender, task);

        when(financeClient.freebieReserve(eq(org), eq(app), eq(100L), eq(recommender), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(100L)));
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        // 修订押金 100 → 200
        int version = taskVersion(task);
        client().post().uri("/api/tasks/" + task + "/revise")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", version, "title", "押金任务", "freebieDepositCents", 200L))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.freebieDepositCents").isEqualTo(200L);

        // 已接受履约仍按 100 冻结；新报名冻结 200
        assertThat(applicationRepo.findById(app).block().freebieDepositCents()).isEqualTo(100L);
        String newApp = apply(UUID.randomUUID().toString(), task);
        assertThat(applicationRepo.findById(newApp).block().freebieDepositCents()).isEqualTo(200L);
    }

    // ---------- helpers ----------

    private String publishCombinedTask(String merchant, String org, long bountyCents, long depositCents) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "组合资金任务",
                        "bountyCents", bountyCents, "freebieDepositCents", depositCents))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
        return taskId;
    }

    private String publishFreebieTask(String merchant, String org, long depositCents) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "押金任务", "freebieDepositCents", depositCents))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
        return taskId;
    }

    private String publishBountyTask(String merchant, String org, long bountyCents) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "赏金任务", "bountyCents", bountyCents))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                        + "WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).then().block();
        return taskId;
    }

    private String apply(String recommender, String task) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请"))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private void submit(String recommender, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header(H, sign(recommender, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", "https://example.com/post/" + app, "note", "已到店体验"))
                .exchange().expectStatus().isCreated();
    }

    private void awaitReservation(String merchant, String task, String app, String expected) {
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = reservationStatus(merchant, task, app);
            if (expected.equals(status)) {
                return;
            }
            sleep(100L);
        }
        throw new AssertionError("reservation did not reach " + expected + " (last=" + status + ")");
    }

    private void awaitSettlement(String merchant, String task, String app, String expected) {
        long deadline = System.currentTimeMillis() + 30_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = settlementStatus(merchant, task, app);
            if (expected.equals(status)) {
                return;
            }
            sleep(300L);
        }
        throw new AssertionError("settlement did not reach " + expected + " (last=" + status + ")");
    }

    private String reservationStatus(String merchant, String task, String app) {
        Map<String, Object> resp = client().get()
                .uri("/api/tasks/" + task + "/applications/" + app + "/reservation")
                .header(H, sign(merchant, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("status");
    }

    private String settlementStatus(String merchant, String task, String app) {
        Map<String, Object> resp = client().get()
                .uri("/api/tasks/" + task + "/applications/" + app + "/settlement")
                .header(H, sign(merchant, "merchant"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("status");
    }

    private String appStatus(String app) {
        return db.sql("SELECT status FROM task_application WHERE id = CAST(:id AS uuid)")
                .bind("id", app)
                .map(r -> r.get("status", String.class)).one().block();
    }

    private int taskVersion(String task) {
        return db.sql("SELECT version FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", task)
                .map(r -> r.get("version", Integer.class)).one().block();
    }

    private String taskContextSnapshot(String app) {
        return db.sql("SELECT task_context_snapshot::text AS s FROM task_application WHERE id = CAST(:id AS uuid)")
                .bind("id", app)
                .map(r -> r.get("s", String.class)).one().block();
    }

    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId)
                .map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private String outboxPayloadFieldForApp(String eventType, String appId, String field) {
        List<String> values = db.sql("SELECT payload->>'" + field + "' AS v FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId)
                .map(r -> r.get("v", String.class)).all().collectList().block();
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
