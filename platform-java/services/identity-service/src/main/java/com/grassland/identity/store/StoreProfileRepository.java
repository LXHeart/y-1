package com.grassland.identity.store;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
            "store_id::text, address::text, phone, business_hours::text, description, "
                    + "categories, signature_items, selling_points, must_emphasize, forbidden_phrases, "
                    + "allowed_tags, brand_tone, price_range, average_spend_cents, visit_notes, status, "
                    + "submitted_at, reviewed_at, reviewer_account_id::text, review_note, created_at, updated_at";

    /** 同 {@link #SELECT_COLS}，带 {@code sp.} 前缀（join 查询/RETURNING 用）。 */
    private static final String SELECT_COLS_SP =
            "sp.store_id::text, sp.address::text, sp.phone, sp.business_hours::text, sp.description, "
                    + "sp.categories, sp.signature_items, sp.selling_points, sp.must_emphasize, sp.forbidden_phrases, "
                    + "sp.allowed_tags, sp.brand_tone, sp.price_range, sp.average_spend_cents, sp.visit_notes, "
                    + "sp.status, sp.submitted_at, sp.reviewed_at, sp.reviewer_account_id::text, sp.review_note, "
                    + "sp.created_at, sp.updated_at";

    private final DatabaseClient db;

    public StoreProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建或更新门店资料（upsert，基于 store_id）。任务书 #24：营销字段整份覆盖，空数组/null 即清空；
     *  V22 KYB 审核语义不变（编辑重置 draft、清审核事实）。 */
    public Mono<StoreProfile> upsertDraft(String organizationId, String storeId, StoreProfileDraft draft) {
        var spec = db.sql("""
                INSERT INTO store_profile(store_id, address, phone, business_hours, description,
                    categories, signature_items, selling_points, must_emphasize, forbidden_phrases,
                    allowed_tags, brand_tone, price_range, average_spend_cents, visit_notes, status)
                SELECT s.id, CAST(:addr AS jsonb), :phone, CAST(:hours AS jsonb), :desc,
                    :categories, :signatureItems, :sellingPoints, :mustEmphasize, :forbiddenPhrases,
                    :allowedTags, :brandTone, :priceRange, :avgSpend, :visitNotes, 'draft'
                FROM store s
                WHERE s.id = CAST(:id AS uuid) AND s.organization_id = CAST(:org AS uuid)
                ON CONFLICT (store_id) DO UPDATE SET
                    address = EXCLUDED.address,
                    phone = EXCLUDED.phone,
                    business_hours = EXCLUDED.business_hours,
                    description = EXCLUDED.description,
                    categories = EXCLUDED.categories,
                    signature_items = EXCLUDED.signature_items,
                    selling_points = EXCLUDED.selling_points,
                    must_emphasize = EXCLUDED.must_emphasize,
                    forbidden_phrases = EXCLUDED.forbidden_phrases,
                    allowed_tags = EXCLUDED.allowed_tags,
                    brand_tone = EXCLUDED.brand_tone,
                    price_range = EXCLUDED.price_range,
                    average_spend_cents = EXCLUDED.average_spend_cents,
                    visit_notes = EXCLUDED.visit_notes,
                    status = 'draft',
                    submitted_at = NULL,
                    reviewed_at = NULL,
                    reviewer_account_id = NULL,
                    review_note = NULL,
                    updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", storeId)
                .bind("org", organizationId)
                .bind("categories", draft.categories().toArray(String[]::new))
                .bind("signatureItems", draft.signatureItems().toArray(String[]::new))
                .bind("sellingPoints", draft.sellingPoints().toArray(String[]::new))
                .bind("mustEmphasize", draft.mustEmphasize().toArray(String[]::new))
                .bind("forbiddenPhrases", draft.forbiddenPhrases().toArray(String[]::new))
                .bind("allowedTags", draft.allowedTags().toArray(String[]::new));
        spec = bindNullable(spec, "addr", draft.address());
        spec = bindNullable(spec, "phone", draft.phone());
        spec = bindNullable(spec, "hours", draft.businessHours());
        spec = bindNullable(spec, "desc", draft.description());
        spec = bindNullable(spec, "brandTone", draft.brandTone());
        spec = bindNullable(spec, "priceRange", draft.priceRange());
        spec = bindNullable(spec, "visitNotes", draft.visitNotes());
        spec = draft.averageSpendCents() == null
                ? spec.bindNull("avgSpend", Integer.class)
                : spec.bind("avgSpend", draft.averageSpendCents());
        return spec.map(StoreProfileRepository::map).one();
    }

    /** 查询门店资料。*/
    public Mono<StoreProfile> findByOrganizationAndId(String organizationId, String storeId) {
        return db.sql("""
                SELECT %s
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sp.store_id = CAST(:id AS uuid)
                """.formatted(SELECT_COLS_SP))
                .bind("org", organizationId)
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    /** 按组织和门店锁定资料行，串行化编辑、提交与审核。 */
    public Mono<StoreProfile> findByOrganizationAndIdForUpdate(String organizationId, String storeId) {
        return db.sql("""
                SELECT %s
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sp.store_id = CAST(:id AS uuid)
                FOR UPDATE OF sp
                """.formatted(SELECT_COLS_SP))
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
                RETURNING %s
                """.formatted(SELECT_COLS_SP))
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
                RETURNING %s
                """.formatted(SELECT_COLS_SP))
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
                RETURNING %s
                """.formatted(SELECT_COLS_SP))
                .bind("org", organizationId)
                .bind("id", storeId)
                .map(StoreProfileRepository::map).one();
    }

    /** 列出组织下所有门店资料。*/
    public Flux<StoreProfile> findByOrganization(String organizationId) {
        return db.sql("""
                SELECT %s
                FROM store_profile sp
                INNER JOIN store s ON s.id = sp.store_id
                WHERE s.organization_id = CAST(:org AS uuid) ORDER BY sp.created_at
                """.formatted(SELECT_COLS_SP))
                .bind("org", organizationId)
                .map(StoreProfileRepository::map).all();
    }

    /** Returns stores with valid profile coordinates inside the requested radius. */
    public Flux<NearbyStore> findNearby(double latitude, double longitude, double radiusKm) {
        return db.sql("""
                WITH coordinates AS (
                    SELECT store_id::text,
                           (address->>'latitude')::double precision AS latitude,
                           (address->>'longitude')::double precision AS longitude
                    FROM store_profile
                    WHERE jsonb_typeof(address) = 'object'
                      AND address->>'latitude' ~ '^-?[0-9]+([.][0-9]+)?$'
                      AND address->>'longitude' ~ '^-?[0-9]+([.][0-9]+)?$'
                      AND (address->>'latitude')::double precision BETWEEN -90 AND 90
                      AND (address->>'longitude')::double precision BETWEEN -180 AND 180
                ), distances AS (
                    SELECT store_id, latitude, longitude,
                           6371.0088 * acos(least(1.0, greatest(-1.0,
                               sin(radians(:latitude)) * sin(radians(latitude))
                               + cos(radians(:latitude)) * cos(radians(latitude))
                               * cos(radians(longitude) - radians(:longitude))))) AS distance_km
                    FROM coordinates
                )
                SELECT store_id, latitude, longitude, distance_km
                FROM distances WHERE distance_km <= :radiusKm
                ORDER BY distance_km, store_id
                """)
                .bind("latitude", latitude)
                .bind("longitude", longitude)
                .bind("radiusKm", radiusKm)
                .map(row -> new NearbyStore(
                        row.get("store_id", String.class),
                        value(row.get("latitude", Double.class)),
                        value(row.get("longitude", Double.class)),
                        value(row.get("distance_km", Double.class))))
                .all();
    }

    // ---- 任务书 #24 Stage 2：门店公开资料白名单读（不含 KYB 审核列/组织内部字段） ----

    private static final String PUBLIC_COLS =
            "s.id::text AS store_id, s.name AS store_name, sp.address::text AS address, sp.phone, "
                    + "sp.business_hours::text AS business_hours, sp.description, sp.categories, "
                    + "sp.signature_items, sp.selling_points, sp.must_emphasize, sp.forbidden_phrases, "
                    + "sp.allowed_tags, sp.brand_tone, sp.price_range, sp.average_spend_cents, sp.visit_notes";

    /**
     * 单店公开资料。前置条件：门店 active 且所属组织 active（非 suspended），无资料行/不满足 → 空
     * （controller 层 404）。不含任何 KYB 审核列。
     */
    public Mono<StorePublicProfile> findPublicProfile(String storeId) {
        return db.sql("""
                SELECT %s
                FROM store s
                INNER JOIN store_profile sp ON sp.store_id = s.id
                INNER JOIN organization o ON o.id = s.organization_id
                WHERE s.id = CAST(:id AS uuid) AND s.status = 'active' AND o.status = 'active' AND s.deleted_at IS NULL
                """.formatted(PUBLIC_COLS))
                .bind("id", storeId)
                .map(StoreProfileRepository::mapPublic).one();
    }

    /**
     * 批量公开资料（feed enrichment 用，一次拉整页 storeId）。LEFT JOIN 资料表：无资料的门店
     * 仍回 storeName（营销字段空）。同样限定门店/组织 active。
     */
    public Flux<StorePublicProfile> findPublicProfiles(Collection<String> storeIds) {
        if (storeIds.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("""
                SELECT %s
                FROM store s
                LEFT JOIN store_profile sp ON sp.store_id = s.id
                INNER JOIN organization o ON o.id = s.organization_id
                WHERE s.id = ANY(CAST(:ids AS uuid[])) AND s.status = 'active' AND o.status = 'active' AND s.deleted_at IS NULL
                ORDER BY s.id
                """.formatted(PUBLIC_COLS))
                .bind("ids", storeIds.toArray(String[]::new))
                .map(StoreProfileRepository::mapPublic).all();
    }

    private static StorePublicProfile mapPublic(Readable row) {
        return new StorePublicProfile(
                row.get("store_id", String.class),
                row.get("store_name", String.class),
                row.get("address", String.class),
                row.get("phone", String.class),
                row.get("business_hours", String.class),
                row.get("description", String.class),
                toList(row.get("categories", String[].class)),
                toList(row.get("signature_items", String[].class)),
                row.get("price_range", String.class),
                row.get("average_spend_cents", Integer.class),
                row.get("visit_notes", String.class),
                toList(row.get("selling_points", String[].class)),
                row.get("brand_tone", String.class),
                toList(row.get("must_emphasize", String[].class)),
                toList(row.get("forbidden_phrases", String[].class)),
                toList(row.get("allowed_tags", String[].class))
        );
    }

    private static StoreProfile map(Readable row) {
        return new StoreProfile(
                row.get("store_id", String.class),
                row.get("address", String.class),
                row.get("phone", String.class),
                row.get("business_hours", String.class),
                row.get("description", String.class),
                toList(row.get("categories", String[].class)),
                toList(row.get("signature_items", String[].class)),
                toList(row.get("selling_points", String[].class)),
                toList(row.get("must_emphasize", String[].class)),
                toList(row.get("forbidden_phrases", String[].class)),
                toList(row.get("allowed_tags", String[].class)),
                row.get("brand_tone", String.class),
                row.get("price_range", String.class),
                row.get("average_spend_cents", Integer.class),
                row.get("visit_notes", String.class),
                row.get("status", String.class),
                toInstant(row.get("submitted_at", OffsetDateTime.class)),
                toInstant(row.get("reviewed_at", OffsetDateTime.class)),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    /** text[] 读出：null 与空白项滤掉，统一成不可变列表（同 recommender_profile 惯例）。 */
    private static List<String> toList(String[] values) {
        return values == null ? List.of()
                : Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static double value(Double value) {
        return value == null ? 0d : value;
    }

    public record NearbyStore(String storeId, double latitude, double longitude, double distanceKm) {}

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

}
