package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.TaskApplication;
import com.grassland.marketplace.taskcatalog.TaskApplicationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 交付看门狗补启派发器（任务书 #96 C96-01 / D96-02）：消除「accept 落定、Temporal start 前进程崩溃」间隙。
 *
 * <p>durable intent = {@code status='accepted' AND delivery_workflow_started_at IS NULL} 的政策版内交付中报名
 * （存量行/套餐推广/已退出被 SQL 排除，D96-07）。按 DB 快照算**剩余秒数**（不重置窗口），确定性 workflowId
 * {@code delivery-<appId>-<remedyEndEpoch>} + AlreadyStarted 幂等；启动成功后 guarded 标记。延期批准清空标记
 * ⇒ 下一轮按新截止重派（新 workflowId）。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.engagement", name = "dispatcher-enabled",
        havingValue = "true", matchIfMissing = true)
public class DeliveryDeadlineDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDeadlineDispatcher.class);

    private final TaskApplicationRepository apps;
    private final DeliveryDeadlineWorkflowStarter starter;
    private final int batchSize;
    private final long reminderLeadSeconds;

    public DeliveryDeadlineDispatcher(
            TaskApplicationRepository apps,
            DeliveryDeadlineWorkflowStarter starter,
            @Value("${marketplace.engagement.dispatcher-batch-size:32}") int batchSize,
            @Value("${marketplace.engagement.reminder-lead-seconds:86400}") long reminderLeadSeconds) {
        this.apps = apps;
        this.starter = starter;
        this.batchSize = Math.max(1, batchSize);
        this.reminderLeadSeconds = Math.max(0, reminderLeadSeconds);
    }

    @Scheduled(fixedDelayString = "${marketplace.engagement.dispatcher-poll-ms:2000}")
    public void dispatch() {
        Mono.fromRunnable(this::dispatchBatch)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    void dispatchBatch() {
        List<TaskApplication> rows = apps.findDeliveryDispatchable(batchSize).collectList().block();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (TaskApplication app : rows) {
            dispatchOne(app);
        }
    }

    private void dispatchOne(TaskApplication app) {
        try {
            if (app.deliveryDeadlineAt() == null || app.remedyDeadlineAt() == null) {
                return;
            }
            Instant now = Instant.now();
            long remainingToDeadline = Math.max(0,
                    Duration.between(now, app.deliveryDeadlineAt()).toSeconds());
            long remainingToRemedyEnd = Math.max(0,
                    Duration.between(now, app.remedyDeadlineAt()).toSeconds());
            starter.start(app.id(), app.taskId(), null, app.remedyDeadlineAt(),
                            remainingToDeadline, remainingToRemedyEnd, reminderLeadSeconds)
                    .block();
            apps.markDeliveryDispatched(app.id()).block();
        } catch (RuntimeException failure) {
            // 不标记，下轮重试；确定性 workflowId 保证「start 已成功但 mark 失败」也安全。
            log.warn("delivery watchdog dispatch failed app={}", app.id(), failure);
        }
    }
}
