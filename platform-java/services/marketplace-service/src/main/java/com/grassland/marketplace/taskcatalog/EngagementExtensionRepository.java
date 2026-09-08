package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 交付延期申请数据访问（任务书 #96 C96-01 / §6 /extend）。
 *
 * <p>申请-批准两段式：推荐官 {@code createPending}（V56 部分唯一索引保证同一报名至多一条 pending，
 * 重复申请并发收敛为唯一冲突 → 调用方 409）；商家 {@code decide}（pending→approved/rejected 的
 * guarded UPDATE，0 行 = 已决定/不存在）。批准后的 deadline 后移在 {@link TaskApplicationRepository
 * #extendDeliveryDeadline}，与决定同事务由领域服务编排。
 */
@Component
public class EngagementExtensionRepository {

    /** 延期申请行（decision 视图）。 */
    public record EngagementExtension(String id, String applicationId, String requestedBy, int days, String reason,
            String status, String decidedBy, Instant decidedAt, Instant createdAt) {
    }

    private final DatabaseClient db;

    public EngagementExtensionRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 新建待审申请。pending 唯一冲突 → empty（调用方 409「已有待处理的延期申请」）。 */
    public Mono<EngagementExtension> createPending(String applicationId, String requestedBy, int days,
            String reason) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO engagement_extension(id, application_id, requested_by, days, reason)
                VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:by AS uuid), :days, :reason)
                RETURNING id::text, application_id::text, requested_by::text, days, reason,
                          status, decided_by::text, decided_at, created_at
                """)
                .bind("id", id).bind("app", applicationId).bind("by", requestedBy)
                .bind("days", days);
        spec = (reason == null || reason.isBlank())
                ? spec.bindNull("reason", String.class)
                : spec.bind("reason", reason.trim());
        return spec.map(EngagementExtensionRepository::map).one()
                .onErrorResume(io.r2dbc.spi.R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
    }

    /** 该报名当前的待审申请（无则空）。 */
    public Mono<EngagementExtension> findPending(String applicationId) {
        return db.sql("""
                SELECT id::text, application_id::text, requested_by::text, days, reason,
                       status, decided_by::text, decided_at, created_at
                FROM engagement_extension
                WHERE application_id = CAST(:app AS uuid) AND status = 'pending'
                ORDER BY created_at DESC LIMIT 1
                """).bind("app", applicationId).map(EngagementExtensionRepository::map).one();
    }

    /**
     * 决定待审申请：pending → approved/rejected（guarded）。0 行 = 无待审或已被并发决定 → empty。
     * 批准后的 deadline 后移由调用方在同一事务接 {@code extendDeliveryDeadline}（单边胜出语义一致）。
     */
    public Mono<EngagementExtension> decide(String applicationId, boolean approved, String decidedBy) {
        return db.sql("""
                UPDATE engagement_extension
                SET status = :status, decided_by = CAST(:by AS uuid), decided_at = now(), updated_at = now()
                WHERE application_id = CAST(:app AS uuid) AND status = 'pending'
                RETURNING id::text, application_id::text, requested_by::text, days, reason,
                          status, decided_by::text, decided_at, created_at
                """)
                .bind("app", applicationId).bind("by", decidedBy)
                .bind("status", approved ? "approved" : "rejected")
                .map(EngagementExtensionRepository::map).one();
    }

    private static EngagementExtension map(Readable row) {
        return new EngagementExtension(row.get("id", String.class), row.get("application_id", String.class),
                row.get("requested_by", String.class), row.get("days", Integer.class),
                row.get("reason", String.class), row.get("status", String.class),
                row.get("decided_by", String.class), toInstant(row.get("decided_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
