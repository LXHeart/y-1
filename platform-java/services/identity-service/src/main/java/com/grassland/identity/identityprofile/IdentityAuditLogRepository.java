package com.grassland.identity.identityprofile;

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
 * identity_audit_log 数据访问（append-only 审计）。草场身份域 Slice 2I（HLD 10.1 身份切换审计）。
 * from/to/session/device 字段可空，用 {@link #bindNullable} 条件绑定（避免 R2DBC bind null 报错）。
 */
@Component
public class IdentityAuditLogRepository {

    private static final String SELECT_COLS =
            "id::text, account_id::text, action, from_identity_type, to_identity_type,"
                    + " session_token, device_id, ip_address, user_agent, occurred_at";

    private final DatabaseClient db;

    public IdentityAuditLogRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 追加一条审计（fire-and-forget 写，调用方 {@code .then()} 串入主链）。 */
    public Mono<Void> append(IdentityAuditAction action, String accountId, String fromType, String toType,
                             String sessionToken, String deviceId, String ipAddress, String userAgent) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO identity_audit_log(id, account_id, action, from_identity_type, to_identity_type,
                                               session_token, device_id, ip_address, user_agent)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :action, :fromType, :toType, :sid, :dev, :ip, :ua)
                """)
                .bind("id", id).bind("acct", accountId).bind("action", action.dbValue());
        spec = bindNullable(spec, "fromType", fromType);
        spec = bindNullable(spec, "toType", toType);
        spec = bindNullable(spec, "sid", sessionToken);
        spec = bindNullable(spec, "dev", deviceId);
        spec = bindNullable(spec, "ip", ipAddress);
        spec = bindNullable(spec, "ua", userAgent);
        return spec.then();
    }

    public Flux<IdentityAuditLog> findByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM identity_audit_log WHERE account_id = CAST(:acct AS uuid) ORDER BY occurred_at")
                .bind("acct", accountId)
                .map(IdentityAuditLogRepository::map).all();
    }

    /** 本人审计 keyset 分页；occurred_at + id 组成稳定游标，避免 OFFSET 漂移和全量读取。 */
    public Flux<IdentityAuditLog> findPage(String accountId, String action, IdentityAuditCursor cursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM identity_audit_log WHERE account_id = CAST(:acct AS uuid)");
        if (action != null) {
            sql.append(" AND action = :action");
        }
        if (cursor != null) {
            sql.append(" AND (occurred_at < :before OR (occurred_at = :before AND id < CAST(:beforeId AS uuid)))");
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :limit");

        GenericExecuteSpec spec = db.sql(sql.toString()).bind("acct", accountId).bind("limit", limit);
        if (action != null) {
            spec = spec.bind("action", action);
        }
        if (cursor != null) {
            spec = spec.bind("before", OffsetDateTime.ofInstant(cursor.occurredAt(), java.time.ZoneOffset.UTC))
                    .bind("beforeId", cursor.id());
        }
        return spec.map(IdentityAuditLogRepository::map).all();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static IdentityAuditLog map(Readable row) {
        return new IdentityAuditLog(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("action", String.class),
                row.get("from_identity_type", String.class),
                row.get("to_identity_type", String.class),
                row.get("session_token", String.class),
                row.get("device_id", String.class),
                row.get("ip_address", String.class),
                row.get("user_agent", String.class),
                toInstant(row.get("occurred_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
