package com.grassland.trust.audit;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
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
}
