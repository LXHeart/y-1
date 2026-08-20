package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.reputation.RecommenderLevel;
import com.grassland.marketplace.reputation.ReputationEvaluation;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #27 自动通过 dispatcher 单测：达标过滤、声誉权重排序、名额满停轮、
 * 资金补偿冷却、开关与单任务失败不阻塞其它任务。
 *
 * <p>等级来源必须走 ReputationService 权威快照（D6 共享内核的前置），
 * dispatch 只做筛选与排序——这些用纯 mock 锁死；SQL 谓词（published/截止/开关）
 * 由 {@code BatchApplicationControllerIT#findAutoAcceptEnabledOnlyScansEligibleTasks} 覆盖。
 */
class ApplicationAutoAcceptDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final TaskApplicationRepository apps = mock(TaskApplicationRepository.class);
    private final ApplicationAcceptanceService acceptanceService = mock(ApplicationAcceptanceService.class);
    private final ReputationService reputationService = mock(ReputationService.class);

    @Test
    void qualifiedApplicationAutoAcceptedBelowThresholdSkipped() {
        Task task = task(4);
        TaskApplication qualified = application("app-high", "rec-high");
        TaskApplication belowLevel = application("app-low", "rec-low");
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(task));
        when(apps.findByTaskId(task.id(), "pending", null, null, 200))
                .thenReturn(Flux.just(qualified, belowLevel));
        ReputationSnapshot high = snapshot(RecommenderLevel.LV5, 100);
        ReputationSnapshot low = snapshot(RecommenderLevel.LV2, 50);
        when(reputationService.snapshots(anyCollection()))
                .thenReturn(Mono.just(Map.of("rec-high", high, "rec-low", low)));
        when(acceptanceService.acceptForDispatcher(task, qualified, high)).thenReturn(Mono.just("accepted"));

        assertThat(dispatcher().processTasks().block()).isEqualTo(1);

        verify(acceptanceService).acceptForDispatcher(task, qualified, high);
        verify(acceptanceService, never()).acceptForDispatcher(any(), eq(belowLevel), any());
    }

    @Test
    void higherReputationWeightProcessedFirst() {
        Task task = task(1);
        TaskApplication weight10 = application("app-w10", "rec-w10");
        TaskApplication weight90 = application("app-w90", "rec-w90");
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(task));
        when(apps.findByTaskId(task.id(), "pending", null, null, 200))
                .thenReturn(Flux.just(weight10, weight90));
        ReputationSnapshot w10 = snapshot(RecommenderLevel.LV2, 10);
        ReputationSnapshot w90 = snapshot(RecommenderLevel.LV4, 90);
        when(reputationService.snapshots(anyCollection()))
                .thenReturn(Mono.just(Map.of("rec-w10", w10, "rec-w90", w90)));
        when(acceptanceService.acceptForDispatcher(task, weight10, w10)).thenReturn(Mono.just("accepted"));
        when(acceptanceService.acceptForDispatcher(task, weight90, w90)).thenReturn(Mono.just("accepted"));

        assertThat(dispatcher().processTasks().block()).isEqualTo(1);

        var order = inOrder(acceptanceService);
        order.verify(acceptanceService).acceptForDispatcher(task, weight90, w90);
        order.verify(acceptanceService).acceptForDispatcher(task, weight10, w10);
    }

    @Test
    void slotsFullStopsRemainingEntriesForTaskThisRound() {
        Task task = task(1);
        TaskApplication first = application("app-1", "rec-1");
        TaskApplication second = application("app-2", "rec-2");
        TaskApplication third = application("app-3", "rec-3");
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(task));
        when(apps.findByTaskId(task.id(), "pending", null, null, 200))
                .thenReturn(Flux.just(first, second, third));
        ReputationSnapshot s1 = snapshot(RecommenderLevel.LV3, 30);
        ReputationSnapshot s2 = snapshot(RecommenderLevel.LV3, 20);
        ReputationSnapshot s3 = snapshot(RecommenderLevel.LV3, 10);
        when(reputationService.snapshots(anyCollection()))
                .thenReturn(Mono.just(Map.of("rec-1", s1, "rec-2", s2, "rec-3", s3)));
        when(acceptanceService.acceptForDispatcher(task, first, s1)).thenReturn(Mono.just("accepted"));
        when(acceptanceService.acceptForDispatcher(task, second, s2)).thenReturn(Mono.just("slots_full"));

        assertThat(dispatcher().processTasks().block()).isEqualTo(1);

        // 名额满后本轮不再对该任务剩余 pending 空转 claim（下轮扫描再试）。
        verify(acceptanceService).acceptForDispatcher(task, first, s1);
        verify(acceptanceService).acceptForDispatcher(task, second, s2);
        verify(acceptanceService, never()).acceptForDispatcher(any(), eq(third), any());
    }

    @Test
    void compensatedApplicationEntersCooldownAndIsSkippedNextRound() {
        Task task = task(1);
        TaskApplication app = application("app-c", "rec-c");
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(task));
        when(apps.findByTaskId(task.id(), "pending", null, null, 200)).thenReturn(Flux.just(app));
        ReputationSnapshot snapshot = snapshot(RecommenderLevel.LV4, 40);
        when(reputationService.snapshots(anyCollection()))
                .thenReturn(Mono.just(Map.of("rec-c", snapshot)));
        when(acceptanceService.acceptForDispatcher(task, app, snapshot)).thenReturn(Mono.just("compensated"));

        // 两轮必须共用同一 dispatcher 实例——冷却 map 是实例态。
        ApplicationAutoAcceptDispatcher dispatcher = dispatcher();
        assertThat(dispatcher.processTasks().block()).isEqualTo(1);

        // 第二轮：资金不足补偿回 pending 的报名仍在冷却窗口（60s）内，不得重复派发 Saga。
        assertThat(dispatcher.processTasks().block()).isEqualTo(1);
        verify(acceptanceService, times(1)).acceptForDispatcher(task, app, snapshot);
    }

    @Test
    void failingTaskDoesNotBlockOtherTasks() {
        Task failing = task(1);
        Task healthy = task(1);
        TaskApplication app = application("app-h", "rec-h");
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(failing, healthy));
        when(apps.findByTaskId(failing.id(), "pending", null, null, 200))
                .thenReturn(Flux.error(new RuntimeException("db down")));
        when(apps.findByTaskId(healthy.id(), "pending", null, null, 200)).thenReturn(Flux.just(app));
        ReputationSnapshot snapshot = snapshot(RecommenderLevel.LV3, 30);
        when(reputationService.snapshots(anyCollection()))
                .thenReturn(Mono.just(Map.of("rec-h", snapshot)));
        when(acceptanceService.acceptForDispatcher(healthy, app, snapshot)).thenReturn(Mono.just("accepted"));

        assertThat(dispatcher().processTasks().block()).isEqualTo(1);

        verify(acceptanceService).acceptForDispatcher(healthy, app, snapshot);
    }

    @Test
    void noPendingApplicationsSkipsReputationLookup() {
        Task task = task(1);
        when(tasks.findAutoAcceptEnabled(50)).thenReturn(Flux.just(task));
        when(apps.findByTaskId(task.id(), "pending", null, null, 200)).thenReturn(Flux.empty());

        assertThat(dispatcher().processTasks().block()).isEqualTo(1);
        verifyNoInteractions(reputationService, acceptanceService);
    }

    @Test
    void disabledDispatcherDoesNotReadQueue() {
        assertThat(new ApplicationAutoAcceptDispatcher(
                tasks, apps, acceptanceService, reputationService, false, 50)
                .processTasks().block()).isZero();
        verifyNoInteractions(tasks, apps, acceptanceService, reputationService);
    }

    // ---- helpers ----

    private ApplicationAutoAcceptDispatcher dispatcher() {
        return new ApplicationAutoAcceptDispatcher(tasks, apps, acceptanceService, reputationService, true, 50);
    }

    /** 全参构造（21 字段）：仅本测试关心的 status/autoAcceptMinLevel 有值。 */
    private static Task task(Integer autoAcceptMinLevel) {
        String id = UUID.randomUUID().toString();
        return new Task(id, "merchant-1", "org-1", "自动通过任务", null, "published",
                null, null, null, 0L, NOW, NOW, 1, null, NOW, null,
                1, null, null, autoAcceptMinLevel);
    }

    private static TaskApplication application(String id, String recommenderAccountId) {
        return new TaskApplication(id, "task-1", recommenderAccountId, "pending", null, null,
                null, NOW, NOW, null, 0L, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static ReputationSnapshot snapshot(RecommenderLevel level, int weight) {
        return new ReputationSnapshot(null, null, null, null, new ReputationEvaluation(
                level, level, false, weight, 2, 0, 0, false, false, List.of()));
    }
}
