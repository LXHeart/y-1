package com.grassland.intelligence.contentlibrary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 素材条目仓储（草场 PRD §4.8 / Slice 14）。表 {@code content_asset}（可变当前行）+
 * {@code content_asset_version}（不可变历史快照）由 Flyway V18 建。
 *
 * <p>用法复刻 {@code MediaReferenceRepository} / {@code StylePreferencesRepository}：注入 {@link DatabaseClient}，
 * timestamptz 读为 {@link OffsetDateTime} 再转 {@link Instant}，uuid 经 {@code ::text}/{@code CAST}，
 * 可空字段用 {@code bindNull}，jsonb 写 {@code CAST(:json AS jsonb)} 读 {@code ::text}。
 * ObjectMapper 持 service-local 实例（不注入 bean——见 {@code StylePreferencesRepository} 同口径）。
 */
@Component
public class ContentAssetRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** SELECT 列（无表别名，单表查询用）。 */
    static final String SELECT_COLS = """
            id::text, media_reference_id::text, library_type, category, owner_account_id, organization_id,
            title, tags::text, mime_type, size_bytes, valid_until, status, version, source, license_scope,
            review_note, reviewed_by, reviewed_at, created_at, updated_at, deleted_at
            """;

    /**
     * SELECT 列（带 {@code a.} 表别名 + {@code a_} 输出别名，JOIN 场景用，如
     * {@code ContentAssetGrantRepository.listGrantedAssets}）。列名与 {@link #map(Readable)} 对齐。
     */
    static final String SELECT_COLS_PLACEHOLDER = """
            a.id::text AS a_id, a.media_reference_id::text AS a_media_reference_id,
            a.library_type AS a_library_type, a.category AS a_category,
            a.owner_account_id AS a_owner_account_id, a.organization_id AS a_organization_id,
            a.title AS a_title, a.tags::text AS a_tags, a.mime_type AS a_mime_type,
            a.size_bytes AS a_size_bytes, a.valid_until AS a_valid_until, a.status AS a_status,
            a.version AS a_version, a.source AS a_source, a.license_scope AS a_license_scope,
            a.review_note AS a_review_note, a.reviewed_by AS a_reviewed_by,
            a.reviewed_at AS a_reviewed_at, a.created_at AS a_created_at,
            a.updated_at AS a_updated_at, a.deleted_at AS a_deleted_at
            """;

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public ContentAssetRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建素材条目（个人/商家库默认 active；公共库可传 pending_review/draft）。 */
    public Mono<ContentAsset> create(ContentAsset asset) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO content_asset (
                    id, media_reference_id, library_type, category, owner_account_id, organization_id,
                    title, tags, mime_type, size_bytes, valid_until, status, version, source, license_scope)
                VALUES (
                    CAST(:id AS uuid), CAST(:mediaReferenceId AS uuid), :libraryType, :category,
                    :ownerAccountId, :organizationId, :title, CAST(:tags AS jsonb),
                    :mimeType, :sizeBytes, :validUntil, :status, 1, :source, :licenseScope)
                """)
                .bind("id", asset.id().toString())
                .bind("mediaReferenceId", asset.mediaReferenceId().toString())
                .bind("libraryType", asset.libraryType().db())
                .bind("category", asset.category().db())
                .bind("ownerAccountId", asset.ownerAccountId())
                .bind("title", asset.title())
                .bind("status", asset.status().db());
        spec = bindNullableString(spec, "organizationId", asset.organizationId());
        spec = bindNullableString(spec, "mimeType", asset.mimeType());
        spec = bindNullableLong(spec, "sizeBytes", asset.sizeBytes());
        spec = bindNullableOffsetDateTime(spec, "validUntil", asset.validUntil());
        spec = bindNullableString(spec, "source", asset.source());
        spec = bindNullableString(spec, "licenseScope", asset.licenseScope());
        spec = bindTags(spec, asset.tags());
        return spec.then().then(findById(asset.id()));
    }

    public Mono<ContentAsset> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM content_asset WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(ContentAssetRepository::map).one();
    }

    /**
     * 列个人库素材（owner 维度，仅 active 未软删）。按创建时间倒序。
     * 商家库/公共库的列表查询走各自专用方法（Stage 2/3）。
     */
    public Flux<ContentAsset> listPersonal(String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM content_asset"
                + " WHERE owner_account_id=:ownerAccountId AND library_type='personal'"
                + " AND deleted_at IS NULL AND status='active'"
                + " ORDER BY created_at DESC")
                .bind("ownerAccountId", ownerAccountId)
                .map(ContentAssetRepository::map).all();
    }

    /**
     * 列商家库素材（org 维度，仅 active 未软删）。按创建时间倒序，可按分类筛选。
     * 商家成员查本 org 全部素材（admin/member 粒度首期不分，断言 org 已经 membership 校验）。
     */
    public Flux<ContentAsset> listMerchantByOrg(String organizationId, AssetCategory category) {
        String sql = "SELECT " + SELECT_COLS + " FROM content_asset"
                + " WHERE organization_id=:organizationId AND library_type='merchant'"
                + " AND deleted_at IS NULL AND status='active'"
                + (category != null ? " AND category=:category" : "")
                + " ORDER BY created_at DESC";
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("organizationId", organizationId);
        if (category != null) {
            spec = spec.bind("category", category.db());
        }
        return spec.map(ContentAssetRepository::map).all();
    }

    /** 详情查询带 org 归属校验（商家库用：跨 org 一律 empty，controller 转 404）。 */
    public Mono<ContentAsset> findByIdInOrg(UUID id, String organizationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM content_asset"
                + " WHERE id=CAST(:id AS uuid) AND organization_id=:organizationId"
                + " AND library_type='merchant' AND deleted_at IS NULL")
                .bind("id", id.toString())
                .bind("organizationId", organizationId)
                .map(ContentAssetRepository::map).one();
    }

    /**
     * 编辑素材（落新 version 快照）。guarded：仅当 id 匹配 + expectedVersion 匹配 + 未软删时更新。
     * 同事务链里先 appendVersion 再 update（由 controller 用 TransactionalOperator 串）。
     * 返回更新后的行；version 不匹配/不存在 → empty（controller 转 409）。
     */
    public Mono<ContentAsset> update(UUID id, int expectedVersion, String title, List<String> tags,
                                      Instant validUntil, AssetCategory category) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                UPDATE content_asset SET
                    title=:title, tags=CAST(:tags AS jsonb), valid_until=:validUntil, category=:category,
                    version=version+1, updated_at=now()
                WHERE id=CAST(:id AS uuid) AND version=:expectedVersion AND deleted_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .bind("expectedVersion", expectedVersion)
                .bind("title", title)
                .bind("category", category.db());
        spec = bindTags(spec, tags);
        spec = bindNullableOffsetDateTime(spec, "validUntil", validUntil);
        return spec.map(ContentAssetRepository::map).one();
    }

    /** 软删素材。guarded：仅当 id 匹配 + 未软删时置 deleted_at。返回是否命中。 */
    public Mono<Boolean> softDelete(UUID id) {
        return db.sql("""
                UPDATE content_asset SET deleted_at=now(), status='expired', updated_at=now()
                WHERE id=CAST(:id AS uuid) AND deleted_at IS NULL
                """)
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /**
     * 落不可变历史快照（镜像 task_version / platform_model_config_history）。每次编辑前调用，
     * 把当前行整行镜像到 content_asset_version。返回 void，与 update 同事务。
     */
    public Mono<Void> appendVersion(ContentAsset asset, String editedBy) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO content_asset_version (
                    asset_id, version, library_type, category, owner_account_id, organization_id,
                    title, tags, mime_type, size_bytes, valid_until, source, license_scope, snapshotted_by)
                VALUES (
                    CAST(:assetId AS uuid), :version, :libraryType, :category, :ownerAccountId, :organizationId,
                    :title, CAST(:tags AS jsonb), :mimeType, :sizeBytes, :validUntil, :source, :licenseScope, :snapshottedBy)
                """)
                .bind("assetId", asset.id().toString())
                .bind("version", asset.version())
                .bind("libraryType", asset.libraryType().db())
                .bind("category", asset.category().db())
                .bind("ownerAccountId", asset.ownerAccountId())
                .bind("title", asset.title())
                .bind("snapshottedBy", editedBy);
        spec = bindNullableString(spec, "organizationId", asset.organizationId());
        spec = bindNullableString(spec, "mimeType", asset.mimeType());
        spec = bindNullableLong(spec, "sizeBytes", asset.sizeBytes());
        spec = bindNullableOffsetDateTime(spec, "validUntil", asset.validUntil());
        spec = bindNullableString(spec, "source", asset.source());
        spec = bindNullableString(spec, "licenseScope", asset.licenseScope());
        spec = bindTags(spec, asset.tags());
        return spec.then();
    }

    /** 列某素材的全部历史快照（按版本正序）。 */
    public Flux<ContentAssetVersion> listVersions(UUID assetId) {
        return db.sql("""
                SELECT asset_id::text, version, library_type, category, owner_account_id, organization_id,
                       title, tags::text, mime_type, size_bytes, valid_until, source, license_scope,
                       snapshotted_at, snapshotted_by
                FROM content_asset_version
                WHERE asset_id=CAST(:assetId AS uuid)
                ORDER BY version ASC
                """)
                .bind("assetId", assetId.toString())
                .map(ContentAssetRepository::mapVersion).all();
    }

    private static ContentAsset map(Readable row) {
        return new ContentAsset(
                UUID.fromString(row.get("id", String.class)),
                UUID.fromString(row.get("media_reference_id", String.class)),
                LibraryType.fromRequest(row.get("library_type", String.class)),
                AssetCategory.fromRequest(row.get("category", String.class)),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("title", String.class),
                parseTagsStatic(row.get("tags", String.class)),
                row.get("mime_type", String.class),
                row.get("size_bytes", Long.class),
                toInstant(row.get("valid_until", OffsetDateTime.class)),
                AssetStatus.fromDb(row.get("status", String.class)),
                value(row.get("version", Integer.class), 1),
                row.get("source", String.class),
                row.get("license_scope", String.class),
                row.get("review_note", String.class),
                row.get("reviewed_by", String.class),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                toInstant(row.get("deleted_at", OffsetDateTime.class)));
    }

    private static ContentAssetVersion mapVersion(Readable row) {
        return new ContentAssetVersion(
                UUID.fromString(row.get("asset_id", String.class)),
                value(row.get("version", Integer.class), 1),
                LibraryType.fromRequest(row.get("library_type", String.class)),
                AssetCategory.fromRequest(row.get("category", String.class)),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("title", String.class),
                parseTagsStatic(row.get("tags", String.class)),
                row.get("mime_type", String.class),
                row.get("size_bytes", Long.class),
                toInstant(row.get("valid_until", OffsetDateTime.class)),
                row.get("source", String.class),
                row.get("license_scope", String.class),
                toInstant(row.get("snapshotted_at", OffsetDateTime.class)),
                row.get("snapshotted_by", String.class));
    }

    /** 解析 tags jsonb 为 List（跨包复用：ContentAssetGrantRepository JOIN 读 asset 也用）。 */
    public static List<String> parseTagsStatic(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = new ObjectMapper().readValue(json, STRING_LIST);
            List<String> filtered = new ArrayList<>();
            for (String value : raw) {
                if (value != null && !value.trim().isEmpty()) {
                    filtered.add(value.trim());
                }
            }
            return List.copyOf(filtered);
        } catch (Exception e) {
            return List.of();
        }
    }

    private DatabaseClient.GenericExecuteSpec bindTags(
            DatabaseClient.GenericExecuteSpec spec, List<String> tags) {
        List<String> safe = tags == null ? List.of() : tags;
        try {
            return spec.bind("tags", mapper.writeValueAsString(safe));
        } catch (Exception e) {
            return spec.bind("tags", "[]");
        }
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableLong(
            DatabaseClient.GenericExecuteSpec spec, String name, Long value) {
        return value == null ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableOffsetDateTime(
            DatabaseClient.GenericExecuteSpec spec, String name, Instant value) {
        return value == null
                ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
