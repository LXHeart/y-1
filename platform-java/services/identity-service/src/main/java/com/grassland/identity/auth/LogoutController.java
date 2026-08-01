package com.grassland.identity.auth;

import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.session.SessionCookiePolicy;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
public class LogoutController {
    private final DatabaseClient db;
    private final CookieSigner cookieSigner;
    private final SessionCookiePolicy cookiePolicy;

    public LogoutController(DatabaseClient db, CookieSigner cookieSigner, SessionCookiePolicy cookiePolicy) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookiePolicy = cookiePolicy;
    }

    @PostMapping("/api/auth/logout")
    public Mono<ResponseEntity<Map<String, Object>>> logout(ServerHttpRequest request) {
        boolean secure = cookiePolicy.isSecure(request);
        HttpCookie cookie = request.getCookies().getFirst(cookiePolicy.cookieName());
        if (cookie == null) {
            return Mono.just(ok(secure));
        }
        String value = cookie.getValue().replace("+", "%2B");
        try {
            value = java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        Optional<String> sid = cookieSigner.unsign(value);
        Mono<Void> delete = sid
            .map(s -> db.sql("DELETE FROM session WHERE sid = :sid").bind("sid", s).then())
            .orElse(Mono.empty());
        return delete.thenReturn(ok(secure));
    }

    private ResponseEntity<Map<String, Object>> ok(boolean secure) {
        return ResponseEntity.ok()
            .header("Set-Cookie", cookiePolicy.buildClearCookie(secure))
            .body(Map.of("success", true, "data", Map.of("loggedOut", true)));
    }
}
