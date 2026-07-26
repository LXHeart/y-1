package com.grassland.marketplace.workflow.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

/**
 * {@link ApplicationReservationActivityImpl} 单元测试（草场 Epic 4 Slice 4F / HLD 9.2）：Mockito 桩 reactive repo + finance，
 * 验证各活动幂等 + 执行前重验状态（pending/reserving/accepted 守卫、owner 自查、release 条件分支）。
 */
@ExtendWith(MockitoExtension.class)
class ApplicationReservationActivityImplTest {

    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
    private static final String MERCHANT = "33333333-3333-3333-3333-333333333333";
    private static final String ORG = "44444444-4444-4444-4444-444444444444";
    private static final String RECOMMENDER = "55555555-5555-5555-5555-555555555555";

    @Mock private TaskApplicationRepository apps;
    @Mock private TaskRepository tasks;
    @Mock private OutboxRepository outbox;
    @Mock private FinanceEscrowClient finance;

    private ApplicationReservationActivityImpl activity;
    private AcceptanceInput input;

    @BeforeEach
    void setUp() {
        activity = new ApplicationReservationActivityImpl(apps, tasks, outbox, finance);
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
                "published", "form", "platform", null, 500L, null, null);
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(otherOwned));

        assertThat(activity.beginAcceptance(input)).isFalse();
        verify(apps, never()).findById(anyString());
    }

    @Test
    void beginAcceptance_abortsWhenSlotsFull() {
        when(tasks.findById(TASK_ID)).thenReturn(Mono.just(task(1)));
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("pending")));
        when(apps.countAcceptedByTask(TASK_ID)).thenReturn(Mono.just(1));

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
    void activateEngagement_reservingToAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.acceptFromReserving(APP_ID, TASK_ID)).thenReturn(Mono.just(app("accepted")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.activateEngagement(input);

        verify(apps).acceptFromReserving(APP_ID, TASK_ID);
    }

    @Test
    void activateEngagement_noopWhenAlreadyAccepted() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("accepted")));

        activity.activateEngagement(input);

        verify(apps, never()).acceptFromReserving(anyString(), anyString());
    }

    @Test
    void compensate_releasesAndRevertsWhenReserved() {
        when(finance.release(ORG, APP_ID)).thenReturn(Mono.empty());
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.revertReserving(APP_ID, TASK_ID)).thenReturn(Mono.just(app("pending")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.compensateAcceptance(input, ReserveResult.reserved(500), "activate_failed");

        verify(finance).release(ORG, APP_ID);
        verify(apps).revertReserving(APP_ID, TASK_ID);
    }

    @Test
    void compensate_skipsReleaseWhenInsufficient() {
        when(apps.findById(APP_ID)).thenReturn(Mono.just(app("reserving")));
        when(apps.revertReserving(APP_ID, TASK_ID)).thenReturn(Mono.just(app("pending")));
        when(outbox.append(any())).thenReturn(Mono.empty());

        activity.compensateAcceptance(input, ReserveResult.insufficientFunds(), "insufficient_funds");

        verify(finance, never()).release(anyString(), anyString());
        verify(apps).revertReserving(APP_ID, TASK_ID);
    }

    private Task task(Integer maxSlots) {
        return new Task(TASK_ID, MERCHANT, ORG, "title", "desc", "published",
                "form", "platform", maxSlots, 500L, null, null);
    }

    private TaskApplication app(String status) {
        return new TaskApplication(APP_ID, TASK_ID, RECOMMENDER, status, null, MERCHANT, null, null, null, null);
    }
}
