package com.grassland.identity.store;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * store_profile 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class StoreProfileRepository {

    private static final String SELECT_COLS =
            "store_id::text, address::text, phone, business_hours::text, description, status, "
                    + "submitted_at, reviewed_at, reviewer_account_id::text, review_note, created_at, updated_at";

    private final DatabaseClient db;

    public StoreProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建或更新门店资料（upsert，基于 store_id）。*/
    public Mono<StoreProfile> upsertDraft(String organizationId, String storeId, String address, String phone,
                                         String businessHours, String description) {
        var spec = db.sql("""
                INSERT INTO store_profile(store_id, address, phone, business_hours, description, status)
                SELECT s.id, CAST(:addr AS jsonb), :phone, CAST(:hours AS jsonb), :desc, 'draft'
                FROM store s
                WHERE s.id = CAST(:id AS uuid) AND s.organization_id = CAST(:org AS uuid)
                ON CONFLICT (store_id) DO UPDATE SET
                    address = EXCLUDED.address,
                    phone = EXCLUDED.phone,
                    business_hours = EXCLUDED.business_hours,
                    description = EXCLUDED.description,
                    status = 'draft',
                    submitted_at = NULL,
                    reviewed_at = NULL,
                    reviewer_account_id = NULL,
                    review_note = NULL,
                    updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", storeId)
                .bind("org", organizationId);
        spec = bindNullable(spec, "addr", address);
        spec = bindNullable(spec, "phone", phone);
        spec = bindNullable(spec, "hours", businessHours);
        spec = bindNullable(spec, "desc", description);
        return spec.map(StoreProfileRepository::map).one();
    }

    /** 查询门店资料。*/
    public Mono<StoreProfile> findByOrganizationAndId(String organizationId, String storeId) {
        return db.sql("""
                SELECT sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                       sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                       sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sp.store_id = CAST(:id AS uuid)
                """)
                .bind("org", organizationId)
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    /** 按组织和门店锁定资料行，串行化编辑、提交与审核。 */
    public Mono<StoreProfile> findByOrganizationAndIdForUpdate(String organizationId, String storeId) {
        return db.sql("""
                SELECT sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                       sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                       sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sp.store_id = CAST(:id AS uuid)
                FOR UPDATE OF sp
                """)
                .bind("org", organizationId)
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    public Mono<StoreProfile> submit(String organizationId, String storeId, Instant submittedAt) {
        return db.sql("""
                UPDATE store_profile sp
                SET status = 'pending', submitted_at = :submitted,
                    reviewed_at = NULL, reviewer_account_id = NULL, review_note = NULL, updated_at = now()
                FROM store s
                WHERE sp.store_id = s.id AND s.organization_id = CAST(:org AS uuid)
                  AND sp.store_id = CAST(:id AS uuid) AND sp.status IN ('draft', 'rejected')
                RETURNING sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                          sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                          sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                """)
                .bind("org", organizationId).bind("id", storeId)
                .bind("submitted", submittedAt.atOffset(ZoneOffset.UTC))
                .map(StoreProfileRepository::map).one();
    }

    public Mono<StoreProfile> review(String organizationId, String storeId, String status,
                                     Instant reviewedAt, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE store_profile sp
                SET status = :status, reviewed_at = :reviewed,
                    reviewer_account_id = CAST(:reviewer AS uuid), review_note = :note, updated_at = now()
                FROM store s
                WHERE sp.store_id = s.id AND s.organization_id = CAST(:org AS uuid)
                  AND sp.store_id = CAST(:id AS uuid) AND sp.status IN ('pending', 'under_review')
                RETURNING sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                          sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                          sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                """)
                .bind("org", organizationId).bind("id", storeId).bind("status", status)
                .bind("reviewed", reviewedAt.atOffset(ZoneOffset.UTC)).bind("reviewer", reviewerAccountId);
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(StoreProfileRepository::map).one();
    }

    /** 按组织和门店双重作用域停用已有资料，保留必填地址与历史内容。 */
    public Mono<StoreProfile> deactivate(String organizationId, String storeId) {
        return db.sql("""
                UPDATE store_profile sp
                SET status = 'inactive', updated_at = now()
                FROM store s
                WHERE sp.store_id = s.id
                  AND s.organization_id = CAST(:org AS uuid)
                  AND sp.store_id = CAST(:id AS uuid)
                RETURNING sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                          sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                          sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                """)
                .bind("org", organizationId)
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    /** 列出组织下所有门店资料。*/
    public Flux<StoreProfile> findByOrganization(String organizationId) {
        return db.sql("""
                SELECT sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text,
                       sp.description, sp.status, sp.submitted_at, sp.reviewed_at,
                       sp.reviewer_account_id::text, sp.review_note, sp.created_at, sp.updated_at
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) ORDER BY sp.created_at
                """)
                .bind("org", organizationId)
                .map(StoreProfileRepository::map).all();
    }

    private static StoreProfile map(Readable row) {
        return new StoreProfile(
                row.get("store_id", String.class),
                row.get("address", String.class),
                row.get("phone", String.class),
                row.get("business_hours", String.class),
                row.get("description", String.class),
                row.get("status", String.class),
                toInstant(row.get("submitted_at", OffsetDateTime.class)),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
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

}
