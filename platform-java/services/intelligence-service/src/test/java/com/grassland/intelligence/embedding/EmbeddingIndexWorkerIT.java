package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #33：素材 Embedding 索引 worker 集成测试。真实 DB + 真实 EmbeddingExecutionService
 * （真实 retrieval Run / 0 成本预算 / settle），仅对 Provider 注册表打桩控制成功与失败路径；
 * 调度器在基座里已静默，全部断言直接驱动 {@code runOnce()}。
 */
class EmbeddingIndexWorkerIT extends IntelligenceItSupport {

    @MockitoBean
    private EmbeddingProviderRegistry providers;

    @org.springframework.beans.factory.annotation.Autowired
    private EmbeddingIndexWorker worker;

    @org.springframework.beans.factory.annotation.Autowired
    private ContentAssetEmbeddingRepository embeddings;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM content_asset_embedding").then().block();
        db.sql("DELETE FROM content_asset_version").then().block();
        db.sql("DELETE FROM content_asset").then().block();
        when(providers.require("sandbox")).thenReturn(new SandboxEmbeddingProvider());
    }

    /** 直接插一行 active 素材（绕过 controller），返回 asset id。 */
    private UUID seedAsset(String title, String tagsJson) {
        UUID assetId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        db.sql("""
                INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type,
                                             size_bytes, source, status)
                VALUES (CAST(:media AS uuid), 'worker-acct', 'content_asset', :key, 'image/png', 1024, 'upload', 'active')
                """)
                .bind("media", mediaId.toString())
                .bind("key", "media/worker/" + mediaId)
                .then().block();
        db.sql("""
                INSERT INTO content_asset (id, media_reference_id, library_type, category, owner_account_id,
                                           title, tags, mime_type, size_bytes, status, version)
                VALUES (CAST(:id AS uuid), CAST(:media AS uuid), 'personal', 'campaign', 'worker-acct',
                        :title, CAST(:tags AS jsonb), 'image/png', 1024, 'active', 1)
                """)
                .bind("id", assetId.toString())
                .bind("media", mediaId.toString())
                .bind("title", title)
                .bind("tags", tagsJson)
                .then().block();
        return assetId;
    }

    private Map<String, Object> embeddingRow(UUID assetId) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        db.sql("""
                        SELECT status, dimensions, embedding IS NOT NULL AS has_vector, ai_run_id::text AS run_id,
                               attempt_count, failure_code, next_attempt_at
                        FROM content_asset_embedding WHERE asset_id = CAST(:asset AS uuid)
                        """)
                .bind("asset", assetId.toString())
                .map(r -> {
                    row.put("status", r.get("status", String.class));
                    row.put("dimensions", r.get("dimensions", Integer.class));
                    row.put("has_vector", r.get("has_vector", Boolean.class));
                    row.put("run_id", r.get("run_id", String.class));
                    row.put("attempt_count", r.get("attempt_count", Integer.class));
                    row.put("failure_code", r.get("failure_code", String.class));
                    row.put("next_attempt_at", r.get("next_attempt_at", java.time.OffsetDateTime.class));
                    return r;
                })
                .one().block();
        return row;
    }

    private String runStatus(String runId) {
        return db.sql("SELECT status FROM ai_run WHERE id = CAST(:id AS uuid)")
                .bind("id", runId)
                .map(row -> row.get("status", String.class)).one().block();
    }

    /** 该账号最近一次 retrieval Run 的状态（失败行不回写 run_id 时用此口径断言 Run 闭环）。 */
    private String latestRetrievalRunStatus(String accountId) {
        return db.sql("""
                        SELECT status FROM ai_run
                        WHERE account_id = :account AND capability = 'retrieval'
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .bind("account", accountId)
                .map(row -> row.get("status", String.class)).one().block();
    }

    @Test
    void runOnceIndexesClaimedAssetToReadyVectorAndCompletedRun() {
        UUID asset = seedAsset("开业大促", "[\"咖啡\"]");
        assertThat(embeddings.enqueue(asset, 1,
                EmbeddingTextNormalizer.forAsset(assetRecord(asset)).contentHash()).block()).isTrue();

        worker.runOnce().block(Duration.ofSeconds(30));

        Map<String, Object> row = embeddingRow(asset);
        assertThat(row.get("status")).isEqualTo("ready");
        assertThat(row.get("dimensions")).isEqualTo(256);
        assertThat(row.get("has_vector")).isEqualTo(true);
        String runId = (String) row.get("run_id");
        assertThat(runId).isNotBlank();
        assertThat(runStatus(runId)).isEqualTo("completed");
        String capability = db.sql("SELECT capability FROM ai_run WHERE id = CAST(:id AS uuid)")
                .bind("id", runId).map(r -> r.get("capability", String.class)).one().block();
        assertThat(capability).isEqualTo("retrieval");
    }

    @Test
    void providerFailureMarksFailedWithStableCodeAndBackoff() {
        UUID asset = seedAsset("门店环境", "[]");
        com.grassland.intelligence.contentlibrary.ContentAsset record = assetRecord(asset);
        assertThat(embeddings.enqueue(asset, 1,
                EmbeddingTextNormalizer.forAsset(record).contentHash()).block()).isTrue();
        when(providers.require("sandbox")).thenThrow(
                new IntelligenceException(503, "unsupported_provider", "暂不支持该Embedding模型供应商"));

        worker.runOnce().block(Duration.ofSeconds(30));

        Map<String, Object> row = embeddingRow(asset);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("failure_code")).isEqualTo("unsupported_provider");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        java.time.OffsetDateTime next = (java.time.OffsetDateTime) row.get("next_attempt_at");
        assertThat(next).isAfter(java.time.OffsetDateTime.now());
        assertThat(latestRetrievalRunStatus("worker-acct")).isEqualTo("failed");
    }

    @Test
    void versionDriftStalesClaimedRowAndBackfillsCurrentVersion() {
        UUID asset = seedAsset("旧版素材", "[]");
        assertThat(embeddings.enqueue(asset, 9, "hash-of-old-version").block()).isTrue();

        worker.runOnce().block(Duration.ofSeconds(30));

        String staleStatus = db.sql("""
                        SELECT status FROM content_asset_embedding
                        WHERE asset_id = CAST(:asset AS uuid) AND asset_version = 9
                        """)
                .bind("asset", asset.toString())
                .map(row -> row.get("status", String.class)).one().block();
        assertThat(staleStatus).isEqualTo("stale");
        String currentStatus = db.sql("""
                        SELECT status FROM content_asset_embedding
                        WHERE asset_id = CAST(:asset AS uuid) AND asset_version = 1
                        """)
                .bind("asset", asset.toString())
                .map(row -> row.get("status", String.class)).one().block();
        assertThat(currentStatus).isEqualTo("ready");
    }

    @Test
    void missingAssetStalesClaimedRow() {
        UUID ghost = UUID.randomUUID();
        assertThat(embeddings.enqueue(ghost, 1, "hash-ghost").block()).isTrue();

        worker.runOnce().block(Duration.ofSeconds(30));

        assertThat(embeddingRow(ghost).get("status")).isEqualTo("stale");
    }

    @Test
    void expiredProcessingLeaseIsReclaimed() {
        UUID asset = seedAsset("过期租约", "[]");
        String hash = EmbeddingTextNormalizer.forAsset(assetRecord(asset)).contentHash();
        assertThat(embeddings.enqueue(asset, 1, hash).block()).isTrue();
        db.sql("""
                UPDATE content_asset_embedding SET status = 'processing', claim_token = gen_random_uuid(),
                       claimed_until = now() - interval '5 minutes', attempt_count = 1
                WHERE asset_id = CAST(:asset AS uuid)
                """)
                .bind("asset", asset.toString()).then().block();

        worker.runOnce().block(Duration.ofSeconds(30));

        assertThat(embeddingRow(asset).get("status")).isEqualTo("ready");
    }

    @Test
    void maxAttemptRowsAreNotReclaimed() {
        UUID asset = seedAsset("到达上限", "[]");
        String hash = EmbeddingTextNormalizer.forAsset(assetRecord(asset)).contentHash();
        assertThat(embeddings.enqueue(asset, 1, hash).block()).isTrue();
        db.sql("""
                UPDATE content_asset_embedding SET status = 'failed', attempt_count = 5,
                       failure_code = 'provider_failure', next_attempt_at = now() - interval '1 hour'
                WHERE asset_id = CAST(:asset AS uuid)
                """)
                .bind("asset", asset.toString()).then().block();

        worker.runOnce().block(Duration.ofSeconds(30));

        Map<String, Object> row = embeddingRow(asset);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("attempt_count")).isEqualTo(5);
    }

    @Test
    void backfillEnqueuesMissingActiveAssetsOnly() {
        UUID indexed = seedAsset("已索引素材", "[]");
        String hash = EmbeddingTextNormalizer.forAsset(assetRecord(indexed)).contentHash();
        assertThat(embeddings.enqueue(indexed, 1, hash).block()).isTrue();
        UUID missing = seedAsset("待补齐素材", "[]");

        worker.runOnce().block(Duration.ofSeconds(30));

        int missingRows = db.sql("""
                        SELECT COUNT(*)::int AS count FROM content_asset_embedding
                        WHERE asset_id = CAST(:asset AS uuid)
                        """)
                .bind("asset", missing.toString())
                .map(row -> row.get("count", Integer.class)).one().block();
        assertThat(missingRows).isEqualTo(1);
        assertThat(embeddingRow(missing).get("status")).isEqualTo("ready");
        int indexedRows = db.sql("""
                        SELECT COUNT(*)::int AS count FROM content_asset_embedding
                        WHERE asset_id = CAST(:asset AS uuid)
                        """)
                .bind("asset", indexed.toString())
                .map(row -> row.get("count", Integer.class)).one().block();
        assertThat(indexedRows).isEqualTo(1);
    }

    @Test
    void claimBatchIsBoundedByBatchSize() {
        for (int i = 0; i < 25; i++) {
            UUID asset = seedAsset("批量素材 " + i, "[]");
            assertThat(embeddings.enqueue(asset, 1,
                    EmbeddingTextNormalizer.forAsset(assetRecord(asset)).contentHash()).block()).isTrue();
        }

        worker.runOnce().block(Duration.ofSeconds(60));

        Integer processed = db.sql("""
                        SELECT COUNT(*)::int AS count FROM content_asset_embedding
                        WHERE status IN ('ready', 'failed', 'processing')
                        """)
                .map(row -> row.get("count", Integer.class)).one().block();
        Integer pending = db.sql(
                        "SELECT COUNT(*)::int AS count FROM content_asset_embedding WHERE status = 'pending'")
                .map(row -> row.get("count", Integer.class)).one().block();
        assertThat(processed).isEqualTo(20);
        assertThat(pending).isEqualTo(5);
    }

    /** 读回 asset 行构造成 ContentAsset 记录（仅 worker 校验用字段；tags 走仓储同款 JSON 解析）。 */
    private com.grassland.intelligence.contentlibrary.ContentAsset assetRecord(UUID assetId) {
        return db.sql("""
                        SELECT id::text, media_reference_id::text, library_type, category, owner_account_id,
                               organization_id, title, tags::text AS tags, mime_type, size_bytes, status, version,
                               source, license_scope
                        FROM content_asset WHERE id = CAST(:id AS uuid)
                        """)
                .bind("id", assetId.toString())
                .map(row -> new com.grassland.intelligence.contentlibrary.ContentAsset(
                        UUID.fromString(row.get("id", String.class)),
                        UUID.fromString(row.get("media_reference_id", String.class)),
                        com.grassland.intelligence.contentlibrary.LibraryType
                                .fromRequest(row.get("library_type", String.class)),
                        com.grassland.intelligence.contentlibrary.AssetCategory
                                .fromRequest(row.get("category", String.class)),
                        row.get("owner_account_id", String.class),
                        row.get("organization_id", String.class),
                        row.get("title", String.class),
                        com.grassland.intelligence.contentlibrary.ContentAssetRepository
                                .parseTagsStatic(row.get("tags", String.class)),
                        row.get("mime_type", String.class),
                        row.get("size_bytes", Long.class),
                        null,
                        com.grassland.intelligence.contentlibrary.AssetStatus
                                .valueOf(row.get("status", String.class).toUpperCase()),
                        row.get("version", Integer.class),
                        row.get("source", String.class),
                        row.get("license_scope", String.class),
                        null, null, null, null, null, null, null))
                .one().block();
    }
}
