package com.grassland.identity.brand;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * organization_brand_profile 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 KYB/权限域风格一致）。#32。
 *
 * <p>乐观锁照 V31 merchant_permission_request 的 version CAS 路线：{@link #save} 行存在走
 * {@code UPDATE ... WHERE organization_id = :org AND version = :expected}，0 行（版本过期）返回 empty；
 * 行不存在且 expectedVersion==0 走 {@code INSERT ... ON CONFLICT DO NOTHING}，冲突（并发首建）返回 empty。
 * empty 一律由调用方转 409。仓储自身不开事务（与 identity 现有 controller 惯例一致，
 * 由调用方按需包裹；INSERT 的 ON CONFLICT DO NOTHING 已兜底首建竞态）。
 */
@Component
public class OrganizationBrandProfileRepository {

    private static final String SELECT_COLS =
            "organization_id::text, brand_name, brand_logo_media_reference_id::text, description, industry,"
                    + " version, created_at, updated_at";

    private final DatabaseClient db;

    public OrganizationBrandProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 查询组织品牌资料；无行返回 empty（调用方按 D3 回 version=0 空资料）。 */
    public Mono<OrganizationBrandProfile> find(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM organization_brand_profile WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", organizationId)
                .map(OrganizationBrandProfileRepository::map).one();
    }

    /**
     * 保存品牌资料（PUT-upsert，#32 D3）。任一分支 0 行 → empty（调用方转 409）：
     * 行存在走 version CAS 的 {@code UPDATE}（版本过期即 empty）；行不存在且 expectedVersion==0
     * 走 {@code INSERT ON CONFLICT DO NOTHING}（并发首建冲突即 empty）；expectedVersion!=0 时
     * 不尝试插入，直接 empty（首次创建必须期望版本 0）。
     */
    public Mono<OrganizationBrandProfile> save(String organizationId, String brandName,
                                               String logoMediaReferenceId, String description,
                                               String industry, int expectedVersion) {
        return updateExisting(organizationId, brandName, logoMediaReferenceId, description, industry, expectedVersion)
                .switchIfEmpty(Mono.defer(() -> expectedVersion == 0
                        ? insertIfAbsent(organizationId, brandName, logoMediaReferenceId, description, industry)
                        : Mono.empty()));
    }

    private Mono<OrganizationBrandProfile> updateExisting(String organizationId, String brandName,
                                                          String logoMediaReferenceId, String description,
                                                          String industry, int expectedVersion) {
        var spec = db.sql("""
                UPDATE organization_brand_profile
                SET brand_name = :brandName, brand_logo_media_reference_id = :logo,
                    description = :description, industry = :industry,
                    version = version + 1, updated_at = now()
                WHERE organization_id = CAST(:org AS uuid) AND version = :expectedVersion
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("org", organizationId).bind("expectedVersion", expectedVersion);
        spec = bindNullable(spec, "brandName", brandName);
        spec = bindNullableUuid(spec, "logo", logoMediaReferenceId);
        spec = bindNullable(spec, "description", description);
        spec = bindNullable(spec, "industry", industry);
        return spec.map(OrganizationBrandProfileRepository::map).one();
    }

    private Mono<OrganizationBrandProfile> insertIfAbsent(String organizationId, String brandName,
                                                          String logoMediaReferenceId, String description,
                                                          String industry) {
        var spec = db.sql("""
                INSERT INTO organization_brand_profile(organization_id, brand_name, brand_logo_media_reference_id,
                        description, industry)
                VALUES (CAST(:org AS uuid), :brandName, :logo, :description, :industry)
                ON CONFLICT (organization_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("org", organizationId);
        spec = bindNullable(spec, "brandName", brandName);
        spec = bindNullableUuid(spec, "logo", logoMediaReferenceId);
        spec = bindNullable(spec, "description", description);
        spec = bindNullable(spec, "industry", industry);
        return spec.map(OrganizationBrandProfileRepository::map).one();
    }

    private static OrganizationBrandProfile map(Readable row) {
        return new OrganizationBrandProfile(
                row.get("organization_id", String.class),
                row.get("brand_name", String.class),
                row.get("brand_logo_media_reference_id", String.class),
                row.get("description", String.class),
                row.get("industry", String.class),
                row.get("version", Integer.class) == null ? 0 : row.get("version", Integer.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableUuid(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, UUID.class) : spec.bind(name, UUID.fromString(value));
    }
}
