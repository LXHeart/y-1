package com.grassland.edge.internalassertion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 直读 legacy session 表解析当前调用者身份（HLD 7.4「BFF 直读 session 表」）。
 *
 * <p>解析链路镜像 identity-service 的 {@code CurrentAccountResolver} + {@code LegacySessionBridge}：
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
                                   @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
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
        return db.sql("SELECT id::text, email, display_name, role, status FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(row -> new AccountRow(
                        row.get("id", String.class),
                        row.get("role", String.class),
                        row.get("status", String.class)))
                .one()
                .flatMap(account -> activeIdentityType(sid)
                        .defaultIfEmpty("")
                        .map(active -> new ResolvedIdentity(
                                account.id(),
                                account.role(),
                                account.status(),
                                active.isEmpty() ? null : active,
                                sid)));
    }

    /** 在 Row 仍有效期间提前抽取字段（r2dbc Row 生命周期仅限 map 阶段，不可透传到下游 flatMap）。 */
    private record AccountRow(String id, String role, String status) {}

    /** 读 identity_session.active_identity_type；无行/值为 null → empty（消费者，active 置 null）。 */
    private Mono<String> activeIdentityType(String sid) {
        return db.sql("SELECT active_identity_type FROM identity_session WHERE session_token = :sid")
                .bind("sid", sid)
                .map(row -> row.get("active_identity_type", String.class))
                .one();
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
