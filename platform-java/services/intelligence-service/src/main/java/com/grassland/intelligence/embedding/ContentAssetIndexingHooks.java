package com.grassland.intelligence.embedding;

import com.grassland.intelligence.contentlibrary.AssetStatus;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 素材变更 → Embedding 索引意图钩子（任务书 #33）。控制器在 CRUD 事务内调用：
 * active 结果落库前入队（幂等）；删除/驳回把当前 pending/ready 行全部转 stale。
 * 只做意图登记，从不触发 Provider 执行——执行始终由 {@link EmbeddingIndexWorker} 异步完成。
 */
@Component
public final class ContentAssetIndexingHooks {

    private final ContentAssetEmbeddingRepository embeddings;

    public ContentAssetIndexingHooks(ContentAssetEmbeddingRepository embeddings) {
        this.embeddings = embeddings;
    }

    /** 创建/审核通过：active 素材入队当前版本索引意图（同 CRUD 事务）。 */
    public Mono<Void> onActiveAsset(ContentAsset asset) {
        if (asset == null || asset.status() != AssetStatus.ACTIVE) {
            return Mono.empty();
        }
        EmbeddingTextNormalizer.NormalizedText normalized = EmbeddingTextNormalizer.forAsset(asset);
        return embeddings.enqueue(asset.id(), asset.version(), normalized.contentHash()).then();
    }

    /** 编辑/版本推进：入队新版本意图，并把其它版本的 pending/ready 行转 stale（同 CRUD 事务）。 */
    public Mono<Void> onActiveAssetInvalidatingOld(ContentAsset updated) {
        if (updated == null || updated.status() != AssetStatus.ACTIVE) {
            return Mono.empty();
        }
        EmbeddingTextNormalizer.NormalizedText normalized = EmbeddingTextNormalizer.forAsset(updated);
        return embeddings.enqueue(updated.id(), updated.version(), normalized.contentHash())
                .then(embeddings.markOtherRowsStale(updated.id(), updated.version(), normalized.contentHash()))
                .then();
    }

    /** 删除/驳回：当前 pending/ready 行全部转 stale（同 CRUD 事务）。 */
    public Mono<Void> onRemovedAsset(UUID assetId) {
        if (assetId == null) {
            return Mono.empty();
        }
        return embeddings.markCurrentRowsStale(assetId).then();
    }
}
