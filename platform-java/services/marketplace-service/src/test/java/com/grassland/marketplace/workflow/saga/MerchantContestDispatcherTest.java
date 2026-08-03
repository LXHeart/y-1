package com.grassland.marketplace.workflow.saga;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@link MerchantContestDispatcher} 扫描与逐行失败隔离单测。 */
class MerchantContestDispatcherTest {

    private TaskApplicationRepository apps;
    private TaskRepository tasks;
    private MerchantContestCoordinator coordinator;
    private MerchantContestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        apps = org.mockito.Mockito.mock(TaskApplicationRepository.class);
        tasks = org.mockito.Mockito.mock(TaskRepository.class);
        coordinator = org.mockito.Mockito.mock(MerchantContestCoordinator.class);
        dispatcher = new MerchantContestDispatcher(apps, tasks, coordinator, 32);
    }

    @Test
    void emptyBatchDoesNothing() {
        when(apps.findContestDispatchable(32)).thenReturn(Flux.empty());

        dispatcher.dispatchBatch();

        verify(tasks, never()).findById(any());
        verify(coordinator, never()).dispatch(any(), any());
    }

    @Test
    void dispatchesClaimWithTask() {
        TaskApplication app = app();
        Task task = task(app.taskId());
        when(apps.findContestDispatchable(32)).thenReturn(Flux.just(app));
        when(tasks.findById(app.taskId())).thenReturn(Mono.just(task));
        when(coordinator.dispatch(app, task)).thenReturn(Mono.just(app));

        dispatcher.dispatchBatch();

        verify(coordinator).dispatch(app, task);
    }

    @Test
    void missingTaskSkipsClaim() {
        TaskApplication app = app();
        when(apps.findContestDispatchable(32)).thenReturn(Flux.just(app));
        when(tasks.findById(app.taskId())).thenReturn(Mono.empty());

        dispatcher.dispatchBatch();

        verify(coordinator, never()).dispatch(any(), any());
    }

    @Test
    void oneFailureDoesNotBlockNextClaim() {
        TaskApplication first = app();
        TaskApplication second = new TaskApplication(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "rec-2", "accepted", null,
                "merchant-1", null, null, null, null, 500L, Instant.now().plusSeconds(60), null,
                null, "理由二", null, Instant.now(), null);
        Task firstTask = task(first.taskId());
        Task secondTask = task(second.taskId());
        when(apps.findContestDispatchable(32)).thenReturn(Flux.just(first, second));
        when(tasks.findById(first.taskId())).thenReturn(Mono.just(firstTask));
        when(tasks.findById(second.taskId())).thenReturn(Mono.just(secondTask));
        when(coordinator.dispatch(first, firstTask)).thenReturn(Mono.error(new IllegalStateException("down")));
        when(coordinator.dispatch(second, secondTask)).thenReturn(Mono.just(second));

        dispatcher.dispatchBatch();

        verify(coordinator).dispatch(first, firstTask);
        verify(coordinator).dispatch(second, secondTask);
        verify(apps).findContestDispatchable(32);
    }

    private TaskApplication app() {
        return new TaskApplication(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "rec-1", "accepted", null,
                "merchant-1", null, null, null, null, 500L, Instant.now().plusSeconds(60), null,
                null, "理由", null, Instant.now(), null);
    }

    private Task task(String id) {
        return new Task(id, "merchant-1", UUID.randomUUID().toString(), "title", "desc", "published",
                "video", "douyin", 1, 500L, Instant.now(), Instant.now(), 1, null, null, null);
    }
}
