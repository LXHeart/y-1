package com.grassland.identity.mobile;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * refresh_token 数据访问（GL-P3-IDENTITY-001）。raw-SQL {@link DatabaseClient}，
 * 风格对齐 {@code IdentityAuditLogRepository}（{@code CAST(:x AS uuid)} + {@link #bindNullable}）。
 *
 * <p>撤销一律软删（置 {@code revoked_at}）；硬删仅由 {@link RefreshTokenCleanup} 按 retention 执行。
 */
@Component
public class RefreshTokenRepository {

    private static final String SELECT_COLS =
            "id::text, account_id::text, token_hash, device_fingerprint, device_name,"
                    + " last_used_at, expires_at, revoked_at, created_at, metadata::text";

    private final DatabaseClient db;

    public RefreshTokenRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<RefreshToken> insert(RefreshToken token) {
        var spec = db.sql("""
                INSERT INTO refresh_token(id, account_id, token_hash, device_fingerprint, device_name,
                                          expires_at, metadata)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :hash, :fp, :name, :expires, CAST(:meta AS jsonb))
                """)
                .bind("id", token.id())
                .bind("acct", token.accountId())
                .bind("hash", token.tokenHash())
                .bind("expires", token.expiresAt().atOffset(java.time.ZoneOffset.UTC));
        spec = bindNullable(spec, "fp", token.deviceFingerprint());
        spec = bindNullable(spec, "name", token.deviceName());
        spec = bindNullable(spec, "meta", token.metadataJson());
        return spec.then().thenReturn(token);
    }

    public Mono<RefreshToken> findByTokenHash(String tokenHash) {
        return db.sql("SELECT " + SELECT_COLS + " FROM refresh_token WHERE token_hash = :hash")
                .bind("hash", tokenHash)
                .map(RefreshTokenRepository::map).one();
    }

    public Mono<RefreshToken> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM refresh_token WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(RefreshTokenRepository::map).one();
    }

    /** 刷新访问时间（尽力遥测，不参与鉴权）。 */
    public Mono<Long> touchLastUsed(String id) {
        return db.sql("UPDATE refresh_token SET last_used_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .fetch().rowsUpdated();
    }

    /** 软删（幂等）：已撤销的行再次撤销返回 0。 */
    public Mono<Long> revokeById(String id) {
        return db.sql("UPDATE refresh_token SET revoked_at = now()"
                        + " WHERE id = CAST(:id AS uuid) AND revoked_at IS NULL")
                .bind("id", id)
                .fetch().rowsUpdated();
    }

    /** 撤销账号全部活跃 token（all_devices）。 */
    public Mono<Long> revokeAllForAccount(String accountId) {
        return db.sql("UPDATE refresh_token SET revoked_at = now()"
                        + " WHERE account_id = CAST(:acct AS uuid) AND revoked_at IS NULL AND expires_at > now()")
                .bind("acct", accountId)
                .fetch().rowsUpdated();
    }

    /**
     * 封顶收敛：把超出 {@code keep} 个的活跃 token 撤旧（按 created_at DESC 保留最新 keep 个）。
     * 单条语句 = 并发登录竞态下最终态恰为 keep 个活跃（不会持续超限）。
     */
    public Mono<Long> pruneOldestBeyond(String accountId, int keep) {
        return db.sql("""
                UPDATE refresh_token SET revoked_at = now()
                WHERE account_id = CAST(:acct AS uuid) AND revoked_at IS NULL AND expires_at > now()
                  AND id NOT IN (
                      SELECT id FROM refresh_token
                      WHERE account_id = CAST(:acct AS uuid) AND revoked_at IS NULL AND expires_at > now()
                      ORDER BY created_at DESC, id DESC
                      LIMIT :keep)
                """)
                .bind("acct", accountId)
                .bind("keep", keep)
                .fetch().rowsUpdated();
    }

    /** 账号当前活跃（未撤销未过期）token，新→旧。 */
    public Flux<RefreshToken> findActiveByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM refresh_token"
                        + " WHERE account_id = CAST(:acct AS uuid) AND revoked_at IS NULL AND expires_at > now()"
                        + " ORDER BY created_at DESC, id DESC")
                .bind("acct", accountId)
                .map(RefreshTokenRepository::map).all();
    }

    /** 硬删过期超 cutoff 的行（清理任务用）。 */
    public Mono<Long> deleteExpiredBefore(Instant cutoff) {
        return db.sql("DELETE FROM refresh_token WHERE expires_at < :cutoff")
                .bind("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
                .fetch().rowsUpdated();
    }

    /** 硬删撤销超 cutoff 的行（清理任务用）。 */
    public Mono<Long> deleteRevokedBefore(Instant cutoff) {
        return db.sql("DELETE FROM refresh_token WHERE revoked_at IS NOT NULL AND revoked_at < :cutoff")
                .bind("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
                .fetch().rowsUpdated();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static RefreshToken map(Readable row) {
        return new RefreshToken(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("token_hash", String.class),
                row.get("device_fingerprint", String.class),
                row.get("device_name", String.class),
                toInstant(row.get("last_used_at", OffsetDateTime.class)),
                toInstant(row.get("expires_at", OffsetDateTime.class)),
                toInstant(row.get("revoked_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                row.get("metadata", String.class));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
