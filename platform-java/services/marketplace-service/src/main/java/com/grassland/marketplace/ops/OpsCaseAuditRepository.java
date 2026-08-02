package com.grassland.marketplace.ops;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ops_case_audit 数据访问（GL-P1-OPS-001 Stage 1）。
 *
 * <p><b>只追加不改不删</b>：本类刻意<b>不提供</b> update/delete 方法 —— 审计流水一旦可改就不再是审计。
 * 每条状态流转都必须在与 {@code ops_case} 写入<b>同一事务</b>内 append，否则会出现「状态变了但没人记得是谁改的」。
 */
@Component
public class OpsCaseAuditRepository {

    private final DatabaseClient db;

    public OpsCaseAuditRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 追加一条审计。{@code actorAccountId}/{@code actorRole} 可空（系统登记时为 NULL + {@code system}）。
     *
     * @param action registered / submitted / approved / rejected / resolved / action_executed
     */
    public Mono<Long> append(String caseId, String action, String actorAccountId, String actorRole,
                             String fromStatus, String toStatus, String note) {
        var spec = db.sql("""
                INSERT INTO ops_case_audit(case_id, action, actor_account_id, actor_role,
                                           from_status, to_status, note)
                VALUES (CAST(:caseId AS uuid), :action, CAST(:actor AS uuid), :role, :from, :to, :note)
                RETURNING id
                """)
                .bind("caseId", caseId).bind("action", action);
        spec = bindNullable(spec, "actor", actorAccountId);
        spec = bindNullable(spec, "role", actorRole);
        spec = bindNullable(spec, "from", fromStatus);
        spec = bindNullable(spec, "to", toStatus);
        spec = bindNullable(spec, "note", note);
        return spec.map(row -> row.get("id", Long.class)).one();
    }

    /** 某单的完整审计时间线（按 id 升序 = 发生顺序；同 created_at 也稳定）。 */
    public Flux<OpsCaseAudit> listByCase(String caseId) {
        return db.sql("""
                SELECT id, case_id::text, action, actor_account_id::text, actor_role,
                       from_status, to_status, note, created_at
                FROM ops_case_audit WHERE case_id = CAST(:caseId AS uuid) ORDER BY id
                """)
                .bind("caseId", caseId)
                .map(OpsCaseAuditRepository::map).all();
    }

    private static OpsCaseAudit map(Readable row) {
        return new OpsCaseAudit(
                row.get("id", Long.class),
                row.get("case_id", String.class),
                row.get("action", String.class),
                row.get("actor_account_id", String.class),
                row.get("actor_role", String.class),
                row.get("from_status", String.class),
                row.get("to_status", String.class),
                row.get("note", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
