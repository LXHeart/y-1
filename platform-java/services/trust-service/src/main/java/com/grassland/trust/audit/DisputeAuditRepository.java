package com.grassland.trust.audit;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * dispute_audit 数据访问（GL-P2-TRUST-001 T2）。
 *
 * <p><b>只追加不改不删</b>：本类刻意不提供 update/delete——审计流水一旦可改就不再是审计。
 * 每条争议状态流转都应在<b>同一事务</b>内 append（与状态写入 + outbox 同事务），否则出现「状态变了但没人记得是谁改的」。
 * 镜像 marketplace {@code OpsCaseAuditRepository}。
 */
@Component
public class DisputeAuditRepository {

    private final DatabaseClient db;

    public DisputeAuditRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 追加一条审计。
     *
     * @param action opened / evidence_submitted / decided / adjudicated / finalized / ...
     */
    public Mono<Long> append(String disputeId, String action, String actorAccountId, String actorRole, String note) {
        var spec = db.sql("""
                INSERT INTO dispute_audit(dispute_id, action, actor_account_id, actor_role, note)
                VALUES (CAST(:did AS uuid), :action, CAST(:actor AS uuid), :role, :note)
                RETURNING id
                """)
                .bind("did", disputeId).bind("action", action);
        spec = bindNullable(spec, "actor", actorAccountId);
        spec = bindNullable(spec, "role", actorRole);
        spec = bindNullable(spec, "note", note);
        return spec.map(row -> row.get("id", Long.class)).one();
    }

    /** 某争议的完整审计时间线（按 id 升序 = 发生顺序）。 */
    public Flux<DisputeAudit> listByDispute(String disputeId) {
        return db.sql("""
                SELECT id, dispute_id::text, action, actor_account_id::text, actor_role, note, created_at
                FROM dispute_audit WHERE dispute_id = CAST(:did AS uuid) ORDER BY id
                """)
                .bind("did", disputeId)
                .map(DisputeAuditRepository::map).all();
    }

    private static DisputeAudit map(Readable row) {
        return new DisputeAudit(
                row.get("id", Long.class),
                row.get("dispute_id", String.class),
                row.get("action", String.class),
                row.get("actor_account_id", String.class),
                row.get("actor_role", String.class),
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
