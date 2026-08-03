package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.Task;
import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** F6 contest 补偿派发器：durable claim 后任何进程/网络失败均由下一轮继续，固定 trust/workflow 幂等键防重复。 */
@Component
@ConditionalOnProperty(prefix = "marketplace.contest", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class MerchantContestDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MerchantContestDispatcher.class);

    private final TaskApplicationRepository apps;
    private final TaskRepository tasks;
    private final MerchantContestCoordinator coordinator;
    private final int batchSize;

    public MerchantContestDispatcher(
            TaskApplicationRepository apps,
            TaskRepository tasks,
            MerchantContestCoordinator coordinator,
            @org.springframework.beans.factory.annotation.Value(
                    "${marketplace.contest.dispatcher-batch-size:32}") int batchSize) {
        this.apps = apps;
        this.tasks = tasks;
        this.coordinator = coordinator;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${marketplace.contest.dispatcher-poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    void dispatchBatch() {
        List<TaskApplication> rows = apps.findContestDispatchable(batchSize).collectList().block();
        if (rows == null) {
            return;
        }
        for (TaskApplication app : rows) {
            try {
                Task task = tasks.findById(app.taskId()).block();
                if (task == null) {
                    log.warn("contest dispatch task missing app={}", app.id());
                    continue;
                }
                coordinator.dispatch(app, task).block();
            } catch (RuntimeException failure) {
                log.warn("contest dispatch failed app={}", app.id(), failure);
            }
        }
    }
}
