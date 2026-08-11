package com.grassland.trust.audit;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * dispute_evidence_access_audit 数据访问（GL-P2-TRUST-001 T2）。append-only，无 update/delete。
 *
 * <p>证据读取路径（审判官/客服经 {@code GET /api/trust/disputes/{id}/adjudication} 看脱敏证据时）
 * 应在<b>同一事务</b>内为每条被查看的证据 append 一条。
 */
@Component
public class DisputeEvidenceAccessAuditRepository {

    private final DatabaseClient db;

    public DisputeEvidenceAccessAuditRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> append(String evidenceId, String disputeId, String viewerAccountId, String viewerRole, String purpose) {
        return db.sql("""
                INSERT INTO dispute_evidence_access_audit(evidence_id, dispute_id, viewer_account_id, viewer_role, purpose)
                VALUES (CAST(:eid AS uuid), CAST(:did AS uuid), CAST(:viewer AS uuid), :role, :purpose)
                RETURNING id
                """)
                .bind("eid", evidenceId).bind("did", disputeId).bind("viewer", viewerAccountId)
                .bind("role", viewerRole).bind("purpose", purpose == null ? "adjudication" : purpose)
                .map(row -> row.get("id", Long.class)).one();
    }

    /** 高敏访问流水只提供有界倒序查询，不提供 update/delete。 */
    public Flux<DisputeEvidenceAccessAudit> list(
            String disputeId, String evidenceId, String viewerAccountId, String viewerRole,
            Instant from, Instant to, int limit) {
        GenericExecuteSpec spec = db.sql("""
                SELECT id, evidence_id::text, dispute_id::text, viewer_account_id::text,
                       viewer_role, purpose, viewed_at
                FROM dispute_evidence_access_audit
                WHERE (CAST(:did AS text) IS NULL OR dispute_id = CAST(:did AS uuid))
                  AND (CAST(:eid AS text) IS NULL OR evidence_id = CAST(:eid AS uuid))
                  AND (CAST(:viewer AS text) IS NULL OR viewer_account_id = CAST(:viewer AS uuid))
                  AND (CAST(:role AS text) IS NULL OR viewer_role = :role)
                  AND (:fromAt IS NULL OR viewed_at >= :fromAt)
                  AND (:toAt IS NULL OR viewed_at <= :toAt)
                ORDER BY viewed_at DESC, id DESC
                LIMIT :limit
                """);
        spec = bindNullableText(spec, "did", disputeId);
        spec = bindNullableText(spec, "eid", evidenceId);
        spec = bindNullableText(spec, "viewer", viewerAccountId);
        spec = bindNullableText(spec, "role", viewerRole);
        spec = bindNullableInstant(spec, "fromAt", from);
        spec = bindNullableInstant(spec, "toAt", to);
        return spec.bind("limit", limit).map(DisputeEvidenceAccessAuditRepository::map).all();
    }

    private static DisputeEvidenceAccessAudit map(Readable row) {
        OffsetDateTime viewedAt = row.get("viewed_at", OffsetDateTime.class);
        return new DisputeEvidenceAccessAudit(
                row.get("id", Long.class),
                row.get("evidence_id", String.class),
                row.get("dispute_id", String.class),
                row.get("viewer_account_id", String.class),
                row.get("viewer_role", String.class),
                row.get("purpose", String.class),
                viewedAt == null ? null : viewedAt.toInstant());
    }

    private static GenericExecuteSpec bindNullableText(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableInstant(GenericExecuteSpec spec, String name, Instant value) {
        return value == null
                ? spec.bindNull(name, OffsetDateTime.class)
                : spec.bind(name, value.atOffset(ZoneOffset.UTC));
    }
}
