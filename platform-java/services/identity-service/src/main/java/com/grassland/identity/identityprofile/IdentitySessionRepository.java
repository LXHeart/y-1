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

    /**
     * 串行化同一账号的活动身份切换，避免两台设备并发激活时同时绕过并发上限。
     * PostgreSQL advisory transaction lock 会随外层事务自动释放。
     */
    public Mono<Void> lockAccount(String accountId) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:acct, 0))")
                .bind("acct", accountId)
                .then();
    }

    /** 按最近活跃倒序保留前 maxActive 条，返回需要被策略切回消费者的旧 session。 */
    public Flux<IdentitySession> findActiveOverflow(String accountId, int maxActive) {
        if (maxActive <= 0) {
            return Flux.empty();
        }
        return db.sql("SELECT " + SELECT_COLS
                        + " FROM identity_session"
                        + " WHERE account_id = CAST(:acct AS uuid) AND active_identity_type IS NOT NULL"
                        + " ORDER BY last_seen_at DESC, session_token DESC OFFSET :keep")
                .bind("acct", accountId)
                .bind("keep", maxActive)
                .map(IdentitySessionRepository::map).all();
    }

    /**
     * 列该账号**所有未过期的登录会话**（设备清单的真正来源），左连 identity_session 取活动身份与设备信息。
     *
     * <p>为什么不用 {@link #findByAccount}：{@code identity_session} 行是**首次激活身份时才懒创建**的，
     * 只登录、没切过身份的设备根本不在里面。安全界面里列一个「看起来是全部、其实是子集」的设备清单，
     * 比不做更危险——用户会据此判断「没有异常登录」。故以 legacy {@code session} 表为准（登录即有行），
     * 设备信息缺失时留空，由前端显示「未知设备」。
     *
     * <p>{@code sess} 是 connect-pg-simple 写的 JSON，用户 id 在 {@code sess->'user'->>'id'}。
     */
    public Flux<IdentitySession> findLoginSessionsByAccount(String accountId) {
        return db.sql("""
                SELECT s.sid AS session_token,
                       :acct AS account_id,
                       i.active_identity_type, i.device_id, i.device_label, i.ip_address, i.user_agent,
                       i.issued_at, i.last_seen_at,
                       i.reauthenticated_at, i.auth_strength
                FROM session s
                LEFT JOIN identity_session i ON i.session_token = s.sid
                WHERE s.sess->'user'->>'id' = :acct AND s.expire > now()
                ORDER BY i.last_seen_at DESC NULLS LAST, s.expire DESC
                """)
                .bind("acct", accountId)
                .map(IdentitySessionRepository::mapLoginSession).all();
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

    /**
     * 登录会话行（session LEFT JOIN identity_session）。identity_session 侧全部可空——
     * 只登录、没激活过身份的设备在右表没有行。{@code expiresAt} 不取（legacy {@code session.expire} 是
     * {@code timestamp} 而非 {@code timestamptz}，类型口径不同，设备清单也用不到），留 null。
     */
    private static IdentitySession mapLoginSession(Readable row) {
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
                null,
                toInstant(row.get("reauthenticated_at", OffsetDateTime.class)),
                row.get("auth_strength", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
