package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * {@link SettlementActivityImpl} 单元测试（草场 Epic 5 Slice 5A / HLD 9.2）：Mockito 桩 repo/finance/disputes，
 * 验证窗口到期 captureSettlement 的重验（accepted+confirmed）、争议 seam、capture 映射、幂等。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SettlementActivityImplTest {

    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String RECOMMENDER = "55555555-5555-5555-5555-555555555555";
    private static final String OWNER = "66666666-6666-6666-6666-666666666666";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";

    @Mock private TaskApplicationRepository apps;
    @Mock private TaskRepository tasks;
    @Mock private OutboxRepository outbox;
    @Mock private FinanceEscrowClient finance;
    @Mock private DisputeChecker disputes;
    @Mock private VerificationChecker verification;
    @Mock private OpsCaseRegistrar opsCases;
    @Mock private TransactionalOperator transactions;

    private SettlementActivityImpl activity;
    private SettlementInput input;

    @BeforeEach
    void setUp() {
        // hold 分支才会用到（GL-P1-OPS-001）：事务直通 + 登记处置单，非 hold 用例不会触达，故 lenient。
        lenient().when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(opsCases.register(anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(Mono.empty());
        // D-02：captureOrHold 统一读 accept 冻结快照判阶梯；本测试类只覆盖固定佣金契约。
        lenient().when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.empty());
        // gate+capture 钱侧逻辑已抽到 SettlementExecution（D-03）；用真实实例 + 桩 gates 覆盖 captureOrHold 全分支。
        SettlementExecution settlementExecution =
                new SettlementExecution(outbox, apps, finance, disputes, verification, opsCases, transactions);
        activity = new SettlementActivityImpl(apps, tasks, settlementExecution);
        input = new SettlementInput(APP_ID, TASK_ID, "33333333-3333-3333-3333-333333333333", ORG, 500L, 0L);
    }

    @Test
    void settledWhenAcceptedConfirmedNoDispute() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", Instant.now())));
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task()));
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(false);
        when(finance.capture(ORG, APP_ID)).thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(activity.captureSettlement(input).status()).isEqualTo("settled");
        verify(finance).capture(ORG, APP_ID);

        // Slice 12 Stage 3：EngagementSettled 携带双方账号，供 identity 通知中心解析收件人。
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        Map<String, Object> payload = captor.getValue().payload();
        assertThat(payload.get("taskOwnerId")).isEqualTo(OWNER);
        assertThat(payload.get("recommenderAccountId")).isEqualTo(RECOMMENDER);
    }

    @Test
    void localContestClaimHoldsBeforeRemoteDisputeRead() {
        TaskApplication claimed = new TaskApplication(
                APP_ID, TASK_ID, RECOMMENDER, "accepted", null,
                "33333333-3333-3333-3333-333333333333", null, null, null, Instant.now(), 500L,
                null, null, null, "不同意", null, Instant.now(), null);
        when(apps.findById(APP_ID)).thenReturn(Mono.just(claimed));
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task()));
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome result = activity.captureSettlement(input);

        assertThat(result.status()).isEqualTo("held");
        assertThat(result.reason()).isEqualTo("merchant_contest_requested");
        verify(disputes, never()).hasOpenDispute(anyString(), anyString());
        verify(finance, never()).capture(anyString(), anyString());
    }

    @Test
    void heldWhenDisputeOpen() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", Instant.now())));
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task()));
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(true);
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome r = activity.captureSettlement(input);
        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("open_dispute");
        verify(finance, never()).capture(anyString(), anyString());
    }

    @Test
    void heldWhenVerificationFailed() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", Instant.now())));
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task()));
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(false);
        when(verification.blocksSettlement(ORG, APP_ID)).thenReturn(true);  // failed 核验 → hold
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome r = activity.captureSettlement(input);
        assertThat(r.status()).isEqualTo("held");
        assertThat(r.reason()).isEqualTo("verification_failed");
        verify(finance, never()).capture(anyString(), anyString());
    }

    @Test
    void abortedWhenNotAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending", Instant.now())));
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
        verify(disputes, never()).hasOpenDispute(anyString(), anyString());
    }

    @Test
    void abortedWhenNotConfirmed() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted", null)));  // confirmedAt null
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
        verify(disputes, never()).hasOpenDispute(anyString(), anyString());
    }

    @Test
    void abortedWhenAppMissing() {
        when(apps.findById(APP_ID)).thenReturn(Mono.empty());
        assertThat(activity.captureSettlement(input).status()).isEqualTo("aborted");
    }

    private TaskApplication app(String status, Instant confirmedAt) {
        return new TaskApplication(APP_ID, "11111111-1111-1111-1111-111111111111",
                "55555555-5555-5555-5555-555555555555", status, null,
                "33333333-3333-3333-3333-333333333333", null, null, null, confirmedAt, 500L, null, null);
    }

    private Task task() {
        return new Task(TASK_ID, OWNER, ORG, "title", "desc", "published", "video", "douyin", 1, 500L,
                Instant.now(), Instant.now(), 1, null, null, null);
    }
}
