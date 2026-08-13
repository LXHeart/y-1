package com.grassland.edge.internalassertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 直读 Java database-bootstrap 管理的 session 表解析当前调用者身份（HLD 7.4）。
 *
 * <p>解析链路镜像 identity-service 的 {@code CurrentAccountResolver} + {@code SessionRepository}：
 * cookie → {@link EdgeCookieSigner#unsign} → sid → {@code session.sess.user.id}（JSON）
 * → {@code app_users}（role/status）→ {@code identity_session.active_identity_type}。
 * 任一闸门无结果（无 cookie / 验签失败 / session 过期 / 账号不存在）→ {@link Mono#empty()}（匿名，下游视作匿名）。
 *
 * <p>仅 SELECT，edge-bff 不写身份库。{@code identity_session} 无行 = 该 session 为消费者（active 为 null）。
 */
@Component
@ConditionalOnProperty(name = "edge.identity.from-database-url", havingValue = "true")
public class SessionIdentityResolver {

    private final DatabaseClient db;
    private final EdgeCookieSigner cookieSigner;
    private final String cookieName;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionIdentityResolver(DatabaseClient db, EdgeCookieSigner cookieSigner,
                                   @Value("${identity.session.cookie-name:y1.sid}") String cookieName) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
    }

    public Mono<ResolvedIdentity> resolve(ServerHttpRequest request) {
        String sid = extractSid(request);
        if (sid == null) {
            return Mono.empty();
        }
        return findUserId(sid)
                .flatMap(accountId -> loadAccount(accountId, sid));
    }

    private Mono<String> findUserId(String sid) {
        return db.sql("SELECT sess FROM session WHERE sid = :sid AND expire > now()")
                .bind("sid", sid)
                .map(row -> row.get("sess", String.class))
                .one()
                .handle((sessJson, sink) -> {
                    String userId = extractUserId(sessJson);
                    if (userId != null) {
                        sink.next(userId);
                    }
                });
    }

    private Mono<ResolvedIdentity> loadAccount(String accountId, String sid) {
        return db.sql("SELECT id::text, email, display_name, status FROM app_users"
                        + " WHERE id = CAST(:id AS uuid) AND lower(status) = 'active'")
                .bind("id", accountId)
                .map(row -> new AccountRow(
                        row.get("id", String.class),
                        row.get("status", String.class)))
                .one()
                .flatMap(account -> sessionState(sid).defaultIfEmpty(SessionState.EMPTY)
                        .flatMap(session -> {
                            // 仅商家活动身份才带 org/tier：推荐官/消费者断言即使该账号也拥有 merchant 档案，
                            // 也必须 organizationId/tier 为 null——否则推荐官 session 会拿到无关 merchant 组织，
                            // 破坏下游「活动身份 ↔ 组织上下文」不变量（HLD 7.4）。
                            Mono<OrgTier> tier = "merchant".equalsIgnoreCase(session.activeIdentityType())
                                    ? orgTier(accountId).defaultIfEmpty(OrgTier.EMPTY)
                                    : Mono.just(OrgTier.EMPTY);
                            // GL-P2-ADMIN-001：backend_role 是断言授权的唯一权威。
                            return backendRolesClaim(accountId)
                                    .defaultIfEmpty("")
                                    .flatMap(rolesClaim -> tier.map(o -> new ResolvedIdentity(
                                            account.id(),
                                            rolesClaim,
                                            account.status(),
                                            session.activeIdentityType(),
                                            sid,
                                            o.orgId(),
                                            o.tier(),
                                            session.reauthenticatedAt(),
                                            session.authStrength())));
                        }));
    }

    /** 读 backend_role 表的多值角色，拼成逗号分隔 claim（如 "platform_admin,content_reviewer"）。 */
    private Mono<String> backendRolesClaim(String accountId) {
        return db.sql("SELECT role FROM backend_role WHERE account_id = CAST(:acct AS uuid) ORDER BY granted_at")
                .bind("acct", accountId)
                .map(row -> row.get("role", String.class))
                .all()
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** 在 Row 仍有效期间提前抽取字段（r2dbc Row 生命周期仅限 map 阶段，不可透传到下游 flatMap）。 */
    private record AccountRow(String id, String status) {}

    private record OrgTier(String orgId, String tier) {
        static final OrgTier EMPTY = new OrgTier(null, null);
    }

    /** 商家身份关联的 org + 该 org 的 tier（identity_profile↔organization；无商家档案 → empty，org/tier 为 null）。 */
    private Mono<OrgTier> orgTier(String accountId) {
        return db.sql("SELECT ip.organization_id::text AS org_id, o.permission_tier"
                + " FROM identity_profile ip"
                + " LEFT JOIN organization o ON o.id = ip.organization_id"
                + " WHERE ip.account_id = CAST(:acct AS uuid) AND ip.identity_type = 'merchant'")
                .bind("acct", accountId)
                .map(row -> new OrgTier(row.get("org_id", String.class), row.get("permission_tier", String.class)))
                .one();
    }

    /**
     * 读 identity_session 的活动身份 + MFA 重认证证明（V7）。
     * 无行 → empty（消费者：active 为 null，authStrength 回落 level1）。
     */
    private Mono<SessionState> sessionState(String sid) {
        return db.sql("SELECT active_identity_type, reauthenticated_at, auth_strength"
                + " FROM identity_session WHERE session_token = :sid")
                .bind("sid", sid)
                .map(row -> new SessionState(
                        row.get("active_identity_type", String.class),
                        toInstant(row.get("reauthenticated_at", OffsetDateTime.class)),
                        row.get("auth_strength", String.class)))
                .one();
    }

    /** session 侧状态（活动身份 + 重认证证明）。 */
    private record SessionState(String activeIdentityType, Instant reauthenticatedAt, String authStrength) {
        static final SessionState EMPTY = new SessionState(null, null, null);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    String extractUserId(String sessJson) {
        try {
            JsonNode node = mapper.readTree(sessJson);
            JsonNode userId = node.path("user").path("id");
            return userId.isTextual() ? userId.asText() : null;
        } catch (Exception error) {
            return null;
        }
    }

    private String extractSid(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null) {
            return null;
        }
        String value = cookie.getValue();
        try {
            value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        return cookieSigner.unsign(value).orElse(null);
    }
}
