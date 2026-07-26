package com.grassland.identity.identityprofile;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * identity_session 数据访问（按 session 隔离的活动身份）。草场身份域 Slice 2I（HLD D-08）。
 *
 * <p>风格沿用 {@code ActiveIdentityRepository}（R2DBC {@link DatabaseClient} 手写 SQL）。
 * {@code activate} 用 upsert（同 sid 覆盖 active + 设备指纹 + last_seen）；{@code deactivate} 置 NULL；{@code delete} 撤销整行。
 * 行在首次激活时懒创建（仅登录、未激活身份的 session 无行 → 默认消费者）。
 */
@Component
public class IdentitySessionRepository {

    private static final String SELECT_COLS =
            "session_token, account_id::text, active_identity_type, device_id, device_label,"
                    + " ip_address, user_agent, issued_at, last_seen_at, expires_at,"
                    + " reauthenticated_at, auth_strength";

    private final DatabaseClient db;

    public IdentitySessionRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<IdentitySession> findByToken(String sessionToken) {
        return db.sql("SELECT " + SELECT_COLS + " FROM identity_session WHERE session_token = :sid")
                .bind("sid", sessionToken)
                .map(IdentitySessionRepository::map).one();
    }

    public Flux<IdentitySession> findByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM identity_session WHERE account_id = CAST(:acct AS uuid) ORDER BY last_seen_at DESC")
                .bind("acct", accountId)
                .map(IdentitySessionRepository::map).all();
    }

    /** 激活/upsert：覆盖 active_identity_type + 设备指纹 + last_seen；返回更新后行。设备字段可空（COALESCE 保留旧值）。 */
    public Mono<IdentitySession> activate(String sessionToken, String accountId, String identityType,
                                          String deviceId, String deviceLabel, String ipAddress, String userAgent) {
        var spec = db.sql("""
                INSERT INTO identity_session(session_token, account_id, active_identity_type, device_id, device_label,
                                             ip_address, user_agent, issued_at, last_seen_at, expires_at)
                VALUES (:sid, CAST(:acct AS uuid), :type, :dev, :label, :ip, :ua, now(), now(), now() + interval '7 days')
                ON CONFLICT (session_token) DO UPDATE
                  SET active_identity_type = :type,
                      device_id = COALESCE(:dev, identity_session.device_id),
                      device_label = COALESCE(:label, identity_session.device_label),
                      ip_address = COALESCE(:ip, identity_session.ip_address),
                      user_agent = COALESCE(:ua, identity_session.user_agent),
                      last_seen_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("sid", sessionToken).bind("acct", accountId).bind("type", identityType);
        spec = bindNullable(spec, "dev", deviceId);
        spec = bindNullable(spec, "label", deviceLabel);
        spec = bindNullable(spec, "ip", ipAddress);
        spec = bindNullable(spec, "ua", userAgent);
        return spec.map(IdentitySessionRepository::map).one();
    }

    /**
     * 记录重认证（MFA，HLD §11.2）：置 {@code reauthenticated_at=now()} + {@code auth_strength='level2'}。
     *
     * <p>用 upsert 而非 UPDATE：session 行是「首次激活身份时懒创建」的，消费者 session 可能尚无行，
     * 但重认证本身不该要求先激活身份（客服未必开通商家/推荐官身份）。
     * 时间戳按 session 存——一个设备重认证不提升另一设备的权限。
     */
    public Mono<IdentitySession> markReauthenticated(String sessionToken, String accountId) {
        return db.sql("""
                INSERT INTO identity_session(session_token, account_id, issued_at, last_seen_at,
                                             expires_at, reauthenticated_at, auth_strength)
                VALUES (:sid, CAST(:acct AS uuid), now(), now(), now() + interval '7 days', now(), 'level2')
                ON CONFLICT (session_token) DO UPDATE
                  SET reauthenticated_at = now(),
                      auth_strength = 'level2',
                      last_seen_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("sid", sessionToken).bind("acct", accountId)
                .map(IdentitySessionRepository::map).one();
    }

    /** 切回消费者（active 置 NULL）；返回受影响行数（0 = 无行/本就消费者，幂等）。 */
    public Mono<Long> deactivate(String sessionToken) {
        return db.sql("UPDATE identity_session"
                + " SET active_identity_type = NULL, last_seen_at = now()"
                + " WHERE session_token = :sid")
                .bind("sid", sessionToken)
                .fetch().rowsUpdated();
    }

    /** 撤销整行（注销该 session/设备）；返回受影响行数（0 = 已不存在，幂等）。 */
    public Mono<Long> deleteByToken(String sessionToken) {
        return db.sql("DELETE FROM identity_session WHERE session_token = :sid")
                .bind("sid", sessionToken)
                .fetch().rowsUpdated();
    }

    /** 更新 last_seen（行不存在时为 no-op）；GET 活动身份时刷新，支撑多设备「最近活跃」视图。 */
    public Mono<Long> touchLastSeen(String sessionToken) {
        return db.sql("UPDATE identity_session SET last_seen_at = now() WHERE session_token = :sid")
                .bind("sid", sessionToken)
                .fetch().rowsUpdated();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static IdentitySession map(Readable row) {
        return new IdentitySession(
                row.get("session_token", String.class),
                row.get("account_id", String.class),
                row.get("active_identity_type", String.class),
                row.get("device_id", String.class),
                row.get("device_label", String.class),
                row.get("ip_address", String.class),
                row.get("user_agent", String.class),
                toInstant(row.get("issued_at", OffsetDateTime.class)),
                toInstant(row.get("last_seen_at", OffsetDateTime.class)),
                toInstant(row.get("expires_at", OffsetDateTime.class)),
                toInstant(row.get("reauthenticated_at", OffsetDateTime.class)),
                row.get("auth_strength", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
