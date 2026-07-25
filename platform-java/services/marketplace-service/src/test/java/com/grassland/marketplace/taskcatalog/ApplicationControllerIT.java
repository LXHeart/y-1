package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

        when(financeClient.reserve(org, app, 500L)).thenReturn(Mono.just(ReserveResult.reserved(500L)));

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

        when(financeClient.reserve(anyString(), anyString(), anyLong()))
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
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(UUID.randomUUID().toString(), task);

        when(financeClient.reserve(org, app, 500L)).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(financeClient.capture(org, app)).thenReturn(Mono.empty());

        // 4F accept → 202 → 轮询 accepted（reserve 成功）
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

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
        String task = publishTaskBounty(merchant, org, null, 500L);
        String app = apply(UUID.randomUUID().toString(), task);
        when(financeClient.reserve(org, app, 500L)).thenReturn(Mono.just(ReserveResult.reserved(500L)));
        when(disputeChecker.hasOpenDispute(anyString(), anyString())).thenReturn(true);  // 开争议 → held

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitReservation(merchant, task, app, "accepted");

        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        awaitSettlement(merchant, task, app, "held");  // 窗口到期查到争议 → held（不 capture）
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
    void nonOwnerListForbidden() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String task = publishTask(merchant, org, null);
        client().get().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isForbidden();
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

    // ---------- helpers ----------

    private void accept(String merchant, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk();
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
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
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
