package com.grassland.intelligence.videoproduction;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 合成 worker（任务书 #64 卡8）：claim {@code phase=composing} 的成片任务执行
 * {@link VideoCompositionService}。合成上限 600s，lease 取配置与 10 分钟的较大者，
 * 防长任务被二次领单并发执行。
 */
@Component
public class CompositionWorker {

    private static final Logger log = LoggerFactory.getLogger(CompositionWorker.class);
    private static final Duration COMPOSE_LEASE_FLOOR = Duration.ofMinutes(10);

    private final VideoProductionTaskRepository tasks;
    private final VideoCompositionService composition;
    private final VideoGenerationProperties properties;

    public CompositionWorker(VideoProductionTaskRepository tasks, VideoCompositionService composition,
            VideoGenerationProperties properties) {
        this.tasks = tasks;
        this.composition = composition;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ai.video-generation.poll-interval:3s}")
    public void dispatch() {
        if (!properties.isWorkerEnabled()) {
            return;
        }
        Duration lease = properties.getClaimLease().compareTo(COMPOSE_LEASE_FLOOR) > 0
                ? properties.getClaimLease()
                : COMPOSE_LEASE_FLOOR;
        tasks.claimBatch(properties.getBatchSize(), lease)
                .filter(task -> VideoProductionTask.PHASE_COMPOSING.equals(task.phase()))
                .flatMap(composition::compose)
                .onErrorContinue((error, value) -> log.warn("compose dispatch item failed value={}", value,
                        error))
                .subscribe();
    }
}
