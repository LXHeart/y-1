package com.grassland.intelligence.media;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * media_reference 仓储（草场 Slice 8 第二步）。表 {@code media_reference} 由 Flyway V4 建。
 *
 * <p>用法复刻 {@code OutboxRepository} / {@code StylePreferencesRepository}：注入 {@link DatabaseClient}，
 * timestamptz 读为 {@link OffsetDateTime} 再转 {@link java.time.Instant}，uuid 经 {@code ::text}/{@code CAST}，
 * 可空字段用 {@code bindNull}。
 */
@Component
public class MediaReferenceRepository {

    private static final String SELECT_COLS = """
            id::text, owner_account_id, organization_id, purpose, domain_type, domain_id,
            object_key, upload_key, mime_type, size_bytes, checksum, source, status,
            created_at, expires_at, deleted_at
            """;

    private final DatabaseClient db;
    private final TransactionalOperator transactions;

    public MediaReferenceRepository(DatabaseClient db, TransactionalOperator transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    /** 全字段插入并回读（upload-ticket 建 pending 行、生成图直接建 active 行都用它）。 */
    public Mono<MediaReference> insert(MediaReference ref) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO media_reference (
                    id, owner_account_id, organization_id, purpose, domain_type, domain_id,
                    object_key, upload_key, mime_type, size_bytes, checksum, source, status, expires_at)
                VALUES (
                    CAST(:id AS uuid), :ownerAccountId, :organizationId, :purpose, :domainType, :domainId,
                    :objectKey, :uploadKey, :mimeType, :sizeBytes, :checksum, :source, :status, :expiresAt)
                ON CONFLICT (id) DO NOTHING
                """)
                .bind("id", ref.id().toString())
                .bind("ownerAccountId", ref.ownerAccountId())
                .bind("purpose", ref.purpose())
                .bind("objectKey", ref.objectKey())
                .bind("mimeType", ref.mimeType())
                .bind("sizeBytes", ref.sizeBytes())
                .bind("source", ref.source())
                .bind("status", ref.status().db());
        spec = bindNullableString(spec, "uploadKey", ref.uploadKey());
        spec = bindNullableString(spec, "organizationId", ref.organizationId());
        spec = bindNullableString(spec, "domainType", ref.domainType());
        spec = bindNullableString(spec, "domainId", ref.domainId());
        spec = bindNullableString(spec, "checksum", ref.checksum());
        spec = bindNullableOffsetDateTime(spec, "expiresAt", ref.expiresAt());
        return spec.then().then(findById(ref.id()));
    }

    /** pending→finalizing：只有一个 confirm 能取得最终化所有权。 */
    public Mono<MediaReference> claimFinalize(UUID id) {
        return db.sql("""
                UPDATE media_reference SET status='finalizing', updated_at=now()
                WHERE id=CAST(:id AS uuid) AND status='pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .map(MediaReferenceRepository::map).one();
    }

