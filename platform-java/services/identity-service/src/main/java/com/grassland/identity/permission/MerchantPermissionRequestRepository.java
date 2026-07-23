package com.grassland.identity.permission;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * merchant_permission_request 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与组织/成员域风格一致）。
 * Slice 2L：create 带 industry + review_deadline；新增 createAppeal（申诉引用原 rejected 申请）。
 */
@Component
public class MerchantPermissionRequestRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, requester_account_id::text, requested_tier, materials::text, status,"
                    + " reviewer_account_id::text, review_note, industry, review_deadline,"
                    + " original_request_id::text, appeal_note, created_at, updated_at";

    private final DatabaseClient db;

    public MerchantPermissionRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建申请（status=pending；带行业快照 + SLA 截止）。materials 为 JSON 串，可空。 */
    public Mono<MerchantPermissionRequest> create(String organizationId, String requesterAccountId,
                                                  String requestedTier, String materials,
                                                  String industry, Instant reviewDeadline) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO merchant_permission_request(id, organization_id, requester_account_id, requested_tier,
                        materials, status, industry, review_deadline)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :tier,
                        CAST(:materials AS json), 'pending', :industry, :deadline)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId)
                .bind("tier", requestedTier).bind("industry", industry);
        spec = bindNullableJson(spec, "materials", materials);
        spec = bindNullableDeadline(spec, "deadline", reviewDeadline);
        return spec.map(MerchantPermissionRequestRepository::map).one();
    }

    /** 申诉：新建 pending 申请，引用原 rejected 申请（original_request_id）+ 申诉说明。 */
    public Mono<MerchantPermissionRequest> createAppeal(String organizationId, String requesterAccountId,
                                                        String requestedTier, String materials,
                                                        String industry, Instant reviewDeadline,
                                                        String originalRequestId, String appealNote) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO merchant_permission_request(id, organization_id, requester_account_id, requested_tier,
                        materials, status, industry, review_deadline, original_request_id, appeal_note)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :tier,
                        CAST(:materials AS json), 'pending', :industry, :deadline,
                        CAST(:original AS uuid), :appealNote)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId)
                .bind("tier", requestedTier).bind("industry", industry)
                .bind("original", originalRequestId);
        spec = bindNullableJson(spec, "materials", materials);
        spec = bindNullableDeadline(spec, "deadline", reviewDeadline);
        spec = bindNullable(spec, "appealNote", appealNote);
        return spec.map(MerchantPermissionRequestRepository::map).one();
    }

    public Mono<MerchantPermissionRequest> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_permission_request WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(MerchantPermissionRequestRepository::map).one();
    }

    public Flux<MerchantPermissionRequest> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_permission_request WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at")
                .bind("org", organizationId)
                .map(MerchantPermissionRequestRepository::map).all();
    }

    /** admin 审核队列：列 pending。 */
    public Flux<MerchantPermissionRequest> findPending() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_permission_request WHERE status = 'pending' ORDER BY created_at")
                .map(MerchantPermissionRequestRepository::map).all();
    }

    /** 更新审核结果（status + reviewer + note）；返回更新后的行。note 可空。 */
    public Mono<MerchantPermissionRequest> updateStatus(String id, String status, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE merchant_permission_request
                SET status = :status, reviewer_account_id = CAST(:reviewer AS uuid),
                    review_note = :note, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("status", status).bind("reviewer", reviewerAccountId);
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(MerchantPermissionRequestRepository::map).one();
    }

    private static MerchantPermissionRequest map(Readable row) {
        return new MerchantPermissionRequest(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("requester_account_id", String.class),
                row.get("requested_tier", String.class),
                row.get("materials", String.class),
                row.get("status", String.class),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                row.get("industry", String.class),
                toInstant(row.get("review_deadline", OffsetDateTime.class)),
                row.get("original_request_id", String.class),
                row.get("appeal_note", String.class),
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

    private static GenericExecuteSpec bindNullableJson(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableDeadline(GenericExecuteSpec spec, String name, Instant value) {
        if (value == null) {
            return spec.bindNull(name, OffsetDateTime.class);
        }
        return spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
