package com.grassland.identity.organization;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 商家主体更名申请（V40 / 2026-08-23 产品规则）：主体名称变更须审核生效，
 * 且自上次变更（创建或上次更名生效）起 30 天冷却。冷却与互斥由契约层判定，
 * {@code uq_org_rename_pending} 唯一索引兜底并发重复提交。
 */
@Component
public class OrganizationRenameRepository {

    private final DatabaseClient db;

    public OrganizationRenameRepository(DatabaseClient db) {
        this.db = db;
    }

    public record RenameRequest(
            String id,
            String organizationId,
            String requestedByAccountId,
            String currentName,
            String requestedName,
            String status,
            Instant requestedAt,
            Instant reviewedAt,
            String reviewedByAccountId,
            String reviewNote) {
    }

    private static final String COLS = """
            id::text, organization_id::text, requested_by_account_id::text,
            current_name, requested_name, status, requested_at, reviewed_at,
            reviewed_by_account_id::text, review_note
            """;

    public Mono<RenameRequest> insert(String organizationId, String requestedBy, String currentName, String requestedName) {
        return db.sql("INSERT INTO organization_rename_request"
                        + "(id, organization_id, requested_by_account_id, current_name, requested_name, status)"
                        + " VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid), :cur, :req, 'pending')"
                        + " RETURNING " + COLS)
                .bind("id", UUID.randomUUID().toString()).bind("org", organizationId)
                .bind("acct", requestedBy).bind("cur", currentName).bind("req", requestedName)
                .map(OrganizationRenameRepository::map).one();
    }

    /** 同一主体的待审申请（至多一条，唯一索引保证）。 */
    public Mono<RenameRequest> findPendingByOrg(String organizationId) {
        return db.sql("SELECT " + COLS + " FROM organization_rename_request"
                        + " WHERE organization_id = CAST(:org AS uuid) AND status = 'pending'")
                .bind("org", organizationId)
                .map(OrganizationRenameRepository::map).one();
    }

    /** 最近一次**已生效**更名（冷却期起点之一；无则回退主体创建时间）。 */
    public Mono<RenameRequest> findLatestApproved(String organizationId) {
        return db.sql("SELECT " + COLS + " FROM organization_rename_request"
                        + " WHERE organization_id = CAST(:org AS uuid) AND status = 'approved'"
                        + " ORDER BY reviewed_at DESC LIMIT 1")
                .bind("org", organizationId)
                .map(OrganizationRenameRepository::map).one();
    }

    /** 主体视角的申请历史（含待审，按时间倒序）。 */
    public Flux<RenameRequest> findRecentByOrg(String organizationId, int limit) {
        return db.sql("SELECT " + COLS + " FROM organization_rename_request"
                        + " WHERE organization_id = CAST(:org AS uuid)"
                        + " ORDER BY requested_at DESC LIMIT " + Math.max(1, Math.min(limit, 20)))
                .bind("org", organizationId)
                .map(OrganizationRenameRepository::map).all();
    }

    /** 平台审核队列：全部待审（按申请时间正序，先到先审）。 */
    public Flux<RenameRequest> findPendingAll() {
        return db.sql("SELECT " + COLS + " FROM organization_rename_request"
                        + " WHERE status = 'pending' ORDER BY requested_at")
                .map(OrganizationRenameRepository::map).all();
    }

    public Mono<RenameRequest> findById(String id) {
        return db.sql("SELECT " + COLS + " FROM organization_rename_request WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(OrganizationRenameRepository::map).one();
    }

    /** 审核落终态（approved/rejected）；名称生效由调用方在 同一事务 里 UPDATE organization。 */
    public Mono<RenameRequest> review(String id, String status, String reviewedBy, String note) {
        return db.sql("UPDATE organization_rename_request"
                        + " SET status = :status, reviewed_at = now(), reviewed_by_account_id = CAST(:acct AS uuid),"
                        + " review_note = :note WHERE id = CAST(:id AS uuid) RETURNING " + COLS)
                .bind("status", status).bind("acct", reviewedBy)
                .bind("note", note == null || note.isBlank() ? null : note.trim())
                .bind("id", id)
                .map(OrganizationRenameRepository::map).one();
    }

    private static RenameRequest map(Readable r) {
        return new RenameRequest(
                r.get("id", String.class),
                r.get("organization_id", String.class),
                r.get("requested_by_account_id", String.class),
                r.get("current_name", String.class),
                r.get("requested_name", String.class),
                r.get("status", String.class),
                r.get("requested_at", Instant.class),
                r.get("reviewed_at", Instant.class),
                r.get("reviewed_by_account_id", String.class),
                r.get("review_note", String.class));
    }
}
