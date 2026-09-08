package com.grassland.marketplace.milestone;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * engagement_milestone 数据访问（任务书 #96 C96-02，表建于 V56）。手写 SQL 风格同 taskcatalog 仓储：
 * 状态变更 guarded UPDATE + RETURNING，0 行 = 已被并发路径处理。
 *
 * <p>不可变红线（TC96-010）：无 delete/更新事实字段的路径；{@code amountCents} 只允许
 * {@link #markSettledAmount} 从 NULL 一次性回填（结算审计），confirmed_at/confirmed_by 只允许
 * {@link #confirm} 从 NULL 单次落定。
 */
@Component
public class EngagementMilestoneRepository {

    private static final String SELECT_COLS = "id::text, application_id::text, kind, version,"
            + " evidence_submission_id::text, proposed_by::text, confirmed_by::text, confirmed_at,"
            + " amount_cents, created_at, updated_at";

    private final DatabaseClient db;

    public EngagementMilestoneRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<EngagementMilestone> create(String applicationId, String kind, int version,
            String evidenceSubmissionId, String proposedBy) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO engagement_milestone(id, application_id, kind, version, evidence_submission_id,
                                                 proposed_by)
                VALUES (CAST(:id AS uuid), CAST(:app AS uuid), :kind, :version, CAST(:evidence AS uuid),
                        CAST(:by AS uuid))
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("app", applicationId).bind("kind", kind).bind("version", version)
                .bind("by", proposedBy);
        spec = evidenceSubmissionId == null
                ? spec.bindNull("evidence", UUID.class)
                : spec.bind("evidence", UUID.fromString(evidenceSubmissionId));
        return spec.map(EngagementMilestoneRepository::map).one()
                .onErrorResume(io.r2dbc.spi.R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
    }

    public Mono<EngagementMilestone> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_milestone WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(EngagementMilestoneRepository::map).one();
    }

    /** 某报名全部里程碑（按 kind/version 升序）。 */
    public Flux<EngagementMilestone> findByApplication(String applicationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_milestone WHERE application_id = CAST(:app AS uuid)"
                + " ORDER BY kind, version")
                .bind("app", applicationId).map(EngagementMilestoneRepository::map).all();
    }

    /** 同类里程碑下一个版次（重做另起一行，旧行留档）。 */
    public Mono<Integer> nextVersion(String applicationId, String kind) {
        return db.sql("SELECT COALESCE(MAX(version), 0)::int + 1 AS v FROM engagement_milestone"
                        + " WHERE application_id = CAST(:app AS uuid) AND kind = :kind")
                .bind("app", applicationId).bind("kind", kind)
                .map(r -> r.get("v", Integer.class)).one().defaultIfEmpty(1);
    }

    /**
     * 对方互签确认（双方确认制）：pending + 确认人 ≠ 提出方 → confirmed。0 行 = 已确认/自签/不存在。
     * confirmed 后幂等重入由调用方回读已确认行返回 200。
     */
    public Mono<EngagementMilestone> confirm(String id, String confirmedBy) {
        return db.sql("""
                UPDATE engagement_milestone
                SET confirmed_by = CAST(:by AS uuid), confirmed_at = now(), updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND confirmed_at IS NULL
                  AND proposed_by <> CAST(:by AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("by", confirmedBy).map(EngagementMilestoneRepository::map).one();
    }

    /** 结算审计回填：confirmed 行的 amount_cents 一次性写入（NULL→值），之后不可变。 */
    public Mono<Boolean> markSettledAmount(String id, long amountCents) {
        return db.sql("""
                UPDATE engagement_milestone
                SET amount_cents = :amount, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND confirmed_at IS NOT NULL AND amount_cents IS NULL
                """)
                .bind("id", id).bind("amount", amountCents).fetch().rowsUpdated().map(n -> n > 0L)
                .defaultIfEmpty(false);
    }

    private static EngagementMilestone map(Readable row) {
        return new EngagementMilestone(row.get("id", String.class), row.get("application_id", String.class),
                row.get("kind", String.class), row.get("version", Integer.class),
                row.get("evidence_submission_id", String.class), row.get("proposed_by", String.class),
                row.get("confirmed_by", String.class), toInstant(row.get("confirmed_at", OffsetDateTime.class)),
                row.get("amount_cents", Long.class), toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
