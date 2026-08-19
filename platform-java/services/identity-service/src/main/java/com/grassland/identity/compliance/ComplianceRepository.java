package com.grassland.identity.compliance;

import static com.grassland.identity.compliance.ComplianceModels.ClosureRequest;
import static com.grassland.identity.compliance.ComplianceModels.ExportRequest;
import static com.grassland.identity.compliance.ComplianceModels.AuditEntry;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ComplianceRepository {

    private static final String EXPORT_COLUMNS =
            "id::text, account_id::text, status, format, artifact, artifact_sha256, artifact_size_bytes,"
                    + " expires_at, attempt_count, claim_token::text, created_at, completed_at, error_code";
    private static final String CLOSURE_COLUMNS =
            "id::text, account_id::text, status, blockers::text, retention_until, attempt_count,"
                    + " claim_token::text, requested_at, completed_at, error_code";

    private final DatabaseClient db;

    public ComplianceRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<ExportRequest> createExport(String accountId) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO personal_data_export_request(id, account_id, status)
                VALUES (CAST(:id AS uuid), CAST(:accountId AS uuid), 'queued')
                RETURNING %s
                """.formatted(EXPORT_COLUMNS))
                .bind("id", id)
                .bind("accountId", accountId)
                .map(ComplianceRepository::mapExport)
                .one();
    }

    public Mono<ExportRequest> findExport(String id, String accountId) {
        return db.sql("SELECT " + EXPORT_COLUMNS
                        + " FROM personal_data_export_request"
                        + " WHERE id = CAST(:id AS uuid) AND account_id = CAST(:accountId AS uuid)")
                .bind("id", id)
                .bind("accountId", accountId)
                .map(ComplianceRepository::mapExport)
                .one();
    }

    public Flux<ExportRequest> claimExports(int limit, UUID claimToken, Duration lease, int maxAttempts) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM personal_data_export_request
                    WHERE status IN ('queued', 'failed') AND next_attempt_at <= now()
                      AND attempt_count < :maxAttempts
                      AND (claimed_until IS NULL OR claimed_until < now())
                    ORDER BY created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE personal_data_export_request request
                SET status = 'processing', claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseSeconds * interval '1 second'),
                    attempt_count = request.attempt_count + 1, updated_at = now(), error_code = NULL
                FROM candidates WHERE request.id = candidates.id
                RETURNING %s
                """.formatted(prefixColumns(EXPORT_COLUMNS, "request")))
                .bind("maxAttempts", Math.max(1, maxAttempts))
                .bind("limit", limit)
                .bind("claimToken", claimToken.toString())
                .bind("leaseSeconds", lease.toSeconds())
                .map(ComplianceRepository::mapExport)
                .all();
    }

    public Mono<Long> completeExport(String id, String claimToken, byte[] artifact, String sha256,
                                     Instant expiresAt) {
        return db.sql("""
                UPDATE personal_data_export_request
                SET status = 'completed', artifact = :artifact, artifact_sha256 = :sha256,
                    artifact_size_bytes = :size, expires_at = :expiresAt, completed_at = now(),
                    claim_token = NULL, claimed_until = NULL, updated_at = now(), error_code = NULL
                WHERE id = CAST(:id AS uuid) AND status = 'processing'
                  AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("artifact", artifact)
                .bind("sha256", sha256)
                .bind("size", (long) artifact.length)
                .bind("expiresAt", expiresAt.atOffset(java.time.ZoneOffset.UTC))
                .bind("id", id)
                .bind("claimToken", claimToken)
                .fetch().rowsUpdated();
    }

    public Mono<Long> failExport(String id, String claimToken, String errorCode, Duration backoff) {
        return db.sql("""
                UPDATE personal_data_export_request
                SET status = 'failed', error_code = :errorCode,
                    next_attempt_at = now() + (:backoffSeconds * interval '1 second'),
                    claim_token = NULL, claimed_until = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'processing'
                  AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("errorCode", errorCode)
                .bind("backoffSeconds", backoff.toSeconds())
                .bind("id", id)
                .bind("claimToken", claimToken)
                .fetch().rowsUpdated();
    }

    public Mono<Long> expireExports() {
        return db.sql("""
                UPDATE personal_data_export_request
                SET status = 'expired', artifact = NULL, updated_at = now()
                WHERE status = 'completed' AND expires_at <= now()
                """).fetch().rowsUpdated();
    }

    public Mono<ClosureRequest> createBlockedClosure(String accountId, String blockersJson) {
        return insertClosure(accountId, "blocked", blockersJson, null);
    }

    public Mono<ClosureRequest> createRetentionClosure(
            String accountId, String blockersJson, Instant retentionUntil) {
        return insertClosure(accountId, "retention", blockersJson, retentionUntil);
    }

    private Mono<ClosureRequest> insertClosure(
            String accountId, String status, String blockersJson, Instant retentionUntil) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO account_closure_request(id, account_id, status, blockers, retention_until)
                VALUES (CAST(:id AS uuid), CAST(:accountId AS uuid), :status,
                        CAST(:blockers AS jsonb), :retentionUntil)
                RETURNING %s
                """.formatted(CLOSURE_COLUMNS))
                .bind("id", id)
                .bind("accountId", accountId)
                .bind("status", status)
                .bind("blockers", blockersJson);
        spec = retentionUntil == null
                ? spec.bindNull("retentionUntil", OffsetDateTime.class)
                : spec.bind("retentionUntil", retentionUntil.atOffset(java.time.ZoneOffset.UTC));
        return spec.map(ComplianceRepository::mapClosure).one();
    }

    public Mono<ClosureRequest> findActiveClosure(String accountId) {
        return db.sql("SELECT " + CLOSURE_COLUMNS
                        + " FROM account_closure_request WHERE account_id = CAST(:accountId AS uuid)"
                        + " ORDER BY requested_at DESC, id DESC LIMIT 1")
                .bind("accountId", accountId)
                .map(ComplianceRepository::mapClosure)
                .one();
    }

    public Flux<ClosureRequest> claimDueClosures(int limit, UUID claimToken, Duration lease, int maxAttempts) {
        return db.sql("""
                WITH candidates AS (
                    SELECT id FROM account_closure_request
                    WHERE status IN ('retention', 'failed') AND retention_until <= now()
                      AND next_attempt_at <= now() AND attempt_count < :maxAttempts
                      AND (claimed_until IS NULL OR claimed_until < now())
                    ORDER BY retention_until, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE account_closure_request request
                SET status = 'erasing', claim_token = CAST(:claimToken AS uuid),
                    claimed_until = now() + (:leaseSeconds * interval '1 second'),
                    attempt_count = request.attempt_count + 1, updated_at = now(), error_code = NULL
                FROM candidates WHERE request.id = candidates.id
                RETURNING %s
                """.formatted(prefixColumns(CLOSURE_COLUMNS, "request")))
                .bind("maxAttempts", maxAttempts)
                .bind("limit", limit)
                .bind("claimToken", claimToken.toString())
                .bind("leaseSeconds", lease.toSeconds())
                .map(ComplianceRepository::mapClosure)
                .all();
    }

    public Mono<Long> completeClosure(String id, String claimToken) {
        return db.sql("""
                UPDATE account_closure_request
                SET status = 'completed', completed_at = now(), updated_at = now(),
                    claim_token = NULL, claimed_until = NULL, error_code = NULL
                WHERE id = CAST(:id AS uuid) AND status = 'erasing'
                  AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("id", id).bind("claimToken", claimToken).fetch().rowsUpdated();
    }

    public Mono<Long> failClosure(String id, String claimToken, String errorCode, Duration backoff) {
        return db.sql("""
                UPDATE account_closure_request
                SET status = 'failed', error_code = :errorCode,
                    next_attempt_at = now() + (:backoffSeconds * interval '1 second'),
                    claim_token = NULL, claimed_until = NULL, updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = 'erasing'
                  AND claim_token = CAST(:claimToken AS uuid)
                """)
                .bind("errorCode", errorCode).bind("backoffSeconds", backoff.toSeconds())
                .bind("id", id).bind("claimToken", claimToken).fetch().rowsUpdated();
    }

    public Mono<Void> appendAudit(String accountId, String action, String requestId,
                                  String actorType, String detailJson) {
        var spec = db.sql("""
                INSERT INTO pii_lifecycle_audit(id, account_id, action, request_id, actor_type, detail)
                VALUES (gen_random_uuid(), CAST(:accountId AS uuid), :action,
                        CAST(:requestId AS uuid), :actorType, CAST(:detail AS jsonb))
                """)
                .bind("accountId", accountId)
                .bind("action", action)
                .bind("actorType", actorType)
                .bind("detail", detailJson == null ? "{}" : detailJson);
        spec = requestId == null
                ? spec.bindNull("requestId", String.class)
                : spec.bind("requestId", requestId);
        return spec.then();
    }

    public Flux<AuditEntry> findAudit(String accountId, int limit) {
        return db.sql("""
                SELECT id::text, action, request_id::text, actor_type, detail::text, occurred_at
                FROM pii_lifecycle_audit WHERE account_id = CAST(:accountId AS uuid)
                ORDER BY occurred_at DESC, id DESC LIMIT :limit
                """)
                .bind("accountId", accountId)
                .bind("limit", Math.max(1, Math.min(limit, 100)))
                .map(row -> new AuditEntry(
                        row.get("id", String.class), row.get("action", String.class),
                        row.get("request_id", String.class), row.get("actor_type", String.class),
                        row.get("detail", String.class), toInstant(row.get("occurred_at", OffsetDateTime.class))))
                .all();
    }

    public Mono<Void> softDeleteAccount(String accountId) {
        return db.sql("""
                UPDATE app_users SET status = 'deleted', deleted_at = COALESCE(deleted_at, now()), updated_at = now()
                WHERE id = CAST(:accountId AS uuid) AND status = 'active'
                """).bind("accountId", accountId).then();
    }

    public Mono<Long> ownedOrganizationCount(String accountId) {
        return db.sql("SELECT COUNT(*)::bigint AS owned FROM organization"
                        + " WHERE owner_account_id = CAST(:accountId AS uuid)")
                .bind("accountId", accountId)
                .map(row -> row.get("owned", Long.class))
                .one().defaultIfEmpty(0L);
    }

    public Mono<Long> activeExportCount(String accountId) {
        return db.sql("SELECT COUNT(*)::bigint AS active FROM personal_data_export_request"
                        + " WHERE account_id = CAST(:accountId AS uuid)"
                        + " AND status IN ('queued', 'processing', 'completed')")
                .bind("accountId", accountId)
                .map(row -> row.get("active", Long.class))
                .one().defaultIfEmpty(0L);
    }

    public Mono<Void> suspendAccountProcessing(String accountId) {
        return db.sql("UPDATE notification_endpoint SET disabled_at = COALESCE(disabled_at, now()),"
                        + " updated_at = now() WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId).then()
                .then(db.sql("DELETE FROM external_delivery_outbox"
                                + " WHERE account_id = CAST(:id AS uuid) AND status = 'pending'")
                        .bind("id", accountId).then());
    }

    public Mono<Void> purgeLocalPii(String accountId) {
        return db.sql("UPDATE personal_data_export_request SET status = 'expired', artifact = NULL, updated_at = now()"
                        + " WHERE account_id = CAST(:id AS uuid) AND status <> 'expired'")
                .bind("id", accountId).then()
                .then(db.sql("DELETE FROM email_verification_codes WHERE lower(email) = ("
                                + " SELECT lower(email) FROM app_users WHERE id = CAST(:id AS uuid))")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM mail_outbox WHERE lower(recipient) = ("
                                + " SELECT lower(email) FROM app_users WHERE id = CAST(:id AS uuid))")
                        .bind("id", accountId).then())
                .then(db.sql("""
                        UPDATE organization_invitation invitation
                        SET email = 'deleted+' || replace(invitation.id::text, '-', '') || '@deleted.invalid',
                            status = CASE WHEN status = 'pending' THEN 'revoked' ELSE status END,
                            expires_at = LEAST(expires_at, now()), updated_at = now()
                        WHERE accepted_by_account_id = CAST(:id AS uuid)
                           OR lower(email) = (SELECT lower(email) FROM app_users
                                              WHERE id = CAST(:id AS uuid))
                        """).bind("id", accountId).then())
                .then(db.sql("DELETE FROM notification_endpoint WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM notification_preference WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM sms_verification_challenge WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM external_delivery_outbox WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM notification WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM refresh_token WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM identity_session WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM user_settings WHERE user_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM identity_profile WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM recommender_profile WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("UPDATE recommender_verification_request"
                                + " SET materials = '{}'::jsonb, review_note = NULL, updated_at = now()"
                                + " WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM store_membership WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM organization_membership WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("DELETE FROM backend_role WHERE account_id = CAST(:id AS uuid)")
                        .bind("id", accountId).then())
                .then(db.sql("""
                        UPDATE identity_audit_log
                        SET session_token = NULL, device_id = NULL, ip_address = NULL, user_agent = NULL,
                            detail = NULL
                        WHERE account_id = CAST(:id AS uuid)
                        """).bind("id", accountId).then())
                .then(db.sql("DELETE FROM withdrawal_account WHERE organization_id IN"
                                + " (SELECT id FROM organization WHERE owner_account_id = CAST(:id AS uuid))")
                        .bind("id", accountId).then())
                .then(db.sql("""
                        UPDATE app_users
                        SET email = 'deleted+' || replace(id::text, '-', '') || '@deleted.invalid',
                            display_name = NULL, password_hash = '!deleted!', last_login_at = NULL, updated_at = now()
                        WHERE id = CAST(:id AS uuid) AND status = 'deleted'
                        """).bind("id", accountId).then());
    }

    public Mono<String> exportIdentityJson(String accountId) {
        return db.sql("""
                SELECT json_build_object(
                    'schemaVersion', 1,
                    'generatedAt', now(),
                    'account', json_build_object(
                        'id', u.id, 'email', u.email, 'displayName', u.display_name,
                        'status', u.status, 'createdAt', u.created_at, 'lastLoginAt', u.last_login_at),
                    'identities', COALESCE((SELECT json_agg(row_to_json(p)) FROM identity_profile p
                        WHERE p.account_id = u.id), '[]'::json),
                    'settings', COALESCE((SELECT json_agg(json_build_object(
                        'id', s.id, 'type', s.settings_type, 'version', s.version,
                        'settings', s.settings_json
                            #- '{features,video,apiToken}' #- '{features,video,apiKey}'
                            #- '{features,image,apiKey}' #- '{features,article,apiKey}'
                            #- '{features,imageGeneration,apiKey}'
                            #- '{features,videoProduction,apiKey}'
                            #- '{integrations,feishu,appSecret}'
                            #- '{integrations,feishu,folderToken}'
                            #- '{hotItems,alapiToken}',
                        'createdAt', s.created_at, 'updatedAt', s.updated_at))
                        FROM user_settings s WHERE s.user_id = u.id), '[]'::json),
                    'organizationMemberships', COALESCE((SELECT json_agg(row_to_json(m))
                        FROM organization_membership m WHERE m.account_id = u.id), '[]'::json),
                    'storeMemberships', COALESCE((SELECT json_agg(row_to_json(sm))
                        FROM store_membership sm WHERE sm.account_id = u.id), '[]'::json),
                    'recommenderProfile', (SELECT row_to_json(r) FROM recommender_profile r
                        WHERE r.account_id = u.id),
                    'organizations', COALESCE((SELECT json_agg(row_to_json(o)) FROM organization o
                        WHERE o.owner_account_id = u.id), '[]'::json),
                    'merchantProfiles', COALESCE((SELECT json_agg(row_to_json(m)) FROM merchant_profile m
                        JOIN organization o ON o.id = m.organization_id WHERE o.owner_account_id = u.id), '[]'::json),
                    'withdrawalAccounts', COALESCE((SELECT json_agg(row_to_json(w)) FROM withdrawal_account w
                        JOIN organization o ON o.id = w.organization_id WHERE o.owner_account_id = u.id), '[]'::json)
                )::text AS payload
                FROM app_users u WHERE u.id = CAST(:accountId AS uuid)
                """)
                .bind("accountId", accountId)
                .map(row -> row.get("payload", String.class))
                .one();
    }

    private static ExportRequest mapExport(Readable row) {
        return new ExportRequest(
                row.get("id", String.class), row.get("account_id", String.class),
                row.get("status", String.class), row.get("format", String.class),
                row.get("artifact", byte[].class), row.get("artifact_sha256", String.class),
                row.get("artifact_size_bytes", Long.class), toInstant(row.get("expires_at", OffsetDateTime.class)),
                value(row.get("attempt_count", Integer.class)), row.get("claim_token", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("completed_at", OffsetDateTime.class)), row.get("error_code", String.class));
    }

    private static ClosureRequest mapClosure(Readable row) {
        return new ClosureRequest(
                row.get("id", String.class), row.get("account_id", String.class),
                row.get("status", String.class), row.get("blockers", String.class),
                toInstant(row.get("retention_until", OffsetDateTime.class)),
                value(row.get("attempt_count", Integer.class)), row.get("claim_token", String.class),
                toInstant(row.get("requested_at", OffsetDateTime.class)),
                toInstant(row.get("completed_at", OffsetDateTime.class)), row.get("error_code", String.class));
    }

    private static String prefixColumns(String columns, String prefix) {
        return java.util.Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> {
                    int cast = column.indexOf("::");
                    int space = column.indexOf(' ');
                    int end = cast >= 0 ? cast : (space >= 0 ? space : column.length());
                    return prefix + "." + column.substring(0, end) + column.substring(end);
                })
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
