package com.grassland.marketplace.ops;

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
 * ops_case 数据访问（GL-P1-OPS-001 Stage 1，风格同 {@code DisputeCaseRepository}）。
 *
 * <p>{@link #insertIfAbsent} 幂等：{@code ON CONFLICT (source_kind, source_ref) DO NOTHING} —— Kafka 重投、
 * Temporal activity 重跑、对账重试都不会开出第二张单；冲突时返回 empty，让调用方能区分首次与重投
 * （{@link OpsCaseRegistrar} 据此决定是否写 {@code registered} 审计）。
 *
 * <p>状态流转全部 guarded-UPDATE-with-RETURNING + {@code expectedVersion} 乐观锁：
 * 前置状态或版本不符 → 0 行 → empty，调用方映射 409。
 */
@Component
public class OpsCaseRepository {

    private static final String SELECT_COLS =
            "id::text, source_kind, source_ref, organization_id, application_id, reason, severity,"
                    + " status, version, submitted_by::text, submitted_at, submit_note,"
                    + " approved_by::text, approved_at, approve_note, resolved_at, resolution,"
                    + " created_at, updated_at";

    private final DatabaseClient db;

    public OpsCaseRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 插入处置单，**冲突时 empty**（不回读）。empty 即「已有单」，调用方据此区分首次登记与重投 ——
     * 见 {@link OpsCaseRegistrar}，它靠这个区分决定是否写 {@code registered} 审计。
     *
     * <p>不在此处回读是刻意的：回读会让首次与重投都返回一个 OpsCase，调用方只能靠
     * 「version=1 且 open」猜，而未被处置的既有单恰好满足该条件，会导致重复审计。
     *
     * <p>调用方应在**领域写的同一事务**内调用，保证「阻断落库 ⇔ 处置单存在」原子
     * （同 outbox 的事务性投递口径）。
     */
    public Mono<OpsCase> insertIfAbsent(String sourceKind, String sourceRef, String organizationId,
                                        String applicationId, String reason) {
        var spec = db.sql("""
                INSERT INTO ops_case(id, source_kind, source_ref, organization_id, application_id, reason, severity)
                VALUES (CAST(:id AS uuid), :kind, :ref, :org, :app, :reason, :severity)
                ON CONFLICT (source_kind, source_ref) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("kind", sourceKind).bind("ref", sourceRef)
                .bind("reason", reason)
                .bind("severity", OpsCaseSource.severityOf(sourceKind));
        spec = bindNullable(spec, "org", organizationId);
        spec = bindNullable(spec, "app", applicationId);
        return spec.map(OpsCaseRepository::map).one();
    }

    public Mono<OpsCase> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ops_case WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(OpsCaseRepository::map).one();
    }

    public Mono<OpsCase> findBySource(String sourceKind, String sourceRef) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ops_case WHERE source_kind = :kind AND source_ref = :ref")
                .bind("kind", sourceKind).bind("ref", sourceRef)
                .map(OpsCaseRepository::map).one();
    }

    /**
     * 队列：默认只列未终态（open/in_review/approved），最早优先（时效）。
     * {@code status} 非空则精确筛选（含终态，供审计回看）。
     */
    public Flux<OpsCase> list(String status, int limit) {
        String where = status == null || status.isBlank()
                ? "status IN ('open', 'in_review', 'approved')"
                : "status = :status";
        var spec = db.sql("SELECT " + SELECT_COLS + " FROM ops_case WHERE " + where
                + " ORDER BY created_at, id LIMIT :limit")
                .bind("limit", limit);
        if (status != null && !status.isBlank()) {
            spec = spec.bind("status", status);
        }
        return spec.map(OpsCaseRepository::map).all();
    }

    /**
     * 运营台队列视图。商家履约异议从 application 的 accept 权益快照派生客服优先级；不复制可漂移的声誉配置，
     * 也不改变已有 case 状态机或 SLA。其他来源保持标准优先级。
     */
    public Flux<QueueItem> listQueue(String status, int limit) {
        String where = status == null || status.isBlank()
                ? "status IN ('open', 'in_review', 'approved')"
                : "status = :status";
        var spec = db.sql("""
                SELECT queued.*,
                       CASE WHEN queued.source_kind = 'merchant_rejection'
                                   AND COALESCE(app.premium_support_at_accept, false)
                            THEN true ELSE false END AS premium_support,
                       CASE WHEN queued.source_kind = 'merchant_rejection'
                                   AND COALESCE(app.premium_support_at_accept, false)
                            THEN 100 ELSE 0 END AS support_priority
                FROM (SELECT %s FROM ops_case WHERE %s) queued
                LEFT JOIN task_application app ON app.id::text = queued.application_id
                ORDER BY support_priority DESC, queued.created_at, queued.id
                LIMIT :limit
                """.formatted(SELECT_COLS, where))
                .bind("limit", limit);
        if (status != null && !status.isBlank()) {
            spec = spec.bind("status", status);
        }
        return spec.map((row, metadata) -> new QueueItem(
                map(row), Boolean.TRUE.equals(row.get("premium_support", Boolean.class)),
                intValue(row.get("support_priority", Integer.class)))).all();
    }

    /** 提审（open→in_review，记提审人）。非 open 或版本不符 → empty。 */
    public Mono<OpsCase> submit(String id, long expectedVersion, String submittedBy, String note) {
        var spec = db.sql("""
                UPDATE ops_case SET status = 'in_review', submitted_by = CAST(:by AS uuid),
                        submitted_at = now(), submit_note = :note,
                        version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'open' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion).bind("by", submittedBy);
        spec = bindNullable(spec, "note", note);
        return spec.map(OpsCaseRepository::map).one();
    }

    /**
     * 审批（in_review→approved|rejected，记审批人）。非 in_review / 版本不符 → empty。
     *
     * <p>SQL 里额外写了 {@code submitted_by <> CAST(:by AS uuid)}：双人审批的第一道守卫在此，
     * DB 的 {@code ck_ops_case_two_person} 是第二道（约束违例会抛异常而非 0 行，
     * 这里先用 WHERE 让「自己审自己」平淡地映射成 409 而不是 500）。
     */
    public Mono<OpsCase> decide(String id, long expectedVersion, String approvedBy,
                                boolean approve, String note) {
        var spec = db.sql("""
                UPDATE ops_case SET status = :target, approved_by = CAST(:by AS uuid),
                        approved_at = now(), approve_note = :note,
                        version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'in_review' AND version = :expected
                        AND submitted_by IS NOT NULL AND submitted_by <> CAST(:by AS uuid)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion).bind("by", approvedBy)
                .bind("target", approve ? "approved" : "rejected");
        spec = bindNullable(spec, "note", note);
        return spec.map(OpsCaseRepository::map).one();
    }

    /**
     * 收单（approved→resolved，记处置结果）。非 approved / 版本不符 → empty。
     *
     * <p>Stage 1 只提供状态迁移；真正的重试/补偿动作在 Stage 2 接入，届时在同事务内
     * 先执行动作再 resolve。
     */
    public Mono<OpsCase> resolve(String id, long expectedVersion, String resolution) {
        var spec = db.sql("""
                UPDATE ops_case SET status = 'resolved', resolution = :resolution,
                        resolved_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'approved' AND version = :expected
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("expected", expectedVersion);
        spec = bindNullable(spec, "resolution", resolution);
        return spec.map(OpsCaseRepository::map).one();
    }

    private static OpsCase map(Readable row) {
        return new OpsCase(
                row.get("id", String.class),
                row.get("source_kind", String.class),
                row.get("source_ref", String.class),
                row.get("organization_id", String.class),
                row.get("application_id", String.class),
                row.get("reason", String.class),
                row.get("severity", String.class),
                row.get("status", String.class),
                value(row.get("version", Long.class)),
                row.get("submitted_by", String.class),
                toInstant(row.get("submitted_at", OffsetDateTime.class)),
                row.get("submit_note", String.class),
                row.get("approved_by", String.class),
                toInstant(row.get("approved_at", OffsetDateTime.class)),
                row.get("approve_note", String.class),
                toInstant(row.get("resolved_at", OffsetDateTime.class)),
                row.get("resolution", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static long value(Long raw) {
        return raw == null ? 1L : raw;
    }

    private static int intValue(Integer raw) {
        return raw == null ? 0 : raw;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    public record QueueItem(OpsCase opsCase, boolean premiumSupport, int supportPriority) {}
}
