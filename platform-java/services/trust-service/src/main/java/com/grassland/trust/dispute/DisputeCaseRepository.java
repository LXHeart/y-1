package com.grassland.trust.dispute;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * dispute_case 数据访问（草场 Epic 6 Slice 6A 受理 + 6C 审判扩字段，风格同 finance ReservationRepository）。
 *
 * <p>{@link #create} 用 partial unique(engagement_ref WHERE status<>'final') 保幂等：每 engagement 至多一个<b>未终局</b>争议，
 * 中间态（open/voting/decided/appealed）持续占用活跃槽阻塞结算；终局后可再开新争议。并发违例 → empty（调用方判既有）。
 * {@link #decide} 手动终局（open→final，version+1）；{@link #findActiveByEngagementRef} 查活跃（非 final）争议。
 *
 * <p>审判 workflow 用的状态迁移方法（startAdjudication/reopen/recordDecision/markAppealed/finalize）随各 activity 落地时再加；
 * 本 slice 先提供受理 + 手动终局 + 活跃查询三件套，避免未接线的投机代码。
 */
@Component
public class DisputeCaseRepository {

    private static final String SELECT_COLS =
            "id::text, engagement_ref, organization_id::text, opened_by_account_id::text, opened_by_role,"
                    + " status, reason, decision, decided_at, created_at, updated_at,"
                    + " round, version, appeal_state, final_decision, final_decided_by::text, evidence_ref";

    private final DatabaseClient db;

    public DisputeCaseRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 开争议（status=open）。新列取默认（round=0, version=1, appeal_state='none'）。partial unique 违例 → empty（幂等：已有活跃）。 */
    public Mono<DisputeCase> create(String engagementRef, String organizationId, String openedBy, String role, String reason) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id, opened_by_role, status, reason)
                VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:by AS uuid), :role, 'open', :reason)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("ref", engagementRef).bind("org", organizationId)
                .bind("by", openedBy).bind("role", role);
        spec = bindNullable(spec, "reason", reason);
        return spec.map(DisputeCaseRepository::map).one()
                .onErrorResume(DisputeCaseRepository::isDuplicateKey, e -> Mono.empty());
    }

    /** 并发竞态下 partial-unique 违例兜底（常规幂等由 controller 预查 findActive 处理）。按消息判定 duplicate key（robust）。 */
    private static boolean isDuplicateKey(Throwable e) {
        return e != null && e.getMessage() != null && e.getMessage().contains("duplicate key");
    }

    public Mono<DisputeCase> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM dispute_case WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(DisputeCaseRepository::map).one();
    }

    /** 某 engagement 的<b>活跃</b>（未终局，status<>'final'）争议（DisputeChecker + 开争议幂等用）。无 → empty。 */
    public Mono<DisputeCase> findActiveByEngagementRef(String engagementRef) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM dispute_case WHERE engagement_ref = :ref AND status <> 'final'")
                .bind("ref", engagementRef)
                .map(DisputeCaseRepository::map).one();
    }

    /** 手动裁决（终局）：open→final，记 decision + decided_at + final_decision + version+1。0 行（非 open）→ empty。 */
    public Mono<DisputeCase> decide(String id, String decision) {
        return db.sql("""
                UPDATE dispute_case SET status = 'final', decision = :decision, final_decision = :decision,
                        decided_at = now(), version = version + 1, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'open'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("decision", decision)
                .map(DisputeCaseRepository::map).one();
    }

    private static DisputeCase map(Readable row) {
        return new DisputeCase(
                row.get("id", String.class),
                row.get("engagement_ref", String.class),
                row.get("organization_id", String.class),
                row.get("opened_by_account_id", String.class),
                row.get("opened_by_role", String.class),
                row.get("status", String.class),
                row.get("reason", String.class),
                row.get("decision", String.class),
                toInstant(row.get("decided_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                row.get("round", Integer.class),
                row.get("version", Long.class),
                row.get("appeal_state", String.class),
                row.get("final_decision", String.class),
                row.get("final_decided_by", String.class),
                row.get("evidence_ref", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
