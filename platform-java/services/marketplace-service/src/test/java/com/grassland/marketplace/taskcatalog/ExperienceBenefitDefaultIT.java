package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.benefit.ExperienceBenefit;
import com.grassland.marketplace.benefit.ExperienceBenefitRepository;
import com.grassland.marketplace.benefit.ExperienceBenefitService;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.time.Instant;
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
 * 任务书 #96 C96-03 体验权益单与失约 IT（TC96-011/012/013/014 + 解耦展示）。
 *
 * <p>bean 覆盖集合与 FreebieEscrowFlowIT 同族（真 Saga + temporal test-server，仅 finance 出站 mock）。
 * 失约派发器在基座关闭——成立直接驱动 {@link ExperienceBenefitService#establishDefault} seam；
 * 回应窗用 IT 配置 1 小时，截止回拨用 SQL 后门。
 */
@SuppressWarnings("unchecked")
class ExperienceBenefitDefaultIT extends MarketplaceItSupport {

    private static final String H = "X-Grassland-Identity";
    private static final long DEPOSIT = 100L;

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
    private ExperienceBenefitRepository benefitRepo;

    @Autowired
    private ExperienceBenefitService benefitService;

    // ---------- TC96-011：失约主张 → 限时回应（否认）与到期自动成立 ----------

    @Test
    void defaultClaimDenyAndAutoEstablishPaths() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        // 否认路径：主张后商家限时回应 → 继续履约，成立守卫不再触发
        String recDenied = UUID.randomUUID().toString();
        String taskDenied = publishFreebieTask(merchant, org);
        String appDenied = applyAcceptFreebie(merchant, org, taskDenied, recDenied);
        bookBenefit(taskDenied, appDenied, recDenied);
        claimDefault(taskDenied, appDenied, recDenied);
        Map<String, Object> view = benefitView(taskDenied, appDenied, merchant, org);
        Map<String, Object> benefitBody = (Map<String, Object>) view.get("benefit");
        assertThat(benefitBody.get("defaultDeadlineAt")).isNotNull();

        Map<String, Object> resp = client().post()
                .uri("/api/tasks/" + taskDenied + "/applications/" + appDenied + "/benefit")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "respond_default"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        assertThat(((Map<String, Object>) resp.get("data")).get("defaultResolution")).isEqualTo("denied");
        ExperienceBenefit denied = benefitRepo.findByApplication(appDenied).block();
        assertThat(denied.status()).isEqualTo("booked");
        assertThat(denied.defaultResolvedAt()).isNotNull();
        // 成立守卫：已决 → 空
        assertThat(benefitService.establishDefault(appDenied).block()).isNull();
        assertThat(outboxCountForApp("BenefitDefaultEstablished", appDenied)).isZero();

        // 自动成立路径：到期未回应 → merchant_defaulted（单边胜出，重放幂等）
        String rec = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org);
        String appId = applyAcceptFreebie(merchant, org, task, rec);
        bookBenefit(task, appId, rec);
        claimDefault(task, appId, rec);
        backdateDefaultDeadline(appId);
        ExperienceBenefit established = benefitService.establishDefault(appId).block();
        assertThat(established.status()).isEqualTo("merchant_defaulted");
        assertThat(established.defaultResolution()).isEqualTo("defaulted");
        assertThat(benefitService.establishDefault(appId).block()).isNull();
        assertThat(outboxCountForApp("BenefitDefaultEstablished", appId)).isEqualTo(1);
        assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");

        // 重复主张 / 非 booked 主张 → 409
        claimDefaultExpect(task, appId, rec, 409);
    }

    // ---------- TC96-012：成立后交付截止顺延 = 回应窗，暂停区间落权益行可查 ----------

    @Test
    void establishmentExtendsDeliveryDeadlinesByResponseWindow() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org);
        String appId = applyAcceptFreebie(merchant, org, task, rec);

        TaskApplication before = applicationRepo.findById(appId).block();
        Instant deliveryBefore = before.deliveryDeadlineAt();
        Instant remedyBefore = before.remedyDeadlineAt();
        assertThat(before.underDeliveryPolicy()).isTrue();  // 体验任务同样受期限规则约束

        bookBenefit(task, appId, rec);
        claimDefault(task, appId, rec);
        backdateDefaultDeadline(appId);
        benefitService.establishDefault(appId).block();

        TaskApplication after = applicationRepo.findById(appId).block();
        assertThat(after.deliveryDeadlineAt()).isEqualTo(deliveryBefore.plusSeconds(3600));
        assertThat(after.remedyDeadlineAt()).isEqualTo(remedyBefore.plusSeconds(3600));
        // 暂停区间可查：主张 → 回应窗截止 → 成立时刻 三点齐备（顺延量 = 窗长，可对账）
        ExperienceBenefit benefit = benefitRepo.findByApplication(appId).block();
        assertThat(benefit.defaultResolution()).isEqualTo("defaulted");
        assertThat(benefit.defaultResolvedAt()).isAfterOrEqualTo(benefit.defaultDeadlineAt());
    }

    // ---------- TC96-013：成立 → 押金全退路径 ----------

    @Test
    void establishedDefaultRefundsDepositToRecommender() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org);
        String appId = applyAcceptFreebie(merchant, org, task, rec);
        bookBenefit(task, appId, rec);
        claimDefault(task, appId, rec);
        backdateDefaultDeadline(appId);
        benefitService.establishDefault(appId).block();
        lenient().when(financeClient.freebieRefund(anyString(), anyString())).thenReturn(Mono.empty());

        // 押金全退腿：派发器重放路径幂等（freebieRefund 404/409 视作成功）
        benefitService.refundDefaultedDeposit(applicationRepo.findById(appId).block()).block();
        verify(financeClient).freebieRefund(org, appId);
    }

    // ---------- TC96-014：未消费退出 vs 已消费不履约分开 ----------

    @Test
    void unconsumedCanExitNoFaultButConsumedCannot() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org);
        String appId = applyAcceptFreebie(merchant, org, task, rec);
        bookBenefit(task, appId, rec);
        lenient().when(financeClient.freebieRefund(anyString(), anyString())).thenReturn(Mono.empty());

        // 未消费：无责退出可走（押金原路退）
        client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
                .header(H, sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "no_fault"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("withdrawn");
        verify(financeClient).freebieRefund(org, appId);

        // 已消费：兑现后不得无责退出（不履约走协商/争议或超时终结，押金不随退出带走）
        String rec2 = UUID.randomUUID().toString();
        String task2 = publishFreebieTask(merchant, org);
        String app2 = applyAcceptFreebie(merchant, org, task2, rec2);
        bookBenefit(task2, app2, rec2);
        client().post().uri("/api/tasks/" + task2 + "/applications/" + app2 + "/benefit")
                .header(H, sign(rec2, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "fulfill"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("fulfilled");
        client().post().uri("/api/tasks/" + task2 + "/applications/" + app2 + "/exit")
                .header(H, sign(rec2, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "no_fault"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(applicationRepo.findById(app2).block().status()).isEqualTo("accepted");
    }

    // ---------- 解耦展示：GET /benefit 权益单与押金快照分开携带 ----------

    @Test
    void benefitViewIsDecoupledFromDepositField() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishFreebieTask(merchant, org);
        String appId = applyAcceptFreebie(merchant, org, task, rec);

        Map<String, Object> empty = benefitView(task, appId, rec, org);
        assertThat(empty.get("benefit")).isNull();
        assertThat(((Number) empty.get("freebieDepositCents")).longValue()).isEqualTo(DEPOSIT);

        bookBenefit(task, appId, rec);
        Map<String, Object> booked = benefitView(task, appId, rec, org);
        Map<String, Object> benefitBody = (Map<String, Object>) booked.get("benefit");
        assertThat(benefitBody.get("status")).isEqualTo("booked");
        assertThat(benefitBody.get("items").toString()).contains("到店体验");
        assertThat(((Number) booked.get("freebieDepositCents")).longValue()).isEqualTo(DEPOSIT);
    }

    // ---------- 造数 helper ----------

    private String publishFreebieTask(String merchant, String org) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("organizationId", org, "title", "体验任务", "platform", "xiaohongshu", "storeId",
                        UUID.randomUUID().toString(), "applicationDeadline",
                        Instant.now().plusSeconds(3600).toString(), "freebieDepositCents", DEPOSIT))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
        db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
                + "WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
        return stripStoreScope(taskId);
    }

    /** 押金型任务为资金型 accept（202 + Saga），桩押金预留腿并轮询至 accepted。 */
    private String applyAcceptFreebie(String merchant, String org, String taskId, String rec) {
        Map<String, Object> applied = client().post().uri("/api/tasks/" + taskId + "/applications")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "想体验"))
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
        when(financeClient.freebieReserve(eq(org), eq(appId), eq(DEPOSIT), eq(rec), eq(merchant)))
                .thenReturn(Mono.just(ReserveResult.reserved(DEPOSIT)));
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isEqualTo(202);
        long deadline = System.currentTimeMillis() + 10_000L;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = applicationRepo.findById(appId).block().status();
            if ("accepted".equals(status)) {
                return appId;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("freebie accept did not reach accepted (last=" + status + ")");
    }

    private void bookBenefit(String taskId, String appId, String rec) {
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/benefit")
                .header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "book", "items", List.of("到店体验"),
                        "bookingWindow", Instant.now().plusSeconds(86400).toString()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("booked");
    }

    private void claimDefault(String taskId, String appId, String rec) {
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/benefit/default-claim")
                .header(H, sign(rec, "recommender"))
                .exchange().expectStatus().isOk();
    }

    private void claimDefaultExpect(String taskId, String appId, String rec, int status) {
        client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/benefit/default-claim")
                .header(H, sign(rec, "recommender"))
                .exchange().expectStatus().isEqualTo(status);
    }

    private Map<String, Object> benefitView(String taskId, String appId, String caller, String org) {
        Map<String, Object> resp = client()
                .get().uri("/api/tasks/" + taskId + "/applications/" + appId + "/benefit")
                .header(H, caller.equals(merchantOf(taskId)) ? sign(caller, "merchant", org, "finance_transaction")
                        : sign(caller, "recommender"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) resp.get("data");
    }

    private String merchantOf(String taskId) {
        return db.sql("SELECT owner_account_id::text AS o FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).map(r -> r.get("o", String.class)).one().block();
    }

    private void backdateDefaultDeadline(String appId) {
        db.sql("UPDATE experience_benefit SET default_deadline_at = now() - interval '1 second'"
                        + " WHERE application_id = CAST(:id AS uuid)")
                .bind("id", appId).then().block();
    }

    private long outboxCountForApp(String eventType, String appId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
                        + "WHERE event_type = :et AND payload->>'applicationId' = :app")
                .bind("et", eventType).bind("app", appId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }
}
