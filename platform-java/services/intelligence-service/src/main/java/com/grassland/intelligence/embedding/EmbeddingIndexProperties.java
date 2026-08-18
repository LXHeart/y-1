package com.grassland.intelligence.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 素材 Embedding 索引 worker 配置（任务书 #33）。全部有界：批量 1-500、租约为正、重试 1-20；
 * 越界直接拒绝启动（fail-fast），避免配置漂移造成无界扫描或无限重试。
 */
@ConfigurationProperties(prefix = "ai.embedding-index")
public record EmbeddingIndexProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("3000") long pollIntervalMs,
        @DefaultValue("20") int batchSize,
        @DefaultValue("100") int backfillBatchSize,
        @DefaultValue("PT1M") Duration claimLease,
        @DefaultValue("5") int maxAttempts) {

    public EmbeddingIndexProperties {
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("ai.embedding-index.poll-interval-ms 必须为正");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("ai.embedding-index.batch-size 必须在 1-500");
        }
        if (backfillBatchSize < 1 || backfillBatchSize > 500) {
            throw new IllegalArgumentException("ai.embedding-index.backfill-batch-size 必须在 1-500");
        }
        if (claimLease == null || claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("ai.embedding-index.claim-lease 必须为正时长");
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("ai.embedding-index.max-attempts 必须在 1-20");
        }
    }
}
