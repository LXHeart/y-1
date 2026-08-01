package com.grassland.identity.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.grassland.identity.security.CookieSigner;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SessionService {
    private static final long CAPTCHA_TTL_MS = 5 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final DatabaseClient db;
    private final CookieSigner cookieSigner;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SessionCookiePolicy cookiePolicy;

    public SessionService(DatabaseClient db, CookieSigner cookieSigner, SessionCookiePolicy cookiePolicy) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookiePolicy = cookiePolicy;
    }

    public record CaptchaResult(String svg, String setCookieHeader) {}
    public record CaptchaData(String text) {}

    /** {@code request} 仅用于判定新建 captcha 会话 cookie 是否加 Secure（GL-P0-AUTH-001）。 */
    public Mono<CaptchaResult> generateAndStoreCaptcha(String rawCookieValue, String captchaText, String svg,
                                                      ServerHttpRequest request) {
        boolean secure = cookiePolicy.isSecure(request);
        String existingSid = resolveSid(rawCookieValue);
        if (existingSid != null) {
            return storeCaptchaInExisting(existingSid, captchaText)
                .flatMap(ok -> ok ? Mono.just(new CaptchaResult(svg, null))
                                  : createNewCaptchaSession(captchaText, svg, secure));
        }
        return createNewCaptchaSession(captchaText, svg, secure);
    }

    public Mono<Optional<CaptchaData>> consumeCaptcha(String rawCookieValue) {
        String sid = resolveSid(rawCookieValue);
        if (sid == null) return Mono.just(Optional.empty());
        return db.sql("SELECT sess FROM session WHERE sid = :sid AND expire > now()")
            .bind("sid", sid).map(r -> r.get("sess", String.class)).one()
            .flatMap(sessJson -> {
                try {
                    JsonNode root = mapper.readTree(sessJson);
                    JsonNode cap = root.path("captcha");
                    if (cap.isMissingNode() || !cap.has("text")) return Mono.just(Optional.<CaptchaData>empty());
                    long expiresAt = cap.path("expiresAt").asLong();
                    String text = cap.get("text").asText();
                    if (System.currentTimeMillis() > expiresAt) {
                        return removeCaptchaAndReturn(sid, sessJson, Optional.<CaptchaData>empty());
                    }
                    return removeCaptchaAndReturn(sid, sessJson, Optional.of(new CaptchaData(text)));
                } catch (Exception e) { return Mono.just(Optional.<CaptchaData>empty()); }
            })
            .defaultIfEmpty(Optional.<CaptchaData>empty());
    }

    private Mono<Optional<CaptchaData>> removeCaptchaAndReturn(String sid, String sessJson, Optional<CaptchaData> result) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(sessJson);
            root.remove("captcha");
            String updated = mapper.writeValueAsString(root);
            return db.sql("UPDATE session SET sess = CAST(:sess AS json) WHERE sid = :sid")
                .bind("sess", updated).bind("sid", sid).then().thenReturn(result);
        } catch (Exception e) { return Mono.just(result); }
    }

    private Mono<Boolean> storeCaptchaInExisting(String sid, String captchaText) {
        return db.sql("SELECT sess FROM session WHERE sid = :sid AND expire > now()")
            .bind("sid", sid).map(r -> r.get("sess", String.class)).one()
            .flatMap(sessJson -> {
                try {
                    ObjectNode root = (ObjectNode) mapper.readTree(sessJson);
                    ObjectNode cap = root.putObject("captcha");
                    cap.put("text", captchaText.toLowerCase());
                    cap.put("expiresAt", System.currentTimeMillis() + CAPTCHA_TTL_MS);
                    return db.sql("UPDATE session SET sess = CAST(:sess AS json) WHERE sid = :sid")
                        .bind("sess", mapper.writeValueAsString(root)).bind("sid", sid)
                        .then().thenReturn(true);
                } catch (Exception e) { return Mono.just(false); }
            })
            .defaultIfEmpty(false);
    }

    private Mono<CaptchaResult> createNewCaptchaSession(String captchaText, String svg, boolean secure) {
        String sid = generateSid();
        Map<String, Object> sess = new LinkedHashMap<>();
        Map<String, Object> cookie = new LinkedHashMap<>();
        cookie.put("path", "/"); cookie.put("httpOnly", true);
        cookie.put("sameSite", cookiePolicy.sameSite().toLowerCase(java.util.Locale.ROOT));
        // secure 必须落库：express-session rolling 续期按库里属性重发 Set-Cookie。
        cookie.put("secure", secure);
        cookie.put("originalMaxAge", cookiePolicy.maxAgeMs());
        sess.put("cookie", cookie);
        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("text", captchaText.toLowerCase());
        cap.put("expiresAt", System.currentTimeMillis() + CAPTCHA_TTL_MS);
        sess.put("captcha", cap);
        try {
            String sessJson = mapper.writeValueAsString(sess);
            return db.sql("INSERT INTO session(sid, sess, expire) VALUES (:sid, CAST(:sess AS json), now() + interval '7 days')")
                .bind("sid", sid).bind("sess", sessJson).then()
                .thenReturn(new CaptchaResult(svg, buildSetCookie(sid, secure)));
        } catch (Exception e) { return Mono.error(e); }
    }

    private String resolveSid(String rawCookieValue) {
        if (rawCookieValue == null) return null;
        try {
            String decoded = java.net.URLDecoder.decode(rawCookieValue.replace("+", "%2B"), StandardCharsets.UTF_8);
            return cookieSigner.unsign(decoded).orElse(null);
        } catch (Exception e) { return null; }
    }

    private String generateSid() {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildSetCookie(String sid, boolean secure) {
        String signed = cookieSigner.sign(sid);
        String encoded = URLEncoder.encode("s:" + signed, StandardCharsets.UTF_8);
        return cookiePolicy.buildSetCookie(encoded, secure);
    }
}
