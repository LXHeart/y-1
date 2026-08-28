package com.grassland.identity.recommenderprofile;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 推荐官认证审核数据访问（GL-P2-ADMIN-002）。克隆 KYB 范式（{@code KybVerificationRequestRepository}）。
 *
 * <p>同 account 只允许一个 pending（UNIQUE 部分索引兜底）；approved/rejected 后可重新提交新申请。
 */
@Component
public class RecommenderVerificationRepository {

    private static final String SELECT_COLS =
            "id::text, account_id::text, materials::text, status, reviewer_account_id::text,"
                    + " review_note, review_deadline, created_at, updated_at";

    /** 待审核口径（行查与 COUNT 共用，防分页漂移）。 */
    private static final String PENDING_FILTER = " WHERE status = 'pending'";

    private final DatabaseClient db;

    public RecommenderVerificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建审核申请。 */
    public Mono<RecommenderVerificationRequest> create(
            String accountId, String materials, Instant reviewDeadline) {
        UUID id = UUID.randomUUID();
        var spec = db.sql("""
                INSERT INTO recommender_verification_request(id, account_id, materials, review_deadline)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:materials AS jsonb), :deadline)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("acct", accountId);
        spec = bindNullable(spec, "materials", materials);
        spec = bindNullable(spec, "deadline", reviewDeadline);
        return spec.map(RecommenderVerificationRepository::map).one();
    }

    public Mono<RecommenderVerificationRequest> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM recommender_verification_request WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(RecommenderVerificationRepository::map).one();
    }

    /** 某账号最新的申请（含终态）。供「是否已认证」判定 + 防重复堆队。 */
    public Mono<RecommenderVerificationRequest> findLatestByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM recommender_verification_request"
                        + " WHERE account_id = CAST(:acct AS uuid) ORDER BY created_at DESC LIMIT 1")
                .bind("acct", accountId)
                .map(RecommenderVerificationRepository::map).one();
    }

    /** 待审核队列（admin 审核台用）：分页，保留原 ORDER BY（先到先审）。 */
    public Flux<RecommenderVerificationRequest> findPending(int limit, int offset) {
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM recommender_verification_request" + PENDING_FILTER
                        + " ORDER BY created_at LIMIT :limit OFFSET :offset")
                .bind("limit", limit).bind("offset", offset)
                .map(RecommenderVerificationRepository::map).all();
    }

    /** 待审核队列总数（与 {@link #findPending(int, int)} 同 WHERE 口径）。 */
    public Mono<Long> countPending() {
        return db.sql("SELECT COUNT(*) AS c FROM recommender_verification_request" + PENDING_FILTER)
                .map(row -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    /** 更新审核结果（status 守卫：仅 pending 可审）。 */
    public Mono<RecommenderVerificationRequest> updateStatus(
            UUID id, String status, String reviewerAccountId, String reviewNote) {
        var spec = db.sql("""
                UPDATE recommender_verification_request
                SET status = :status, reviewer_account_id = CAST(:reviewer AS uuid),
                    review_note = :note, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("status", status).bind("reviewer", reviewerAccountId);
        spec = bindNullable(spec, "note", reviewNote);
        return spec.map(RecommenderVerificationRepository::map).one();
    }

    private static RecommenderVerificationRequest map(Readable row) {
        return new RecommenderVerificationRequest(
                UUID.fromString(row.get("id", String.class)),
                row.get("account_id", String.class),
                row.get("materials", String.class),
                row.get("status", String.class),
                row.get("reviewer_account_id", String.class),
                row.get("review_note", String.class),
                toInstant(row.get("review_deadline", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, Object value) {
        return value == null ? spec.bindNull(name, value instanceof Instant ? OffsetDateTime.class : String.class) : spec.bind(name, value);
    }
}
