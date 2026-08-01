package com.grassland.identity.session;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * 会话 cookie 属性的唯一真相源（GL-P0-AUTH-001）。
 *
 * <p>Java 侧写出的 Set-Cookie 与 session 表里 {@code sess.cookie} 的属性必须一致：
 * express-session 的 rolling 续期会用库里存的 cookie 属性重发 Set-Cookie，
 * 若 Java 写 {@code secure:false} 而实际需要 Secure，续期时 Secure 会被抹掉（降级为可明文回传）。
 *
 * <p>secure 模式（与 Express {@code SESSION_COOKIE_SECURE} 同名同义）：
 * <ul>
 *   <li>{@code auto}（默认）— 按当前请求是否 HTTPS 决定。判定顺序 X-Forwarded-Proto → request scheme。
 *       等价于 express-session 的 {@code secure: 'auto'}，本地 HTTP 与生产 HTTPS 同一份配置都对。</li>
 *   <li>{@code always} — 恒定加 Secure，不看连接是否 HTTPS。
 *       ⚠️ 与 Express 不完全同义：express-session 在 {@code secure:true} 且连接非 HTTPS 时
 *       会整个不发 Set-Cookie（登录静默失效），Java 侧则照发。两端在 {@code auto} 下语义一致，
 *       混合部署（Express + identity-service 共用 session 表）推荐统一用 {@code auto}。</li>
 *   <li>{@code never} — 恒不加。仅用于明确无 TLS 的内网/调试。</li>
 * </ul>
 *
 * <p>{@code auto} 信任 X-Forwarded-Proto 的前提：identity-service 不发布宿主端口，
 * 仅容器网络内可达，且 nginx 用 {@code proxy_set_header X-Forwarded-Proto $scheme} 覆盖客户端值。
 * 若这两条不成立，用 {@code always}。
 */
@Component
public class SessionCookiePolicy {
    /** express-session 默认 7 天，与 SESSION_COOKIE_MAX_AGE_MS 缺省一致。 */
    public static final long DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    private static final String EXPIRED = "Thu, 01 Jan 1970 00:00:00 GMT";

    private final String cookieName;
    private final SecureMode secureMode;
    private final String sameSite;
    private final long maxAgeMs;

    public SessionCookiePolicy(
        @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName,
        @Value("${identity.legacy.session.cookie-secure:auto}") String cookieSecure,
        @Value("${identity.legacy.session.cookie-same-site:Lax}") String sameSite,
        @Value("${identity.legacy.session.cookie-max-age-ms:0}") long maxAgeMs) {
        this.cookieName = cookieName;
        this.secureMode = SecureMode.parse(cookieSecure);
        this.sameSite = normalizeSameSite(sameSite);
        this.maxAgeMs = maxAgeMs > 0 ? maxAgeMs : DEFAULT_MAX_AGE_MS;
    }

    public String cookieName() {
        return cookieName;
    }

    public long maxAgeMs() {
        return maxAgeMs;
    }

    public String sameSite() {
        return sameSite;
    }

    /** 本次响应的 cookie 是否应带 Secure。 */
    public boolean isSecure(ServerHttpRequest request) {
        return switch (secureMode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> isHttps(request);
        };
    }

    /** 会话 cookie 的 Set-Cookie 值。{@code secure} 由 {@link #isSecure} 得出，调用方不要自行判定。 */
    public String buildSetCookie(String signedValue, boolean secure) {
        StringBuilder sb = new StringBuilder(cookieName).append('=').append(signedValue)
            .append("; Path=/; HttpOnly; SameSite=").append(sameSite)
            .append("; Max-Age=").append(maxAgeMs / 1000);
        if (secure) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    /**
     * 清除 cookie 的 Set-Cookie 值。
     *
     * <p>属性必须与写入时一致（含 Secure）：浏览器按 name/path/domain 匹配覆盖，
     * 属性不一致时可能留下同名 cookie，登出后旧 sid 仍随请求发出（DB 行已删，但 cookie 未清）。
     */
    public String buildClearCookie(boolean secure) {
        StringBuilder sb = new StringBuilder(cookieName)
            .append("=; Path=/; Expires=").append(EXPIRED)
            .append("; HttpOnly; SameSite=").append(sameSite);
        if (secure) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    private static boolean isHttps(ServerHttpRequest request) {
        if (request == null) {
            return false;
        }
        List<String> forwarded = request.getHeaders().getOrEmpty("X-Forwarded-Proto");
        if (!forwarded.isEmpty()) {
            // 多跳会追加成 "https, http"；最左是客户端到最外层入口的协议。
            String first = forwarded.get(0).split(",")[0].trim();
            if (!first.isEmpty()) {
                return "https".equalsIgnoreCase(first);
            }
        }
        String scheme = request.getURI().getScheme();
        return "https".equalsIgnoreCase(scheme);
    }

    private static String normalizeSameSite(String value) {
        if (value == null || value.isBlank()) {
            return "Lax";
        }
        String trimmed = value.trim();
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException("Unsupported SameSite value: " + trimmed);
        };
    }

    enum SecureMode {
        AUTO, ALWAYS, NEVER;

        static SecureMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return AUTO;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                // true/1 视作 always，false/0 视作 never：容忍布尔风格取值。
                case "always", "true", "1" -> ALWAYS;
                case "never", "false", "0" -> NEVER;
                default -> throw new IllegalArgumentException("Unsupported cookie-secure value: " + raw);
            };
        }
    }

    /** 供测试断言 header 名，避免字面量散落。 */
    static String setCookieHeaderName() {
        return HttpHeaders.SET_COOKIE;
    }
}
