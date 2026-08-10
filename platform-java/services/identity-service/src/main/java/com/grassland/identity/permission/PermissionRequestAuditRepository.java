package com.grassland.identity.permission;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Append-only audit trail for merchant admission decisions and SLA actions. */
@Component
public class PermissionRequestAuditRepository {

    private final DatabaseClient db;

    public PermissionRequestAuditRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Void> append(String requestId, String organizationId, String actorAccountId,
                             String actorKind, String action, String fromStatus, String toStatus,
                             String details) {
        var spec = db.sql("""
                INSERT INTO merchant_permission_request_audit(
                    id, request_id, organization_id, actor_account_id, actor_kind, action,
                    from_status, to_status, details)
                VALUES (gen_random_uuid(), CAST(:requestId AS uuid), CAST(:organizationId AS uuid),
                    CAST(:actorAccountId AS uuid), :actorKind, :action, :fromStatus, :toStatus,
                    CAST(:details AS jsonb))
                """)
                .bind("requestId", requestId)
                .bind("organizationId", organizationId)
                .bind("actorKind", actorKind)
                .bind("action", action);
        spec = bindNullable(spec, "actorAccountId", actorAccountId);
        spec = bindNullable(spec, "fromStatus", fromStatus);
        spec = bindNullable(spec, "toStatus", toStatus);
        spec = details == null || details.isBlank() ? spec.bindNull("details", String.class) : spec.bind("details", details);
        return spec.then();
    }

    public Flux<PermissionRequestAudit> findByRequest(String requestId) {
        return db.sql("""
                SELECT id::text, request_id::text, organization_id::text, actor_account_id::text,
                       actor_kind, action, from_status, to_status, details::text, created_at
                FROM merchant_permission_request_audit
                WHERE request_id = CAST(:requestId AS uuid)
                ORDER BY created_at DESC, id DESC
                """)
                .bind("requestId", requestId)
                .map((row, metadata) -> map(row))
                .all();
    }

    private static PermissionRequestAudit map(Readable row) {
        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        return new PermissionRequestAudit(row.get("id", String.class), row.get("request_id", String.class),
                row.get("organization_id", String.class), row.get("actor_account_id", String.class),
                row.get("actor_kind", String.class), row.get("action", String.class),
                row.get("from_status", String.class), row.get("to_status", String.class),
                row.get("details", String.class), createdAt == null ? null : createdAt.toInstant());
    }

    private static org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec bindNullable(
            org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