    /** finalizing→active，补全校验元数据；保留 upload key 名称，供删除/GC 清理由旧 PUT URL 重建的临时对象。 */
    public Mono<MediaReference> completeFinalize(
            UUID id, String mimeType, long sizeBytes, String checksum) {
        return db.sql("""
                UPDATE media_reference
                SET status='active', mime_type=:mimeType,
                    size_bytes=:sizeBytes, checksum=:checksum, updated_at=now()
                WHERE id=CAST(:id AS uuid) AND status='finalizing'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .bind("mimeType", mimeType)
                .bind("sizeBytes", sizeBytes)
                .bind("checksum", checksum)
                .map(MediaReferenceRepository::map).one();
    }

    public Mono<MediaReference> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM media_reference WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(MediaReferenceRepository::map).one();
    }

    /** finalizing→pending：最终化失败时释放 claim，保留临时 key 供客户端重试。 */
    public Mono<Boolean> releaseFinalize(UUID id) {
        return db.sql("""
                UPDATE media_reference SET status='pending', updated_at=now()
                WHERE id=CAST(:id AS uuid) AND status='finalizing'
                """)
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /** active/pending→deleting：删除操作取得生命周期所有权；finalizing 不可删。 */
    public Mono<MediaReference> claimDelete(UUID id, String ownerAccountId) {
        return transactions.transactional(lockForLifecycle(id).flatMap(ignored -> db.sql("""
                UPDATE media_reference SET status='deleting', updated_at=now()
                WHERE id=CAST(:id AS uuid) AND owner_account_id=:ownerAccountId
                  AND status IN ('pending', 'active')
                  AND NOT EXISTS (SELECT 1 FROM media_kyb_retention r
                                  WHERE r.media_reference_id=media_reference.id AND r.released_at IS NULL
                                    AND (r.lease_until > now() OR r.retained_until > now()))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .bind("ownerAccountId", ownerAccountId)
                .map(MediaReferenceRepository::map).one()));
    }

    /** cleanup 专用：对到期候选取得/刷新 deleting 所有权；stale deleting 可重试。 */
    public Mono<MediaReference> claimCleanup(UUID id) {
        return transactions.transactional(lockForLifecycle(id).flatMap(ignored -> db.sql("""
                UPDATE media_reference SET status='deleting', updated_at=now()
                WHERE id=CAST(:id AS uuid) AND status IN ('pending', 'active', 'finalizing', 'deleting')
                  AND NOT EXISTS (SELECT 1 FROM media_kyb_retention r
                                  WHERE r.media_reference_id=media_reference.id AND r.released_at IS NULL
                                    AND (r.lease_until > now() OR r.retained_until > now()))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .map(MediaReferenceRepository::map).one()));
    }

    /**
     * 与 KYB retention 写入统一锁顺序。锁取得后的下一条 UPDATE 使用 READ COMMITTED 新快照，
     * 能看见等待期间提交的 retention；单条 UPDATE 的固定语句快照做不到这一点。
     */
    private Mono<Object> lockForLifecycle(UUID id) {
        return db.sql("SELECT id FROM media_reference WHERE id=CAST(:id AS uuid) FOR UPDATE")
                .bind("id", id.toString()).map(row -> row.get(0)).one();
    }

    /** deleting→deleted，保留行作删除审计。 */
    public Mono<Boolean> completeDelete(UUID id) {
        return db.sql("""
                UPDATE media_reference
                SET status='deleted', upload_key=NULL, deleted_at=now(), updated_at=now()
                WHERE id=CAST(:id AS uuid) AND status='deleting'
                """)
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /**
     * owner 级配额原子预留：用 {@code media_owner_quota} 的 {@code ON CONFLICT DO UPDATE}
     * 行锁串行化同 owner 的并发预留（MVCC 行锁，可靠），校验通过才递增计数并落 media 行。
     * 两条语句（计数 UPSERT + media INSERT）在同一 data-modifying CTE 中原子执行。
     * 返回 empty=配额已满（调用方应报 429）。
     */
    public Mono<MediaReference> insertIfQuotaAllowed(
            MediaReference ref, long maxObjects, long maxTotalBytes) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                WITH seed AS (
                    SELECT CAST(:ownerAccountId AS text) AS owner_account_id,
                           1 AS object_count,
                           CAST(:sizeBytes AS bigint) AS total_bytes
                    WHERE CAST(:sizeBytes AS bigint) <= CAST(:maxTotalBytes AS bigint)
                ),
                bump AS (
                    INSERT INTO media_owner_quota (owner_account_id, object_count, total_bytes)
                    SELECT owner_account_id, object_count, total_bytes FROM seed
                    ON CONFLICT (owner_account_id)
                    DO UPDATE SET object_count = media_owner_quota.object_count + excluded.object_count,
                                  total_bytes = media_owner_quota.total_bytes + excluded.total_bytes
                    WHERE media_owner_quota.object_count < CAST(:maxObjects AS bigint)
                      AND media_owner_quota.total_bytes + excluded.total_bytes <= CAST(:maxTotalBytes AS bigint)
                    RETURNING owner_account_id
                )
                INSERT INTO media_reference (
                    id, owner_account_id, organization_id, purpose, domain_type, domain_id,
                    object_key, upload_key, mime_type, size_bytes, checksum, source, status, expires_at)
                SELECT CAST(:id AS uuid), :ownerAccountId, :organizationId, :purpose, :domainType, :domainId,
                       :objectKey, :uploadKey, :mimeType, :sizeBytes, :checksum, :source, :status, :expiresAt
                FROM bump
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", ref.id().toString())
                .bind("ownerAccountId", ref.ownerAccountId())
                .bind("purpose", ref.purpose())
                .bind("objectKey", ref.objectKey())
                .bind("mimeType", ref.mimeType())
                .bind("sizeBytes", ref.sizeBytes())
                .bind("source", ref.source())
                .bind("status", ref.status().db())
                .bind("maxObjects", maxObjects)
                .bind("maxTotalBytes", maxTotalBytes);
        spec = bindNullableString(spec, "uploadKey", ref.uploadKey());
        spec = bindNullableString(spec, "organizationId", ref.organizationId());
        spec = bindNullableString(spec, "domainType", ref.domainType());
        spec = bindNullableString(spec, "domainId", ref.domainId());
        spec = bindNullableString(spec, "checksum", ref.checksum());
        spec = bindNullableOffsetDateTime(spec, "expiresAt", ref.expiresAt());
        return spec.map(MediaReferenceRepository::map).one();
    }

    /**
     * 释放 owner 配额（删除/清理时调用）：单条 data-modifying CTE 原子完成「翻转 quota_released 标志」+
     * 「递减计数」。{@code quota_released} 保证每行 exactly-once 释放（GC 对 deleting 行重试不双扣）；
     * 仅对 {@code source='upload'} 的行递减（服务端生成/本地资产从未预留配额，见 {@code insert}）；
     * {@code GREATEST(...,0)} 防下溢。单语句在 autocommit 下原子，避免「标志已翻但计数未减」的永久泄漏。
     */
    public Mono<Void> releaseQuota(UUID id) {
        return db.sql("""
                WITH released AS (
                    UPDATE media_reference
                    SET quota_released = true, updated_at = now()
                    WHERE id = CAST(:id AS uuid) AND quota_released = false
                    RETURNING owner_account_id, size_bytes, source
                )
                UPDATE media_owner_quota
                SET object_count = GREATEST(object_count - 1, 0),
                    total_bytes = GREATEST(total_bytes - (SELECT size_bytes FROM released), 0)
                WHERE owner_account_id = (SELECT owner_account_id FROM released)
                  AND (SELECT source FROM released) = 'upload'
                """)
                .bind("id", id.toString())
                .then();
    }

    /** owner 上传占用（与 media_owner_quota 计数同口径：仅 source='upload' 的 pending/finalizing/active）。 */
    public Mono<OwnerUsage> usageByOwner(String ownerAccountId) {
        return db.sql("""
                SELECT COUNT(*)::bigint AS object_count,
                       COALESCE(SUM(size_bytes), 0)::bigint AS total_bytes
                FROM media_reference
                WHERE owner_account_id=:ownerAccountId
                  AND status IN ('pending', 'finalizing', 'active')
                  AND deleted_at IS NULL
                  AND source = 'upload'
                """)
                .bind("ownerAccountId", ownerAccountId)
                .map(row -> new OwnerUsage(
                        value(row.get("object_count", Long.class), 0L),
                        value(row.get("total_bytes", Long.class), 0L)))
                .one().defaultIfEmpty(new OwnerUsage(0L, 0L));
    }

    /** TTL 巡检：active 按资产 TTL；pending 只按上传票据宽限期，避免短资产 TTL 早于 PUT URL。 */
    public Flux<MediaReference> findCleanupCandidates(Duration pendingGrace) {
        return db.sql("""
                SELECT %s FROM media_reference
                WHERE deleted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM media_kyb_retention r
                                  WHERE r.media_reference_id=media_reference.id AND r.released_at IS NULL
                                    AND (r.lease_until > now() OR r.retained_until > now()))
                  AND ((status='active' AND expires_at < now())
                       OR (status='active' AND purpose='merchant_kyb'
                           AND created_at < now() - (:pendingGraceMillis * interval '1 millisecond'))
                       OR (status='pending' AND created_at < now() - (:pendingGraceMillis * interval '1 millisecond'))
                       OR (status IN ('finalizing', 'deleting')
                           AND updated_at < now() - (:pendingGraceMillis * interval '1 millisecond')))
                """.formatted(SELECT_COLS))
                .bind("pendingGraceMillis", Math.max(pendingGrace.toMillis(), 1L))
                .map(MediaReferenceRepository::map).all();
    }

    /**
     * 门店媒体批量换 URL 的四重过滤查询（#42 D5）：单条 SQL {@code id = ANY(:ids)} +
     * purpose ∧ organization_id ∧ domain_type='store' ∧ domain_id=storeId ∧ active ∧ 未过期，
     * 仅返回通过过滤的子集（不逐项 findById）。过滤失败的 id 直接缺席，调用方据此实现子集语义。
     */
    public Flux<MediaReference> findActiveStoreMedia(
            Collection<UUID> ids, String purpose, String organizationId, String storeId) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("""
                SELECT %s FROM media_reference
                WHERE id = ANY(CAST(:ids AS uuid[]))
                  AND purpose = :purpose
                  AND organization_id = :organizationId
                  AND domain_type = 'store'
                  AND domain_id = :storeId
                  AND status = 'active'
                  AND (expires_at IS NULL OR expires_at > now())
                """.formatted(SELECT_COLS))
                .bind("ids", ids.stream().map(UUID::toString).toArray(String[]::new))
                .bind("purpose", purpose)
                .bind("organizationId", organizationId)
                .bind("storeId", storeId)
                .map(MediaReferenceRepository::map).all();
    }

    public record OwnerUsage(long objectCount, long totalBytes) {}

    private static MediaReference map(Readable row) {
        return new MediaReference(
                UUID.fromString(row.get("id", String.class)),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("purpose", String.class),
                row.get("domain_type", String.class),
                row.get("domain_id", String.class),
                row.get("object_key", String.class),
                row.get("upload_key", String.class),
                row.get("mime_type", String.class),
                value(row.get("size_bytes", Long.class), 0L),
                row.get("checksum", String.class),
                row.get("source", String.class),
                MediaStatus.fromDb(row.get("status", String.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("expires_at", OffsetDateTime.class)),
                toInstant(row.get("deleted_at", OffsetDateTime.class)));
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableOffsetDateTime(
            DatabaseClient.GenericExecuteSpec spec, String name, java.time.Instant value) {
        return value == null
                ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
