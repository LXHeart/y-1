package com.grassland.intelligence.orchestration;

import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkerOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 任务书 #100 C100-08：高负载本机/CI 上 workflow 首跑的类加载/JIT 可超过 SDK 默认 1s 死锁窗，
 * TMPRL1101 误杀 workflow task 后重试风暴把 history 撑到 1.4 万事件、任务恒 queued（2026-09-11
 * e2e 实录）。e2e/本地栈经 TEMPORAL_DEADLOCK_DETECTION_TIMEOUT_MS 放宽（60s）；生产默认不动。
 */
@Configuration
public class TemporalWorkerOptionsConfig {

    @Bean
    TemporalOptionsCustomizer<WorkerOptions.Builder> temporalWorkerDeadlockDetectionTimeout(
            @Value("${TEMPORAL_DEADLOCK_DETECTION_TIMEOUT_MS:1000}") long timeoutMs) {
        return builder -> builder.setDefaultDeadlockDetectionTimeout(timeoutMs);
    }
}
