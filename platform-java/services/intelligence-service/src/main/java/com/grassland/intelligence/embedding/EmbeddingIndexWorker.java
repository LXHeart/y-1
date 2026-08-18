package com.grassland.intelligence.embedding;

import com.grassland.intelligence.contentlibrary.AssetStatus;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.contentlibrary.ContentAssetRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 素材 Embedding 索引 worker（任务书 #33）。每轮：先有界回填缺失索引意图，再 claim 一批
 * pending/到期 failed/超租约 processing 行逐条执行；执行前重读素材校验状态/版本/哈希，
 * 漂移行转 stale；失败记录稳定错误码并指数退避（上限 1 小时），达到上限保留 failed。
 * Provider 执行从不进入素材 CRUD 事务。
 */
@Component
@EnableConfigurationProperties(EmbeddingIndexProperties.class)
public final class EmbeddingIndexWorker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexWorker.class);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);
    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(60);

    private final ContentAssetEmbeddingRepository embeddings;
    private final ContentAssetRepository assets;
    private final EmbeddingExecutionService executionService;
    private final EmbeddingIndexProperties properties;
    private final AtomicBoolean dispatching = new AtomicBoolean();

    public EmbeddingIndexWorker(
            ContentAssetEmbeddingRepository embeddings,
            ContentAssetRepository assets,
            EmbeddingExecutionService executionService,
            EmbeddingIndexProperties properties) {
        this.embeddings = embeddings;
        this.assets = assets;
        this.executionService = executionService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ai.embedding-index.poll-interval-ms:3000}")
    public void scheduled() {
        if (!properties.enabled() || !dispatching.compareAndSet(false, true)) {
            return;
        }
        runOnce()
                .doOnError(error -> log.error("embedding index batch failed", error))
                .onErrorResume(error -> Mono.empty())
                .doFinally(signal -> dispatching.set(false))
                .subscribe();
    }

    /** 单轮完整调度（回填 + 处理一批）；测试直接驱动。 */
    public Mono<Void> runOnce() {
        if (!properties.enabled()) {
            return Mono.empty();
        }
        return backfill()
                .then(processBatch())
                .onErrorResume(error -> {
                    log.error("embedding index runOnce failed", error);
                    return Mono.empty();
                });
    }

    /** 补齐缺失索引意图的 active 素材（SQL 反连接当前版本），最多 backfillBatchSize 条。 */
    private Mono<Void> backfill() {
        return assets.findActiveWithoutCurrentIndex(properties.backfillBatchSize())
                .concatMap(asset -> {
                    EmbeddingTextNormalizer.NormalizedText normalized = EmbeddingTextNormalizer.forAsset(asset);
                    return embeddings.enqueue(asset.id(), asset.version(), normalized.contentHash())
                            .onErrorResume(error -> {
                                log.warn("embedding backfill enqueue failed: assetId={}", asset.id());
                                return Mono.just(false);
                            });
                })
                .then();
    }

    /** 认领一批索引行并逐条处理。 */
    private Mono<Void> processBatch() {
        UUID claimToken = UUID.randomUUID();
        return embeddings.claimBatch(properties.batchSize(), claimToken, properties.claimLease(),
                        properties.maxAttempts())
                .concatMap(row -> process(row, claimToken).onErrorResume(error -> {
                    log.warn("embedding row processing failed: assetId={}, attempt={}",
                            row.assetId(), row.attemptCount());
                    return Mono.empty();
                }))
                .then();
    }

    private Mono<Void> process(ContentAssetEmbedding row, UUID claimToken) {
        return assets.findById(row.assetId())
                .flatMap(asset -> {
                    if (!isCurrentActive(asset, row)) {
                        return embeddings.markStale(row.id(), claimToken).then();
                    }
                    EmbeddingTextNormalizer.NormalizedText normalized = EmbeddingTextNormalizer.forAsset(asset);
                    if (!normalized.contentHash().equals(row.contentHash())) {
                        return embeddings.markStale(row.id(), claimToken).then();
                    }
                    return executionService.embedForIndexing(
                                    asset.ownerAccountId(), asset.organizationId(), normalized.text())
                            .flatMap(outcome -> embeddings.markReady(
                                    row.id(), claimToken, outcome.provider(), outcome.algorithmVersion(),
                                    outcome.vector(), outcome.runId()))
                            .then();
                })
                .switchIfEmpty(embeddings.markStale(row.id(), claimToken).then())
                .onErrorResume(error -> failRow(row, claimToken, error));
    }

    /** 素材仍是 active、未软删且版本与索引行一致，才允许执行。 */
    private static boolean isCurrentActive(ContentAsset asset, ContentAssetEmbedding row) {
        return asset.status() == AssetStatus.ACTIVE
                && asset.deletedAt() == null
                && asset.version() == row.assetVersion();
    }

    private Mono<Void> failRow(ContentAssetEmbedding row, UUID claimToken, Throwable error) {
        String code = stableCode(error);
        Duration delay = retryDelay(row.attemptCount());
        return embeddings.markFailed(row.id(), claimToken, code, delay)
                .onErrorResume(markError -> {
                    log.warn("embedding markFailed failed: assetId={}", row.assetId());
                    return Mono.just(false);
                })
                .then();
    }

    /** 指数退避：60s * 2^(n-1)，封顶 1 小时。 */
    private static Duration retryDelay(int attemptCount) {
        long cappedSeconds = Math.round(Math.min(
                (double) MAX_RETRY_DELAY.toSeconds(),
                BASE_RETRY_DELAY.toSeconds() * Math.pow(2, Math.max(attemptCount - 1, 0))));
        return Duration.ofSeconds(Math.max(cappedSeconds, 1));
    }

    private static String stableCode(Throwable error) {
        if (error instanceof IntelligenceException intelligence && intelligence.code() != null) {
            return intelligence.code();
        }
        return "provider_failure";
    }
}
