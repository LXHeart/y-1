package com.grassland.intelligence.contentlibrary;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 素材授权关系仓储（草场 PRD §4.8 / Slice 14 Stage 2）。表 {@code content_asset_grant} 由 Flyway V18 建。
 *
 * <p>PRD §4.8 商家素材库「商家可以指定哪些素材允许推荐官使用」。镜像 {@code KybMediaRetentionRepository}
 * （V9/V10）的 grant 模式：写授权前 {@code FOR UPDATE} 锁素材行校验归属/library/status，续约用
 * {@code GREATEST} 只前进（防回退），软释放置 {@code released_at}。
 *
 * <p>用法复刻 {@code MediaReferenceRepository}：注入 {@link DatabaseClient}，timestamptz 读为
 * {@link OffsetDateTime} 再转 {@link Instant}，uuid 经 {@code ::text}/{@code CAST}。
 */
@Component
public class ContentAssetGrantRepository {

    /** 默认推荐官授权租约时长（与 KYB LEGACY_LEASE 同口径，可由调用方覆盖）。 */
    public static final Duration DEFAULT_LEASE = Duration.ofDays(3650);

    private final DatabaseClient db;

    public ContentAssetGrantRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 授权（或续约）某素材给推荐官。镜像 KybMediaRetentionRepository.upsertLease：先锁素材行校验
     * library=merchant + 未软删 + active，再 upsert grant（GREATEST 只前进，软释放后可重新激活）。
     * 素材不存在/非商家库/已软删 → empty（controller 转 404/409）。
     */
    public Mono<ContentAssetGrant> grantShare(UUID assetId, String granteeAccountId, String grantedBy,
                                              Duration leaseDuration) {
        long leaseMillis = Math.max(leaseDuration.toMillis(), 1L);
        return db.sql("""
                WITH locked_asset AS MATERIALIZED (
                    SELECT id
                    FROM content_asset
                    WHERE id = CAST(:assetId AS uuid)
                      AND library_type = 'merchant'
                      AND deleted_at IS NULL
                      AND status = 'active'
                    FOR UPDATE
                )
                INSERT INTO content_asset_grant(
                    asset_id, grant_type, grantee_account_id, granted_by, lease_until)
                SELECT id, 'recommender_share', :grantee, :grantedBy,
                       now() + (:leaseMillis * interval '1 millisecond')
                FROM locked_asset
                ON CONFLICT (asset_id, grant_type, grantee_account_id)
                DO UPDATE SET
                    lease_until = GREATEST(content_asset_grant.lease_until, excluded.lease_until),
                    released_at = NULL,
                    granted_by = excluded.granted_by
                RETURNING %s
                """.formatted(RETURNING))
                .bind("assetId", assetId.toString())
                .bind("grantee", granteeAccountId)
                .bind("grantedBy", grantedBy)
                .bind("leaseMillis", leaseMillis)
                .map(ContentAssetGrantRepository::map).one();
    }

    /** 列某素材的全部有效授权（商家管理用）。含已释放的标注（released_at 非空）。 */
    public Flux<ContentAssetGrant> listGrantsForAsset(UUID assetId) {
        return db.sql("SELECT " + RETURNING + " FROM content_asset_grant"
                + " WHERE asset_id=CAST(:assetId AS uuid)"
                + " ORDER BY granted_at ASC")
                .bind("assetId", assetId.toString())
                .map(ContentAssetGrantRepository::map).all();
    }

