package com.grassland.edge.internalassertion;

import com.grassland.identity.assertion.token.AccessToken;
import com.grassland.identity.assertion.token.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 移动端 access token 身份解析（由 {@link AccessTokenFilter} 在公网边界消费）。
 *
 * <p>从 {@code Authorization: Bearer <access_token>} 头验签 → 复查 refresh_token 行存活 →
 * 查 app_users/identity_session/backend_role 组装 {@link ResolvedIdentity}。
 *
 * <p>与 {@link SessionIdentityResolver}（cookie 链路）镜像，区别是身份来源从 cookie session 改为
 * access token + refresh_token 行复查。复查是撤销即时生效的保证（AccessToken TTL 15 分钟内，
 * 撤销不能等到 token 自然过期）。
 *
 * <p>装配条件：edge.identity.from-database-url=true（R2DBC 可用）+ AccessTokenSigner bean 存在（secret 配了）。
 * 未配 secret → bean 不装；携带 Bearer 的内部 Java 路由由 {@link AccessTokenFilter} fail-closed 返回 401，
 * 不携带 Bearer 的 Web cookie 路径不受影响。
 */
@Component
@ConditionalOnProperty(name = "edge.identity.from-database-url", havingValue = "true")
@ConditionalOnBean(AccessTokenSigner.class)
public class AccessTokenIdentityResolver {

    private final DatabaseClient db;
    private final AccessTokenSigner accessTokenSigner;

    public AccessTokenIdentityResolver(DatabaseClient db, AccessTokenSigner accessTokenSigner) {
        this.db = db;
        this.accessTokenSigner = accessTokenSigner;
    }

    /**
     * 解析 Bearer access token → ResolvedIdentity。无 Bearer / 验签失败 / 行已撤销 → empty（匿名）。
     */
    public Mono<ResolvedIdentity> resolve(ServerHttpRequest request) {
        String bearer = extractBearer(request);
        if (bearer == null) {
            return Mono.empty();
        }
        return Mono.justOrEmpty(accessTokenSigner.verify(bearer, null))
                .flatMap(token -> verifyRefreshTokenAlive(token.sessionToken())
                        .flatMap(alive -> alive
                                ? loadIdentity(token)
                                : Mono.<ResolvedIdentity>empty()))
                .switchIfEmpty(Mono.empty());
    }

    // ---------- 身份组装（镜像 SessionIdentityResolver.loadAccount，但 accountId 来自 AccessToken）----------

    private Mono<ResolvedIdentity> loadIdentity(AccessToken token) {
        String accountId = token.accountId();
        String refreshTokenId = token.sessionToken();
        return db.sql("SELECT id::text, email, display_name, status FROM app_users"
                        + " WHERE id = CAST(:id AS uuid) AND lower(status) = 'active'")
                .bind("id", accountId)
                .map(row -> new AccountRow(
                        row.get("id", String.class),
                        row.get("status", String.class)))
                .one()
                // 与 SessionIdentityResolver.mustChangePassword 同款降级：account_flag 缺席环境闸门失效，
                // 不能因旗标查询失败打死移动端（任务书 #48）。
                .flatMap(account -> mustChangePassword(accountId).flatMap(flag ->
                        backendRolesClaim(accountId)
                        .defaultIfEmpty("")
                        .flatMap(rolesClaim -> sessionState(refreshTokenId).defaultIfEmpty(SessionState.EMPTY)
                                .flatMap(session -> {
                                    Mono<OrgTier> tier = "merchant".equalsIgnoreCase(session.activeIdentityType())
                                            ? orgTier(accountId).defaultIfEmpty(OrgTier.EMPTY)
                                            : Mono.just(OrgTier.EMPTY);
                                    return tier.map(o -> new ResolvedIdentity(
                                            account.id(),
                                            rolesClaim,
                                            account.status(),
                                            session.activeIdentityType(),
                                            refreshTokenId,
                                            o.orgId(),
                                            o.tier(),
                                            session.reauthenticatedAt(),
                                            session.authStrength(),
                                            flag));
                                }))));
    }

    /** 首登强制改密标记；无行=false，查询失败降级 false（同 SessionIdentityResolver）。 */
    private Mono<Boolean> mustChangePassword(String accountId) {
        return db.sql("SELECT must_change_password FROM account_flag WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> Boolean.TRUE.equals(row.get("must_change_password", Boolean.class)))
                .one()
                .defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(false));
    }

    /** refresh_token 行存活复查（撤销即时生效）。 */
    private Mono<Boolean> verifyRefreshTokenAlive(String refreshTokenId) {
        return db.sql("SELECT 1 FROM refresh_token"
                        + " WHERE id = CAST(:id AS uuid) AND revoked_at IS NULL AND expires_at > now()")
                .bind("id", refreshTokenId)
                .map(row -> true)
                .one()
                .defaultIfEmpty(false);
    }

    /** identity_session 活动身份（key = refresh_token 行 id = identity_session.session_token）。 */
    private Mono<SessionState> sessionState(String refreshTokenId) {
        return db.sql("SELECT active_identity_type, reauthenticated_at, auth_strength"
                        + " FROM identity_session WHERE session_token = :sid")
                .bind("sid", refreshTokenId)
                .map(row -> new SessionState(
                        row.get("active_identity_type", String.class),
                        toInstant(row.get("reauthenticated_at", OffsetDateTime.class)),
                        row.get("auth_strength", String.class)))
                .one();
    }

    /** backend_role 多值（逗号分隔 claim）。 */
    private Mono<String> backendRolesClaim(String accountId) {
        return db.sql("SELECT role FROM backend_role WHERE account_id = CAST(:acct AS uuid) ORDER BY granted_at")
                .bind("acct", accountId)
                .map(row -> row.get("role", String.class))
                .all()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** 商家身份关联的 org + tier。 */
    private Mono<OrgTier> orgTier(String accountId) {
        return db.sql("SELECT ip.organization_id::text AS org_id, o.permission_tier"
                        + " FROM identity_profile ip"
                        + " LEFT JOIN organization o ON o.id = ip.organization_id"
                        + " WHERE ip.account_id = CAST(:acct AS uuid) AND ip.identity_type = 'merchant'")
                .bind("acct", accountId)
                .map(row -> new OrgTier(row.get("org_id", String.class), row.get("permission_tier", String.class)))
                .one();
    }

    /** 从 Authorization 头提取 Bearer token（大小写不敏感前缀匹配）。 */
    private static String extractBearer(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth == null || auth.length() <= 7) {
            return null;
        }
        if (!auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = auth.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    // ---------- 内部 records（镜像 SessionIdentityResolver 的私有 records）----------

    record AccountRow(String id, String status) {}

    record SessionState(String activeIdentityType, Instant reauthenticatedAt, String authStrength) {
        static final SessionState EMPTY = new SessionState(null, null, null);
    }

    record OrgTier(String orgId, String tier) {
        static final OrgTier EMPTY = new OrgTier(null, null);
    }
}
