package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.AcceptanceCommandRepository;
import com.grassland.marketplace.taskcatalog.TaskAcceptanceCounterRepository;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskFullAutoCloser;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * {@link ApplicationReservationActivityImpl} 单元测试（草场 Epic 4 Slice 4F / HLD 9.2）：Mockito 桩 reactive repo + finance，
 * 验证各活动幂等 + 执行前重验状态（pending/reserving/accepted 守卫、owner 自查、release 条件分支）。
 *
 * <p>Slice 7C-2：activity 把「领域写 + outbox append」包进 {@code transactions.transactional(...)}。
 * 单测里把 {@link TransactionalOperator} 桩成<b>直通</b>（原样返回被包的 Mono），故断言不变；
 * 真实回滚由 {@code ActivityOutboxAtomicityIT}（testcontainers + spy outbox）证明。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ApplicationReservationActivityImplTest {

    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String MERCHANT = "33333333-3333-3333-3333-333333333333";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";
    private static final String RECOMMENDER = "55555555-5555-5555-5555-555555555555";
    private static final String OPERATOR = "66666666-6666-6666-6666-666666666666";

    @Mock private TaskApplicationRepository apps;
    @Mock private TaskAcceptanceCounterRepository counters;
    @Mock private AcceptanceCommandRepository commands;
    @Mock private TaskRepository tasks;
    @Mock private OutboxRepository outbox;
    @Mock private FinanceEscrowClient finance;
    @Mock private TransactionalOperator transactions;
    @Mock private TaskFullAutoCloser taskFullAutoCloser;

    private ApplicationReservationActivityImpl activity;
    private AcceptanceInput input;

    @BeforeEach
    void setUp() {
        // 直通：transactional(mono) 原样返回被包的 Mono（reserveFunds 测试不触发，用 lenient 避免 strict stubbing 报错）。
        lenient().when(transactions.transactional(any(Mono.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counters.claim(TASK_ID)).thenReturn(Mono.just(1));
        lenient().when(counters.release(TASK_ID)).thenReturn(Mono.just(true));
        // #26：激活落定后同事务判定关闭；默认未满/无上限 → empty（thenReturn 照常透传激活结果）。
        lenient().when(taskFullAutoCloser.closeIfFull(TASK_ID)).thenReturn(Mono.empty());
        activity = new ApplicationReservationActivityImpl(
                apps, counters, commands, tasks, outbox, finance, transactions, taskFullAutoCloser);
        input = new AcceptanceInput(APP_ID, TASK_ID, MERCHANT, ORG, 500L);
    }

    @Test
    void beginAcceptance_transitionsPendingToReserving() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(null)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending")));
        when(apps.beginAcceptance(APP_ID, TASK_ID, MERCHANT)).thenReturn(Mono.just(app("reserving")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(activity.beginAcceptance(input)).isTrue();
        verify(apps).beginAcceptance(APP_ID, TASK_ID, MERCHANT);
    }

    @Test
    void beginAcceptance_recordsIndependentStoreManagerAsOperator() {
        AcceptanceInput managerInput = new AcceptanceInput(
                APP_ID, TASK_ID, MERCHANT, ORG, 500L, OPERATOR);
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(null)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending")));
        when(apps.beginAcceptance(APP_ID, TASK_ID, OPERATOR)).thenReturn(Mono.just(app("reserving")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        assertThat(activity.beginAcceptance(managerInput)).isTrue();
        verify(apps).beginAcceptance(APP_ID, TASK_ID, OPERATOR);
    }

    @Test
    void beginAcceptance_idempotentWhenAlreadyReserving() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(null)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));

        assertThat(activity.beginAcceptance(input)).isTrue();  // 幂等
        verify(apps, never()).beginAcceptance(anyString(), anyString(), anyString());
    }

    @Test
    void beginAcceptance_abortsWhenTerminal() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(null)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted")));

        assertThat(activity.beginAcceptance(input)).isFalse();
        verify(apps, never()).beginAcceptance(anyString(), anyString(), anyString());
    }

    @Test
    void beginAcceptance_abortsWhenNotOwner() {
        Task otherOwned = new Task(TASK_ID, "99999999-9999-9999-9999-999999999999", ORG, "t", "d",
                "published", "form", "platform", null, 500L, null, null, 1, null, null, null);
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(otherOwned));

        assertThat(activity.beginAcceptance(input)).isFalse();
        verify(apps, never()).findById(anyString());
    }

    @Test
    void beginAcceptance_abortsWhenSlotsFull() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(1)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending")));
        when(counters.claim(TASK_ID)).thenReturn(Mono.empty());

        assertThat(activity.beginAcceptance(input)).isFalse();
        verify(apps, never()).beginAcceptance(anyString(), anyString(), anyString());
    }

    /** 预留时要把**收款推荐官**一并告知 finance，否则 capture 阶段无从分账（钱会停在平台账上）。 */
    @Test
    void reserveFunds_delegatesToFinanceWithPayee() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(finance.reserve(eq(ORG), eq(APP_ID), eq(500L), eq(RECOMMENDER)))
                .thenReturn(Mono.just(ReserveResult.reserved(500)));

        assertThat(activity.reserveFunds(input).reserved()).isTrue();
        verify(finance).reserve(ORG, APP_ID, 500L, RECOMMENDER);
    }

    @Test
    void reserveFunds_passesAcceptanceCommissionBonusSnapshot() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving", 1_000)));
        when(finance.reserve(ORG, APP_ID, 500L, RECOMMENDER, 1_000))
                .thenReturn(Mono.just(ReserveResult.reserved(500)));

        assertThat(activity.reserveFunds(input).reserved()).isTrue();
        verify(finance).reserve(ORG, APP_ID, 500L, RECOMMENDER, 1_000);
    }

    @Test
    void activateEngagement_reservingToAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.acceptFromReserving(APP_ID, TASK_ID, 0L, 0L)).thenReturn(Mono.just(app("accepted")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.activateEngagement(input);

        verify(apps).acceptFromReserving(APP_ID, TASK_ID, 0L, 0L);
        // #26（D2/D4）：激活落定后同事务判定满员关闭
        verify(taskFullAutoCloser).closeIfFull(TASK_ID);
    }

    /** #26：关闭判定失败不吞——抛出让事务回滚、Temporal 重试本 activity（未满时 empty 则照常完成）。 */
    @Test
    void activateEngagement_closeFailurePropagatesForRetry() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.acceptFromReserving(APP_ID, TASK_ID, 0L, 0L)).thenReturn(Mono.just(app("accepted")));
        when(outbox.append(any())).thenReturn(Mono.empty());
        when(taskFullAutoCloser.closeIfFull(TASK_ID))
                .thenReturn(Mono.error(new IllegalStateException("close if full failed")));

        assertThatThrownBy(() -> activity.activateEngagement(input))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("close if full failed");
    }

    @Test
    void activateEngagement_noopWhenAlreadyAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted")));

        activity.activateEngagement(input);

        verify(apps, never()).acceptFromReserving(anyString(), anyString(), anyLong(), anyLong());
        // 重试幂等：已激活则不重复判定关闭（关闭与激活同事务，激活已提交则关闭也已提交）
        verify(taskFullAutoCloser, never()).closeIfFull(anyString());
    }

    @Test
    void compensate_releasesAndRevertsWhenReserved() {
        when(finance.release(ORG, APP_ID)).thenReturn(Mono.empty());
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(1)));  // 事件 payload 的 taskOwnerId 现查
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.revertReserving(APP_ID, TASK_ID)).thenReturn(Mono.just(app("pending")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.compensateAcceptance(input, ReserveResult.reserved(500), "activate_failed");

        verify(finance).release(ORG, APP_ID);
        verify(apps).revertReserving(APP_ID, TASK_ID);
        // #26（D4 护栏）：补偿路径绝不关闭——预留失败回退名额，误关不可收口
        verify(taskFullAutoCloser, never()).closeIfFull(anyString());
    }

    @Test
    void compensate_skipsReleaseWhenInsufficient() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(1)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.revertReserving(APP_ID, TASK_ID)).thenReturn(Mono.just(app("pending")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.compensateAcceptance(input, ReserveResult.insufficientFunds(), "insufficient_funds");

        verify(finance, never()).release(anyString(), anyString());
        verify(apps).revertReserving(APP_ID, TASK_ID);
        verify(taskFullAutoCloser, never()).closeIfFull(anyString());  // #26（D4 护栏）
    }

    private Task task(Integer maxSlots) {
        return new Task(TASK_ID, MERCHANT, ORG, "title", "desc", "published",
                "form", "platform", maxSlots, 500L, null, null, 1, null, null, null);
    }

    private TaskApplication app(String status) {
        return new TaskApplication(APP_ID, TASK_ID, RECOMMENDER, status, null, MERCHANT, null, null, null, null, 0L, null, null);
    }

    private TaskApplication app(String status, int commissionBonusBps) {
        return new TaskApplication(APP_ID, TASK_ID, RECOMMENDER, status, null, MERCHANT,
                null, null, null, null, 0L, null, null, null, null, null, null, null,
                3, 1L, 2, commissionBonusBps, false);
    }
}
