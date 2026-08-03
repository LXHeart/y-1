package com.grassland.identity.kyb;

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
 * kyb_verification_request 数据访问（R2DBC {@link DatabaseClient} 手写 SQL）。
 * GL-P3-MERCHANT-001。
 */
@Component
public class KybVerificationRequestRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, requester_account_id::text, verification_type, target_id::text,"
                    + " materials::text, status, reviewer_account_id::text, review_note, review_deadline, created_at, updated_at";

    private final DatabaseClient db;

    public KybVerificationRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建审核申请。*/
    public Mono<KybVerificationRequest> create(String organizationId, String requesterAccountId,
                                               String verificationType, UUID targetId, String materials,
                                               Instant reviewDeadline) {
        UUID id = UUID.randomUUID();
        var spec = db.sql("""
                INSERT INTO kyb_verification_request(id, organization_id, requester_account_id,
                        verification_type, target_id, materials, review_deadline)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :type,
                        CAST(:target AS uuid), CAST(:materials AS jsonb), :deadline)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId)
                .bind("type", verificationType).bind("target", targetId);
        spec = bindNullable(spec, "materials", materials);
        spec = bindNullable(spec, "deadline", reviewDeadline);
        return spec.map(KybVerificationRequestRepository::map).one();
    }

    /** 查询审核申请。*/
    public Mono<KybVerificationRequest> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM kyb_verification_request WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(KybVerificationRequestRepository::map).one();
    }

    /** 列出组织下所有审核申请。*/
    public Flux<KybVerificationRequest> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM kyb_verification_request WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at DESC")
                .bind("org", organizationId)
                .map(KybVerificationRequestRepository::map).all();
    }

    /** 查询待审核队列。*/
    public Flux<KybVerificationRequest> findPending() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM kyb_verification_request WHERE status IN ('pending', 'under_review') ORDER BY created_at")
                .map(KybVerificationRequestRepository::map).all();
    }

    /** 查询指定类型和目标的审核申请。*/
    public Mono<KybVerificationRequest> findByTypeAndTarget(String verificationType, UUID targetId) {
        return db.sql("""
                SELECT %s FROM kyb_verification_request
                WHERE verification_type = :type AND target_id = CAST(:target AS uuid)
                ORDER BY created_at DESC LIMIT 1
                """.formatted(SELECT_COLS))
                .bind("type", verificationType).bind("target", targetId)
                .map(KybVerificationRequestRepository::map).one();
    }

    /** 更新审核结果。*/
    public Mono<KybVerificationRequest> updateStatus(UUID id, String status, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE kyb_verification_request
                SET status = :status, reviewer_account_id = CAST(:reviewer AS uuid),
                    review_note = :note, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("status", status).bind("reviewer", reviewerAccountId);
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(KybVerificationRequestRepository::map).one();
    }

    private static KybVerificationRequest map(Readable row) {
        return new KybVerificationRequest(
                UUID.fromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("requester_account_id", String.class),
                row.get("verification_type", String.class),
                row.get("target_id", String.class) != null ? UUID.fromString(row.get("target_id", String.class)) : null,
                row.get("materials", String.class),
                row.get("status", String.class),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("review_deadline", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Instant value) {
        if (value == null) {
            return spec.bindNull(name, OffsetDateTime.class);
        }
        return spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
