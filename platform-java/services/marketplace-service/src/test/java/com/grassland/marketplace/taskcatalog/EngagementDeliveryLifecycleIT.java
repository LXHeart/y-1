package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.DeliveryDeadlineInput;
import com.grassland.marketplace.workflow.saga.DeliveryLifecycleActivity;
import com.grassland.marketplace.workflow.saga.DeliveryOutcome;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #96 C96-01 交付期限与退出/延期 IT（TC96-001/003/004/005 + D96-07 存量豁免）。
 *
 * <p>真 DB + 真 temporal test-server；仅 finance 出站 mock（镜像 ApplicationControllerIT 的 bean 覆盖集合，
 * 复用其 Spring 上下文缓存）。看门狗派发器在基座已关——提醒/终结直接驱动
 * {@link DeliveryLifecycleActivity} seam（照 confirmation/contest 派发器测试惯例），
 * 补救窗到期用 SQL 回拨 deadline（同 publishTaskWithDeadline 的测试后门手法）。
 */
@SuppressWarnings("unchecked")
class EngagementDeliveryLifecycleIT extends MarketplaceItSupport {

    private static final String H = "X-Grassland-Identity";

    // 与 ApplicationControllerIT 完全一致的 bean 覆盖集合——Spring 上下文缓存按覆盖组合区分，
    // 集合不同会新建上下文（多一整个连接池，曾打爆 testcontainer 的 max_connections）。
    @MockitoBean
    private FinanceEscrowClient financeClient;

    @MockitoBean
    private DisputeChecker disputeChecker;

    @MockitoBean
    private IntelligenceMediaClient mediaClient;

    @MockitoBean
    private TrustDisputeClient trustDisputeClient;

    @MockitoBean
    private com.grassland.marketplace.taskcatalog.LinkReachabilityChecker linkChecker;

    @MockitoBean
    private IntelligenceVerificationClient verificationClient;

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
    private DeliveryLifecycleActivity deliveryActivity;

    @Autowired
    private EngagementExtensionRepository extensionRepo;

    @Autowired
    private SubmissionRepository submissionRepo;

    // ---------- TC96-001：接单快照期限 + 提醒-补救-终结链 ----------

