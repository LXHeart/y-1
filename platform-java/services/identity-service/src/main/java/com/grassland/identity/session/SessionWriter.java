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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SessionWriter {
    private static final int SID_BYTES = 24;
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DatabaseClient db;
    private final CookieSigner cookieSigner;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String cookieName;

    public SessionWriter(DatabaseClient db, CookieSigner cookieSigner,
                         @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
    }

    public Mono<CreatedSession> createSession(AuthUser user) {
        String sid = generateSid();
        String sessJson = buildSessJson(user);
        return db.sql("INSERT INTO session (sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
            .bind("sid", sid)
            .bind("sess", sessJson)
            .then()
            .thenReturn(new CreatedSession(sid, buildSetCookieHeader(sid)));
    }

    String generateSid() {
        byte[] bytes = new byte[SID_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String buildSessJson(AuthUser user) {
        try {
            Map<String, Object> sess = new LinkedHashMap<>();
            Map<String, Object> cookie = new LinkedHashMap<>();
            cookie.put("path", "/");
            cookie.put("httpOnly", true);
            cookie.put("sameSite", "lax");
            cookie.put("secure", false);
            cookie.put("originalMaxAge", MAX_AGE_MS);
            cookie.put("expires", DateTimeFormatter.ISO_INSTANT.format(
                Instant.now().plus(Duration.ofMillis(MAX_AGE_MS)).atOffset(ZoneOffset.UTC)));
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

    String buildSetCookieHeader(String sid) {
        String signed = cookieSigner.sign(sid);
        String encoded = URLEncoder.encode("s:" + signed, StandardCharsets.UTF_8);
        return cookieName + "=" + encoded + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (MAX_AGE_MS / 1000);
    }

    public record CreatedSession(String sid, String setCookieHeader) {}
}
