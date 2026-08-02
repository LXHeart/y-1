package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.ops.OpsCaseRegistrar;
import com.grassland.marketplace.settlement.SettlementReconciliation;
import com.grassland.marketplace.settlement.SettlementReconciliationRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceReconciliationClient;
import com.grassland.marketplace.workflow.TrustResolutionClient;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** {@link SettlementReconciliationActivityImpl} 对账决策矩阵（Slice 7B）。 */
class SettlementReconciliationActivityImplTest {

    private static final String SOURCE = "src-1";
    private static final String DISPUTE = "dispute-1";
    private static final String APP = "app-1";
    private static final String TASK_ID = "task-1";
    private static final String ORG = "org-1";
    private static final String DECISION = "for_recommender";

    private final SettlementReconciliationRepository reconciliations = mock(SettlementReconciliationRepository.class);
    private final TaskApplicationRepository apps = mock(TaskApplicationRepository.class);
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final TrustResolutionClient trust = mock(TrustResolutionClient.class);
    private final FinanceReconciliationClient finance = mock(FinanceReconciliationClient.class);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);
    private final OpsCaseRegistrar opsCases = mock(OpsCaseRegistrar.class);
    private final SettlementReconciliationActivityImpl activity =
            new SettlementReconciliationActivityImpl(reconciliations, apps, tasks, outbox, trust, finance,
                    opsCases, transactions);

    @BeforeEach
    void passThroughTransactions() {
        when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reconciliations.markReconciled(any())).thenReturn(Mono.just(true));
        when(reconciliations.markBlocked(any(), any())).thenReturn(Mono.just(true));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(opsCases.register(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    void terminalRowIsIdempotentNoop() {
        when(reconciliations.findBySourceEventId(SOURCE)).thenReturn(Mono.just(row("reconciled")));

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("reconciled");
        verify(finance, never()).reconcile(any(), any(), any());
        verify(trust, never()).resolve(any(), any());
        verify(outbox, never()).append(any());
    }

    @Test
    void trustMismatchBlocksAndNeverSettles() {
        acceptedMonetaryApp();
        when(trust.resolve(ORG, DISPUTE)).thenReturn(Mono.just(
                new TrustResolutionClient.TrustResolution(DISPUTE, APP, ORG, "final", "for_merchant", 2)));

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("blocked");
        assertThat(outcome.reason()).isEqualTo("trust_mismatch");
        verify(reconciliations).markBlocked(SOURCE, "trust_mismatch");
        verify(reconciliations, never()).markReconciled(any());
        EventEnvelope event = captureSingleEvent();
        assertThat(event.eventType()).isEqualTo("SettlementReconciliationBlocked");
    }

    @Test
    void nonMonetaryCompletesAfterTrustOkWithoutFinance() {
        acceptedApp(0L);
        trustFinal(DECISION);

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("reconciled");
        verify(reconciliations).markReconciled(SOURCE);
        verify(finance, never()).reconcile(any(), any(), any());
        EventEnvelope event = captureSingleEvent();
        assertThat(event.eventType()).isEqualTo("EngagementSettled");
        assertThat(event.payload().get("reason")).isEqualTo("adjudication:for_recommender");
    }

    @Test
    void monetaryAppStaysFundBranchEvenAfterTaskBountyEditedToZero() {
        // snapshot-pinning：app 冻结 bounty=500，但 task 行后来被改到 0（全字段 revise）。
        // 结算必须仍走 finance 对账（fund 分支）——读 app.bountyCents()，不读可变 task.bountyCents()。
        // 否则 fund 任务被改成 0 后会跳过 finance 对账，预留的钱不再走 release/reverse（真实资金错误）。
        when(reconciliations.findBySourceEventId(SOURCE)).thenReturn(Mono.just(row("started")));
        when(apps.findById(APP)).thenReturn(Mono.just(
                new TaskApplication(APP, TASK_ID, "rec", "accepted", null, "own", Instant.now(), Instant.now(),
                        Instant.now(), Instant.now(), 500L)));  // 冻结 500
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(
                new Task(TASK_ID, "own", ORG, "title", "desc", "published", "any", "any", null, 0L,
                        Instant.now(), Instant.now(), 1, null, null, null)));  // task 行已被改到 0
        trustFinal(DECISION);
        when(finance.reconcile(ORG, APP, DECISION)).thenReturn(Mono.just(
                new FinanceReconciliationClient.Result("repaired", "captured")));

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("reconciled");
        verify(finance).reconcile(ORG, APP, DECISION);  // 仍走 finance（fund 分支），未被误判非 fund 跳过
    }

    @Test
    void financeRepairedCompletes() {
        acceptedMonetaryApp();
        trustFinal(DECISION);
        when(finance.reconcile(ORG, APP, DECISION)).thenReturn(Mono.just(
                new FinanceReconciliationClient.Result("repaired", "captured")));

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("reconciled");
        verify(reconciliations).markReconciled(SOURCE);
        assertThat(captureSingleEvent().eventType()).isEqualTo("EngagementSettled");
    }

    @Test
    void financeBlockedOutcomePersistsBlock() {
        acceptedMonetaryApp();
        trustFinal(DECISION);
        when(finance.reconcile(ORG, APP, DECISION)).thenReturn(Mono.just(
                new FinanceReconciliationClient.Result("conflict", "released_but_recommender_won")));

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("blocked");
        assertThat(outcome.reason()).isEqualTo("finance_conflict");
        verify(reconciliations).markBlocked(eq(SOURCE), eq("finance_conflict"));
        verify(reconciliations, never()).markReconciled(any());
    }

    @Test
    void financeTransportErrorPropagatesForRetry() {
        acceptedMonetaryApp();
        trustFinal(DECISION);
        when(finance.reconcile(ORG, APP, DECISION)).thenReturn(Mono.error(
                new FinanceReconciliationClient.FinanceReconciliationException("finance down")));

        assertThatThrownBy(() -> activity.reconcile(input()))
                .isInstanceOf(FinanceReconciliationClient.FinanceReconciliationException.class);
        verify(reconciliations, never()).markReconciled(any());
        verify(reconciliations, never()).markBlocked(any(), any());
    }

    @Test
    void applicationNotConfirmedBlocks() {
        when(reconciliations.findBySourceEventId(SOURCE)).thenReturn(Mono.just(row("started")));
        when(apps.findById(APP)).thenReturn(Mono.just(
                new TaskApplication(APP, TASK_ID, "rec", "accepted", null, "own", Instant.now(), Instant.now(),
                        Instant.now(), null, 0L)));  // confirmedAt=null

        SettlementReconciliationWorkflow.ReconciliationOutcome outcome = activity.reconcile(input());

        assertThat(outcome.status()).isEqualTo("blocked");
        assertThat(outcome.reason()).isEqualTo("application_not_confirmed");
        verify(trust, never()).resolve(any(), any());
    }

    private void acceptedMonetaryApp() {
        acceptedApp(500L);
    }

    private void acceptedApp(long bounty) {
        when(reconciliations.findBySourceEventId(SOURCE)).thenReturn(Mono.just(row("started")));
        when(apps.findById(APP)).thenReturn(Mono.just(
                new TaskApplication(APP, TASK_ID, "rec", "accepted", null, "own", Instant.now(), Instant.now(),
                        Instant.now(), Instant.now(), bounty)));  // bounty 冻结在 app（snapshot-pinning），结算读它
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(
                new Task(TASK_ID, "own", ORG, "title", "desc", "published", "any", "any", null, bounty,
                        Instant.now(), Instant.now(), 1, null, null, null)));
    }

    private void trustFinal(String decision) {
        when(trust.resolve(ORG, DISPUTE)).thenReturn(Mono.just(
                new TrustResolutionClient.TrustResolution(DISPUTE, APP, ORG, "final", decision, 2)));
    }

    private SettlementReconciliationWorkflow.ReconciliationInput input() {
        return new SettlementReconciliationWorkflow.ReconciliationInput(SOURCE, DISPUTE, APP, DECISION);
    }

    private SettlementReconciliation row(String status) {
        return new SettlementReconciliation(SOURCE, DISPUTE, APP, ORG, DECISION,
                "settlement-reconcile-" + DISPUTE, status, null, 0, Instant.now(), Instant.now(),
                Instant.now(), Instant.now(), Instant.now());
    }

    private EventEnvelope captureSingleEvent() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        return captor.getValue();
    }
}