    /** 撤销授权（软释放）。guarded：仅当 asset + grantee 匹配且未释放时置 released_at。返回是否命中。 */
    public Mono<Boolean> release(UUID assetId, String granteeAccountId) {
        return db.sql("""
                UPDATE content_asset_grant
                SET released_at = now()
                WHERE asset_id = CAST(:assetId AS uuid)
                  AND grant_type = 'recommender_share'
                  AND grantee_account_id = :grantee
                  AND released_at IS NULL
                """)
                .bind("assetId", assetId.toString())
                .bind("grantee", granteeAccountId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /**
     * 列某推荐官被授权的商家素材（推荐官侧列表）。JOIN content_asset 取素材元数据，
     * 仅未释放且租约有效的授权。按授权时间倒序。
     */
    public Flux<ContentAsset> listGrantedAssets(String recommenderAccountId) {
        return listGrantedAssets(recommenderAccountId, null);
    }

    public Flux<ContentAsset> listGrantedAssets(String recommenderAccountId, String query) {
        String search = query == null ? "" : " AND lower(coalesce(a.title,'') || ' ' || a.tags::text)"
                + " LIKE lower(:query) ESCAPE E'\\\\'";
        var spec = db.sql("""
                SELECT %s FROM content_asset a
                JOIN content_asset_grant g ON g.asset_id = a.id
                WHERE g.grant_type = 'recommender_share'
                  AND g.grantee_account_id = :grantee
                  AND g.released_at IS NULL
                  AND (g.lease_until IS NULL OR g.lease_until > now())
                  AND a.deleted_at IS NULL AND a.status = 'active' %s
                ORDER BY g.granted_at DESC
                """.formatted(ContentAssetRepository.SELECT_COLS_PLACEHOLDER, search))
                .bind("grantee", recommenderAccountId);
        if (query != null) spec = spec.bind("query", query);
        return spec.map(ContentAssetGrantRepository::mapAsset).all();
    }

    /** 校验某推荐官对某素材是否有有效授权（下载跨账号读用）。 */
    public Mono<Boolean> isGranted(UUID assetId, String recommenderAccountId) {
        return db.sql("""
                SELECT EXISTS(SELECT 1 FROM content_asset_grant
                  WHERE asset_id = CAST(:assetId AS uuid)
                    AND grant_type = 'recommender_share'
                    AND grantee_account_id = :grantee
                    AND released_at IS NULL
                    AND (lease_until IS NULL OR lease_until > now()))
                """)
                .bind("assetId", assetId.toString())
                .bind("grantee", recommenderAccountId)
                .map(row -> Boolean.TRUE.equals(row.get("exists", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    private static final String RETURNING = """
            asset_id::text, grant_type, grantee_account_id, granted_by, granted_at,
            lease_until, retained_until, released_at
            """;

    private static ContentAssetGrant map(Readable row) {
        return new ContentAssetGrant(
                UUID.fromString(row.get("asset_id", String.class)),
                row.get("grant_type", String.class),
                row.get("grantee_account_id", String.class),
                row.get("granted_by", String.class),
                toInstant(row.get("granted_at", OffsetDateTime.class)),
                toInstant(row.get("lease_until", OffsetDateTime.class)),
                toInstant(row.get("retained_until", OffsetDateTime.class)),
                toInstant(row.get("released_at", OffsetDateTime.class)));
    }

    /** 复用 ContentAssetRepository.map 的字段集，但 SELECT 带表别名前缀 a（JOIN 场景）。 */
    private static ContentAsset mapAsset(Readable row) {
        return new ContentAsset(
                UUID.fromString(row.get("a_id", String.class)),
                UUID.fromString(row.get("a_media_reference_id", String.class)),
                LibraryType.fromRequest(row.get("a_library_type", String.class)),
                AssetCategory.fromRequest(row.get("a_category", String.class)),
                row.get("a_owner_account_id", String.class),
                row.get("a_organization_id", String.class),
                row.get("a_title", String.class),
                ContentAssetRepository.parseTagsStatic(row.get("a_tags", String.class)),
                row.get("a_mime_type", String.class),
                row.get("a_size_bytes", Long.class),
                toInstant(row.get("a_valid_until", OffsetDateTime.class)),
                AssetStatus.fromDb(row.get("a_status", String.class)),
                intValue(row.get("a_version", Integer.class), 1),
                row.get("a_source", String.class),
                row.get("a_license_scope", String.class),
                row.get("a_review_note", String.class),
                row.get("a_reviewed_by", String.class),
                toInstant(row.get("a_reviewed_at", OffsetDateTime.class)),
                toInstant(row.get("a_created_at", OffsetDateTime.class)),
                toInstant(row.get("a_updated_at", OffsetDateTime.class)),
                toInstant(row.get("a_deleted_at", OffsetDateTime.class)),
                row.get("a_store_id", String.class));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int intValue(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
