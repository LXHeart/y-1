package com.grassland.identity.assertion.token;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;

/**
 * 移动端 access token claims（GL-P3-IDENTITY-001，docs/架构/移动端刷新token认证方案设计.md）。
 *
 * <p>wire 格式为 {@code <payloadB64url>.<macB64url>}（HMAC-SHA256），payload 用 snake_case JSON
 * （与设计文档 §3.3 一致；移动端把 token 当不透明串，但保持公开契约便于未来非 Java 验签方）。
 *
 * <p>{@code sessionToken} = 签发该 token 所用 refresh_token 的行 id：edge-bff 据此复查
 * refresh_token 行存活（撤销即时生效），并像 cookie sid 一样从 identity_session 现查活动身份。
 * <b>刻意不携带</b> {@code active_identity_type}——它按 session 可变，烤进 token 会在切换后失真。
 *
 * <p>{@code iat}/{@code exp} 用 epoch 秒 long（与设计文档一致，避免 Jackson 时间戳格式化歧义）。
 */
public record AccessToken(
        @JsonProperty("account_id") String accountId,
        String email,
        String role,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("session_token") String sessionToken,
        String kid,
        long iat,
        long exp) {

    /** 以 now 为签发时刻、now+ttl 为过期时刻构造 claims。 */
    public static AccessToken issue(String accountId, String email, String role, String deviceId,
                                    String sessionToken, String kid, Instant now, Duration ttl) {
        long iat = now.getEpochSecond();
        return new AccessToken(accountId, email, role, deviceId, sessionToken, kid, iat, now.plus(ttl).getEpochSecond());
    }

    public Instant issuedAt() {
        return Instant.ofEpochSecond(iat);
    }

    public Instant expiresAt() {
        return Instant.ofEpochSecond(exp);
    }
}
