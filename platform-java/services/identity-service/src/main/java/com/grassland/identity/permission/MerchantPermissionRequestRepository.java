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
                    + " original_request_id::text, appeal_note, created_at, updated_at, version,"
                    + " review_started_at, sla_breached_at, auto_review_status, auto_review_result::text,"
                    + " review_mode, risk_level, attachment_ids::text, decision_at, appeal_count";

    private final DatabaseClient db;

    public MerchantPermissionRequestRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建申请（status=pending；带行业快照 + SLA 截止）。materials 为 JSON 串，可空。 */
    public Mono<MerchantPermissionRequest> create(String organizationId, String requesterAccountId,
                                                  String requestedTier, String materials,
                                                  String industry, Instant reviewDeadline, String attachmentIds,
                                                  PermissionAutoReview autoReview) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO merchant_permission_request(id, organization_id, requester_account_id, requested_tier,
                        materials, status, industry, review_deadline, attachment_ids,
                        auto_review_status, auto_review_result, review_mode, risk_level)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :tier,
                        CAST(:materials AS json), 'pending', :industry, :deadline, CAST(:attachments AS jsonb),
                        :autoStatus, CAST(:autoResult AS jsonb), :reviewMode, :riskLevel)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId)
                .bind("tier", requestedTier).bind("industry", industry)
                .bind("attachments", attachmentIds).bind("autoStatus", autoReview.status())
                .bind("autoResult", autoReview.resultJson()).bind("reviewMode", autoReview.mode())
                .bind("riskLevel", autoReview.riskLevel());
        spec = bindNullableJson(spec, "materials", materials);
        spec = bindNullableDeadline(spec, "deadline", reviewDeadline);
        return spec.map(MerchantPermissionRequestRepository::map).one();
    }

    /** 申诉：新建 pending 申请，引用原 rejected 申请（original_request_id）+ 申诉说明。 */
    public Mono<MerchantPermissionRequest> createAppeal(String organizationId, String requesterAccountId,
                                                        String requestedTier, String materials,
                                                        String industry, Instant reviewDeadline,
                                                        String originalRequestId, String appealNote,
                                                        int appealCount, String attachmentIds,
                                                        PermissionAutoReview autoReview) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO merchant_permission_request(id, organization_id, requester_account_id, requested_tier,
                        materials, status, industry, review_deadline, original_request_id, appeal_note,
                        appeal_count, attachment_ids, auto_review_status, auto_review_result, review_mode, risk_level)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:req AS uuid), :tier,
                        CAST(:materials AS json), 'pending', :industry, :deadline,
                        CAST(:original AS uuid), :appealNote, :appealCount, CAST(:attachments AS jsonb),
                        :autoStatus, CAST(:autoResult AS jsonb), :reviewMode, :riskLevel)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("req", requesterAccountId)
                .bind("tier", requestedTier).bind("industry", industry)
                .bind("original", originalRequestId).bind("appealCount", appealCount)
                .bind("attachments", attachmentIds).bind("autoStatus", autoReview.status())
                .bind("autoResult", autoReview.resultJson()).bind("reviewMode", autoReview.mode())
                .bind("riskLevel", autoReview.riskLevel());
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

    /** admin 审核队列：列 pending/under_review，逾期优先。 */
    public Flux<MerchantPermissionRequest> findPending() {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM merchant_permission_request WHERE status IN ('pending','under_review')"
                + " ORDER BY (review_deadline < now()) DESC, review_deadline, created_at")
                .map(MerchantPermissionRequestRepository::map).all();
    }

    public Flux<MerchantPermissionRequest> findAwaitingAutomaticReview(int limit) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM merchant_permission_request"
                        + " WHERE status IN ('pending','under_review')"
                        + " AND auto_review_status IN ('not_run','pending')"
                        + " ORDER BY created_at, id LIMIT :limit")
                .bind("limit", Math.max(1, Math.min(limit, 500)))
                .map(MerchantPermissionRequestRepository::map).all();
    }

    public Mono<MerchantPermissionRequest> updateAutomaticReview(String id, int expectedVersion,
                                                                  PermissionAutoReview autoReview) {
        return db.sql("""
                UPDATE merchant_permission_request
                SET auto_review_status = :status, auto_review_result = CAST(:result AS jsonb),
                    review_mode = :mode, risk_level = :risk, version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                  AND status IN ('pending','under_review')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expectedVersion", expectedVersion)
                .bind("status", autoReview.status()).bind("result", autoReview.resultJson())
                .bind("mode", autoReview.mode()).bind("risk", autoReview.riskLevel())
                .map(MerchantPermissionRequestRepository::map).one();
    }

    public Mono<MerchantPermissionRequest> claim(String id, String reviewerAccountId) {
        return db.sql("""
                UPDATE merchant_permission_request
                SET status = 'under_review', reviewer_account_id = CAST(:reviewer AS uuid),
                    review_started_at = COALESCE(review_started_at, now()), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("reviewer", reviewerAccountId)
                .map(MerchantPermissionRequestRepository::map).one();
    }

    /** CAS 审核；pending 兼容旧客户端直接审核，under_review 只允许领取人完成。 */
    public Mono<MerchantPermissionRequest> review(String id, String status, String reviewerAccountId,
                                                   String reviewNote, int expectedVersion) {
        var spec = db.sql("""
                UPDATE merchant_permission_request
                SET status = :status, reviewer_account_id = CAST(:reviewer AS uuid),
                    review_started_at = COALESCE(review_started_at, now()), review_note = :note,
                    decision_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                  AND status IN ('pending','under_review')
                  AND (reviewer_account_id IS NULL OR reviewer_account_id = CAST(:reviewer AS uuid))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("status", status).bind("reviewer", reviewerAccountId)
                .bind("expectedVersion", expectedVersion);
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(MerchantPermissionRequestRepository::map).one();
    }

    public Mono<Integer> countAppeals(String originalRequestId) {
        return db.sql("SELECT COUNT(*)::int AS count FROM merchant_permission_request"
                        + " WHERE original_request_id = CAST(:id AS uuid)")
                .bind("id", originalRequestId)
                .map(row -> row.get("count", Integer.class)).one().defaultIfEmpty(0);
    }

    /** 首次把逾期开放申请标记为 breached；状态不自动批准或拒绝。 */
    public Flux<MerchantPermissionRequest> markOverdue(int limit) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM merchant_permission_request
                    WHERE status IN ('pending','under_review')
                      AND review_deadline < now() AND sla_breached_at IS NULL
                    ORDER BY review_deadline, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                , updated AS (
                    UPDATE merchant_permission_request request
                    SET sla_breached_at = now(), version = version + 1, updated_at = now()
                    FROM candidates WHERE request.id = candidates.id
                    RETURNING request.id
                )
                SELECT %s FROM merchant_permission_request
                WHERE id IN (SELECT id FROM updated)
                """.formatted(SELECT_COLS))
                .bind("limit", Math.max(1, Math.min(limit, 500)))
                .map(MerchantPermissionRequestRepository::map).all();
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
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                row.get("version", Integer.class) == null ? 0 : row.get("version", Integer.class),
                toInstant(row.get("review_started_at", OffsetDateTime.class)),
                toInstant(row.get("sla_breached_at", OffsetDateTime.class)),
                row.get("auto_review_status", String.class),
                row.get("auto_review_result", String.class),
                row.get("review_mode", String.class),
                row.get("risk_level", String.class),
                row.get("attachment_ids", String.class),
                toInstant(row.get("decision_at", OffsetDateTime.class)),
                row.get("appeal_count", Integer.class) == null ? 0 : row.get("appeal_count", Integer.class)
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
