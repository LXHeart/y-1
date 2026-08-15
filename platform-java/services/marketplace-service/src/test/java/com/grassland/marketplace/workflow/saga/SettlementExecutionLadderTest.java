package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
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
 * {@link SettlementExecution} D-02 阶梯接线单元测试：冻结快照 + 申报指标值 → 按实际档位捕获；
 * 无申报值 / 计划非法 → hold，绝不满额捕获；固定佣金契约保持两参全额 capture 不回归。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class SettlementExecutionLadderTest {

    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OWNER = "66666666-6666-6666-6666-666666666666";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";

    /** 档位：1000→500、10000→1500；发布闸门要求 bounty ≥ 最高档 1500。 */
    private static final String LADDER_SNAPSHOT = """
            {"taskId":"%s","requirements":{"commissionLadder":{
               "policyVersion":"ladder-v1","metricKey":"video.views",
               "tiers":[{"threshold":1000,"payoutCents":500},{"threshold":10000,"payoutCents":1500}]}}}
            """.formatted(TASK_ID);

    @Mock private OutboxRepository outbox;
    @Mock private TaskApplicationRepository apps;
    @Mock private FinanceEscrowClient finance;
    @Mock private DisputeChecker disputes;
    @Mock private VerificationChecker verification;
    @Mock private OpsCaseRegistrar opsCases;
    @Mock private TransactionalOperator transactions;

    private SettlementExecution execution;

    @BeforeEach
    void setUp() {
        lenient().when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(opsCases.register(anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(Mono.empty());
        execution = new SettlementExecution(outbox, apps, finance, disputes, verification, opsCases, transactions);
    }

    @Test
    void fixedContractKeepsFullAmountCapture() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app(4_000L, 1_500L)));
        when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.empty());  // 无快照（历史行）→ 固定佣金
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(false);
        when(finance.capture(ORG, APP_ID)).thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(execution.captureOrHold(ORG, APP_ID, app(4_000L, 1_500L), OWNER).status())
                .isEqualTo("settled");
        verify(finance).capture(ORG, APP_ID);
        verify(finance, never()).capture(anyString(), anyString(), anyLong());
    }

    @Test
    void ladderSettlesAtDeclaredTierAndEmitsAmount() {
        TaskApplication app = app(4_000L, 1_500L);
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app));
        when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.just(LADDER_SNAPSHOT));
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(false);
        when(finance.capture(ORG, APP_ID, 500L)).thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(execution.captureOrHold(ORG, APP_ID, app, OWNER).status()).isEqualTo("settled");
        verify(finance).capture(ORG, APP_ID, 500L);  // 申报 4000 落在 1000 档 → 500，不捕获预留上限 1500
        verify(finance, never()).capture(anyString(), anyString());

        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        Map<String, Object> payload = captor.getValue().payload();
        assertThat(payload.get("settlementAmountCents")).isEqualTo(500L);
    }

    @Test
    void ladderWithoutDeclaredMetricHoldsInsteadOfCapturingReserve() {
        TaskApplication app = app(null, 1_500L);  // 自动确认：无申报值
        when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.just(LADDER_SNAPSHOT));
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome result = execution.captureOrHold(ORG, APP_ID, app, OWNER);
        assertThat(result.status()).isEqualTo("held");
        assertThat(result.reason()).isEqualTo("ladder_metric_missing");
        verify(finance, never()).capture(anyString(), anyString());
        verify(finance, never()).capture(anyString(), anyString(), anyLong());
        verify(finance, never()).release(anyString(), anyString());
    }

    @Test
    void ladderBelowFirstTierReleasesFullReserve() {
        TaskApplication app = app(100L, 1_500L);  // 未达首档 1000 → 结算 0 → 全额返还商家
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app));
        when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.just(LADDER_SNAPSHOT));
        when(disputes.hasOpenDispute(ORG, APP_ID)).thenReturn(false);
        when(finance.release(ORG, APP_ID)).thenReturn(Mono.empty());
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(execution.captureOrHold(ORG, APP_ID, app, OWNER).status()).isEqualTo("settled");
        verify(finance).release(ORG, APP_ID);
        verify(finance, never()).capture(anyString(), anyString(), anyLong());
    }

    @Test
    void ladderReserveMismatchHoldsInsteadOfOverpaying() {
        TaskApplication app = app(4_000L, 400L);  // 预留低于最高档（历史脏数据）→ 计划非法 → hold
        when(apps.findTaskContextSnapshot(APP_ID)).thenReturn(Mono.just(LADDER_SNAPSHOT));
        when(outbox.append(any())).thenReturn(Mono.empty());

        SettlementOutcome result = execution.captureOrHold(ORG, APP_ID, app, OWNER);
        assertThat(result.status()).isEqualTo("held");
        assertThat(result.reason()).isEqualTo("ladder_plan_invalid");
        verify(finance, never()).capture(anyString(), anyString(), anyLong());
    }

    private TaskApplication app(Long declaredMetric, long bountyCents) {
        return new TaskApplication(APP_ID, TASK_ID, "55555555-5555-5555-5555-555555555555",
                "accepted", null, OWNER, null, null, null, Instant.now(), bountyCents,
                null, null, null, null, null, null, null,
                1, 1L, 2, 0, false, declaredMetric);
    }
}