    @Test
    void acceptSnapshotsDeliveryContractThenReminderAndTimeoutTerminateChain() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);

        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.underDeliveryPolicy()).isTrue();
        assertThat(app.engagementPolicyVersion()).isEqualTo(EngagementDeliveryPolicy.POLICY_VERSION);
        assertThat(app.deliveryDeadlineAt()).isAfter(Instant.now().plusSeconds(3600));
        assertThat(app.remedyDeadlineAt()).isAfter(app.deliveryDeadlineAt());
        // 契约可见（§8 发布预览/待办后续卡消费同一读模型）
        assertThat(app.exitedAt()).isNull();
        assertThat(app.exitKind()).isNull();

        // 派发扫描可见（先排干共享库里其他用例的未派发行，隔离 LIMIT/排序噪声）+ guarded 标记幂等
        drainDispatchScan(appId);
        assertThat(applicationRepo.findDeliveryDispatchable(10).collectList().block())
                .anyMatch(row -> row.id().equals(appId));
        assertThat(applicationRepo.markDeliveryDispatched(appId).block()).isTrue();
        assertThat(applicationRepo.markDeliveryDispatched(appId).block()).isFalse();

        // 临到期提醒：确定性 eventId，重复驱动不重复发
        deliveryActivity.notifyDeliveryExpiring(input(appId, task, org));
        deliveryActivity.notifyDeliveryExpiring(input(appId, task, org));
        assertThat(outboxCountForApp("DeliveryDeadlineExpiring", appId)).isEqualTo(1);

        // 未到补救窗终结线 → 守卫 abort（提醒后误触发/旧 workflow 迟到都无害）
        DeliveryOutcome early = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(early.status()).isEqualTo("aborted");
        assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");

        // 补救窗到期（SQL 回拨终结线）→ 有责终结：refunded + exit_kind=timeout + 事件 + 名额回收
        backdateRemedy(appId);
        DeliveryOutcome outcome = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(outcome.status()).isEqualTo("terminated");
        TaskApplication terminated = applicationRepo.findById(appId).block();
        assertThat(terminated.status()).isEqualTo("refunded");
        assertThat(terminated.exitKind()).isEqualTo("timeout");
        assertThat(terminated.exitedAt()).isNotNull();
        assertThat(outboxCountForApp("DeliveryTimeoutTerminated", appId)).isEqualTo(1);
        assertThat(occupiedSlots(task)).isZero();

        // 重试幂等：已终结再驱动 → abort，事件不重复
        DeliveryOutcome retried = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(retried.status()).isEqualTo("aborted");
        assertThat(outboxCountForApp("DeliveryTimeoutTerminated", appId)).isEqualTo(1);
        // 终结后提醒静默
        deliveryActivity.notifyDeliveryExpiring(input(appId, task, org));
        assertThat(outboxCountForApp("DeliveryDeadlineExpiring", appId)).isEqualTo(1);
    }

    /** TC96-001 补救窗内交付即豁免：提交后旧 Timer 到点必须 abort（不得误杀已交付履约）。 */
    @Test
    void submissionInsideRemedyWindowExemptsFromTimeoutTermination() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);

        backdateRemedy(appId);
        assertThat(submissionRepo.create(appId, rec, "https://www.xiaohongshu.com/p/abc", null).block()).isNotNull();

        DeliveryOutcome outcome = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(outcome.status()).isEqualTo("aborted");
        TaskApplication app = applicationRepo.findById(appId).block();
        assertThat(app.status()).isEqualTo("accepted");
        assertThat(app.exitedAt()).isNull();
    }

    // ---------- TC96-003：无责退出（终态 withdrawn，不进完成率分母） ----------

    @Test
    void noFaultExitLandsWithdrawnAndStaysOutOfCompletionDenominator() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        // 一单已完成（confirmed）垫底，保证退出前完成率 1.0
        String doneTask = publishTask(merchant, org);
        String doneApp = applyAndAccept(merchant, org, doneTask, rec);
        applicationRepo.confirm(doneApp, doneTask).block();

        var before = reputationService.snapshot(rec).block().stats();
        assertThat(before.completionRate()).isEqualTo(1.0d);
        assertThat(before.acceptedCount()).isEqualTo(1);

        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        // SQL 后门注入资金快照：验证退出释放赏金腿（真 Saga 造资金型需整套 Saga 桩，此处按冻结列直验）
        db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());

        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
                .header(H, sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "no_fault", "reason", "档期冲突"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("withdrawn")
                .jsonPath("$.data.exitKind").isEqualTo("no_fault");
        verify(financeClient).release(org, appId);

        TaskApplication exited = applicationRepo.findById(appId).block();
        assertThat(exited.exitedAt()).isNotNull();
        assertThat(occupiedSlots(task)).isZero();
        assertThat(outboxCountForApp("ApplicationExitedNoFault", appId)).isEqualTo(1);

        // 完成率口径（TC96-003）：退出计入 withdrawn，不进分母、不误记商家取消
        var stats = reputationService.snapshot(rec).block().stats();
        assertThat(stats.withdrawnCount()).isEqualTo(1);
        assertThat(stats.acceptedCount()).isEqualTo(before.acceptedCount());
        assertThat(stats.merchantCancelledCount()).isEqualTo(0);
        assertThat(stats.completionRate()).isEqualTo(1.0d);

        // 重复退出 / 已退出再确认 → 409
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 无责退出前置：已有提交 → 409（走协商/争议，C96-02 开放）。 */
    @Test
    void noFaultExitRejectedOnceSubmissionExists() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        submissionRepo.create(appId, rec, "https://www.xiaohongshu.com/p/xyz", null).block();

        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
                .header(H, sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "no_fault"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");
    }

    /** D96-07 存量豁免：政策版本外的 accepted 行不可无责退出、不可被看门狗扫描。 */
    @Test
    void legacyApplicationWithoutPolicyVersionIsExempt() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        db.sql("UPDATE task_application SET engagement_policy_version = NULL, delivery_deadline_at = NULL,"
                        + " remedy_deadline_at = NULL WHERE id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();

        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
                .header(H, sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "no_fault"))
                .exchange().expectStatus().isEqualTo(409);
        // 存量行不再进入看门狗扫描（本用例行不可见；他用例行不受影响——断言按 id 作用域）
        assertThat(applicationRepo.findDeliveryDispatchable(50).collectList().block())
                .noneMatch(row -> row.id().equals(appId));
    }

    // ---------- TC96-004：终结 / 商家取消并发单边胜出 ----------

    @Test
    void timeoutTerminationAndMerchantCancelAreSingleWinner() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recA = UUID.randomUUID().toString();
        String recB = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appA = applyAndAccept(merchant, org, task, recA);
        String appB = applyAndAccept(merchant, org, task, recB);

        // A：超时终结先落定
        backdateRemedy(appA);
        assertThat(deliveryActivity.terminateDeliveryTimeout(input(appA, task, org)).status())
                .isEqualTo("terminated");
        // 商家取消 sweep：A 已终态不再重退；B（accepted 未提交）走原语义全额退
        lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.refundedCount").isEqualTo(1);

        TaskApplication a = applicationRepo.findById(appA).block();
        assertThat(a.status()).isEqualTo("refunded");
        assertThat(a.exitKind()).isEqualTo("timeout");
        TaskApplication b = applicationRepo.findById(appB).block();
        assertThat(b.status()).isEqualTo("refunded");
        assertThat(b.exitKind()).isNull();  // 商家取消路径，非超时终结

        // B 已被取消收口 → 迟到看门狗 abort（同一报名最多一种终结）
        assertThat(deliveryActivity.terminateDeliveryTimeout(input(appB, task, org)).status())
                .isEqualTo("aborted");
        assertThat(outboxCountForApp("DeliveryTimeoutTerminated", appB)).isZero();
    }

    // ---------- TC96-005：延期申请 / 批准 / 拒绝 ----------

    @Test
    void extensionRequestApproveRejectLifecycle() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);
        Instant deadlineBefore = applicationRepo.findById(appId).block().deliveryDeadlineAt();
        Instant remedyBefore = applicationRepo.findById(appId).block().remedyDeadlineAt();

        // 申请：days 缺失/非正 → 400
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reason", "n/a"))
                .exchange().expectStatus().isBadRequest();
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("days", 0))
                .exchange().expectStatus().isBadRequest();

        // 正常申请 → pending；重复申请 → 409
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("days", 3, "reason", "素材未就绪"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.days").isEqualTo(3);
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("days", 5))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(outboxCountForApp("DeliveryExtensionRequested", appId)).isEqualTo(1);
        assertThat(applicationRepo.findById(appId).block().deliveryDeadlineAt()).isEqualTo(deadlineBefore);

        // 批准：deadline/补救线整体后移 + 派发标记清空 + 事件
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("decision", "approve"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("approved");
        TaskApplication extended = applicationRepo.findById(appId).block();
        assertThat(extended.deliveryDeadlineAt()).isEqualTo(deadlineBefore.plusSeconds(3 * 86400L));
        assertThat(extended.remedyDeadlineAt()).isEqualTo(remedyBefore.plusSeconds(3 * 86400L));
        assertThat(outboxCountForApp("DeliveryExtensionApproved", appId)).isEqualTo(1);
        // 重复决定 → 409（无待审）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/extend")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("decision", "approve"))
                .exchange().expectStatus().isEqualTo(409);

        // 拒绝：截止不动
        String recR = UUID.randomUUID().toString();
        String appR2 = applyAndAccept(merchant, org, task, recR);
        Instant deadlineR = applicationRepo.findById(appR2).block().deliveryDeadlineAt();
        client().post().uri("/api/tasks/" + task + "/applications/" + appR2 + "/extend")
                .header(H, sign(recR, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("days", 2))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/tasks/" + task + "/applications/" + appR2 + "/extend")
                .header(H, sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("decision", "reject"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("rejected");
        assertThat(applicationRepo.findById(appR2).block().deliveryDeadlineAt()).isEqualTo(deadlineR);
        assertThat(outboxCountForApp("DeliveryExtensionRejected", appR2)).isEqualTo(1);
    }

    /** 延期批准后旧终结线失效：终结守卫见 remedy 在未来 → abort（延期 vs 终结单边胜出）。 */
    @Test
    void terminationGuardAbortsWhenExtensionMovedDeadlineForward() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String appId = applyAndAccept(merchant, org, task, rec);

        extensionRepo.createPending(appId, rec, 3, null).block();
        extensionRepo.decide(appId, true, merchant).block();
        applicationRepo.extendDeliveryDeadline(appId, task, 3 * 86400L).block();

        DeliveryOutcome outcome = deliveryActivity.terminateDeliveryTimeout(input(appId, task, org));
        assertThat(outcome.status()).isEqualTo("aborted");
        assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");
    }

    // ---------- 造数 helper ----------

    private String publishTask(String merchant, String org) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("organizationId", org);
        b.put("title", "交付期限任务");
        b.put("platform", "xiaohongshu");
        b.put("storeId", UUID.randomUUID().toString());
        b.put("applicationDeadline", Instant.now().plusSeconds(3600).toString());
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

    private void backdateRemedy(String appId) {
        db.sql("UPDATE task_application SET remedy_deadline_at = now() - interval '1 second',"
                        + " delivery_deadline_at = now() - interval '121 second'"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();
    }

    private int occupiedSlots(String taskId) {
        return db.sql("SELECT COALESCE(counter.occupied_slots, 0)::int AS s FROM task t"
                        + " LEFT JOIN task_acceptance_counter counter ON counter.task_id = t.id"
                        + " WHERE t.id = CAST(:id AS uuid)")
                .bind("id", taskId).map(r -> r.get("s", Integer.class)).one().block();
    }

    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private DeliveryDeadlineInput input(String appId, String taskId, String org) {
        return new DeliveryDeadlineInput(appId, taskId, org, 0, 0, 0);
    }

    /** 把共享库里当前可派发的行（除指定行外）全部标记掉，让后续对扫描结果的断言只看本用例的新行。 */
    private void drainDispatchScan(String keepAppId) {
        applicationRepo.findDeliveryDispatchable(200).collectList().block().stream()
                .filter(row -> !row.id().equals(keepAppId))
                .forEach(row -> applicationRepo.markDeliveryDispatched(row.id()).block());
    }
}
