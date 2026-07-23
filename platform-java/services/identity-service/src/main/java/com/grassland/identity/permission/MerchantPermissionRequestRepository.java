package com.grassland.identity.permission;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * merchant_permission_request 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与组织/成员域风格一致）。
 */
@Component
public class MerchantPermissionRequestRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, requester_account_id::text, requested_tier, materials::text, status,"
                    + " reviewer_account_id::text, review_note, created_at, updated_at";

    private final DatabaseClient db;

    public MerchantPermissionRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建申请（status=pending）。materials 可空。 */
    public Mono<MerchantPermissionRequest> create(String organizationId, String requesterAccountId,
                                                  String requestedTier, String materials) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO merchant_permission_request(id, organization_id, requester_account_id, requested_tier, materials, status)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :tier, CAST(:materials AS json), 'pending')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId).bind("tier", requestedTier);
        if (materials == null || materials.isBlank()) {
            spec = spec.bindNull("materials", String.class);
        } else {
            spec = spec.bind("materials", materials);
        }
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
        if (reviewNote == null || reviewNote.isBlank()) {
            spec = spec.bindNull("note", String.class);
        } else {
            spec = spec.bind("note", reviewNote);
        }
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
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
