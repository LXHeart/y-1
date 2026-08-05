package com.grassland.trust.dispute;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * dispute_evidence 数据访问（GL-P2-TRUST-001 T1）。
 *
 * <p>证据是 append-only 语义（争议存续期可补证据，但不改不删——证据是审计/裁决对象）。
 * 故本类刻意不提供 update/delete：写错由补一条 + 审计说明覆盖，不抹除历史。
 */
@Component
public class DisputeEvidenceRepository {

    private static final String COLS = "id::text, dispute_id::text, submitted_by_account_id::text,"
            + " submitted_by_role, kind, content_ref, redacted_ref, caption, created_at, retention_until";

    private final DatabaseClient db;

    public DisputeEvidenceRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 追加一条证据（id 由调用方生成）。{@code redactedRef}/{@code caption} 可空。 */
    public Mono<DisputeEvidence> append(DisputeEvidence e) {
        var spec = db.sql("""
                INSERT INTO dispute_evidence(id, dispute_id, submitted_by_account_id, submitted_by_role,
                                             kind, content_ref, redacted_ref, caption, retention_until)
                VALUES (CAST(:id AS uuid), CAST(:did AS uuid), CAST(:by AS uuid), :role,
                        :kind, :content, :redacted, :caption, :retention)
                RETURNING %s
                """.formatted(COLS))
                .bind("id", e.id()).bind("did", e.disputeId()).bind("by", e.submittedByAccountId())
                .bind("role", e.submittedByRole()).bind("kind", e.kind()).bind("content", e.contentRef())
                .bind("retention", e.retentionUntil().atOffset(java.time.ZoneOffset.UTC));
        spec = bindNullable(spec, "redacted", e.redactedRef());
        spec = bindNullable(spec, "caption", e.caption());
        return spec.map(DisputeEvidenceRepository::map).one();
    }

    /** 某争议的全部证据（按提交时间升序）。 */
    public Flux<DisputeEvidence> listByDispute(String disputeId) {
        return db.sql("SELECT " + COLS + " FROM dispute_evidence WHERE dispute_id = CAST(:did AS uuid) ORDER BY created_at")
                .bind("did", disputeId)
                .map(DisputeEvidenceRepository::map).all();
    }

    public Mono<DisputeEvidence> findById(String id) {
        return db.sql("SELECT " + COLS + " FROM dispute_evidence WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(DisputeEvidenceRepository::map).one();
    }

    private static DisputeEvidence map(Readable row) {
        return new DisputeEvidence(
                row.get("id", String.class),
                row.get("dispute_id", String.class),
                row.get("submitted_by_account_id", String.class),
                row.get("submitted_by_role", String.class),
                row.get("kind", String.class),
                row.get("content_ref", String.class),
                row.get("redacted_ref", String.class),
                row.get("caption", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("retention_until", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
