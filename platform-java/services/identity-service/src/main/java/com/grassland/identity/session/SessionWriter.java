package com.grassland.identity.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.user.AuthUser;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SessionWriter {
    private static final int SID_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseClient db;
    private final CookieSigner cookieSigner;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionCookiePolicy cookiePolicy;

    public SessionWriter(DatabaseClient db, CookieSigner cookieSigner, SessionCookiePolicy cookiePolicy) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookiePolicy = cookiePolicy;
    }

    /**
     * 建会话并返回 Set-Cookie。
     *
     * <p>{@code request} 用于判定本次响应是否加 Secure（{@code auto} 模式下看 X-Forwarded-Proto / scheme），
     * 且同一判定写进 {@code sess.cookie.secure}，保证 express-session rolling 续期不会抹掉 Secure。
     */
    public Mono<CreatedSession> createSession(AuthUser user, ServerHttpRequest request) {
        boolean secure = cookiePolicy.isSecure(request);
        String sid = generateSid();
        String sessJson = buildSessJson(user, secure);
        return db.sql("INSERT INTO session (sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
            .bind("sid", sid)
            .bind("sess", sessJson)
            .then()
            .thenReturn(new CreatedSession(sid, buildSetCookieHeader(sid, secure)));
    }

    String generateSid() {
        byte[] bytes = new byte[SID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String buildSessJson(AuthUser user, boolean secure) {
        try {
            long maxAgeMs = cookiePolicy.maxAgeMs();
            Map<String, Object> sess = new LinkedHashMap<>();
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("path", "/");
            cookie.put("httpOnly", true);
            cookie.put("sameSite", cookiePolicy.sameSite().toLowerCase(java.util.Locale.ROOT));
            cookie.put("secure", secure);
            cookie.put("originalMaxAge", maxAgeMs);
            cookie.put("expires", DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plus(Duration.ofMillis(maxAgeMs)).atOffset(ZoneOffset.UTC)));
            sess.put("cookie", cookie);
            Map<String, Object> sessUser = new LinkedHashMap<>();
            sessUser.put("id", user.id());
            sessUser.put("email", user.email());
            if (user.displayName() != null && !user.displayName().isBlank()) {
                sessUser.put("displayName", user.displayName());
            }
            sessUser.put("role", user.role());
            sess.put("user", sessUser);
            return mapper.writeValueAsString(sess);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build session JSON", e);
        }
    }

    String buildSetCookieHeader(String sid, boolean secure) {
        String signed = cookieSigner.sign(sid);
        String encoded = URLEncoder.encode("s:" + signed, StandardCharsets.UTF_8);
        return cookiePolicy.buildSetCookie(encoded, secure);
    }

    public record CreatedSession(String sid, String setCookieHeader) {}
}
