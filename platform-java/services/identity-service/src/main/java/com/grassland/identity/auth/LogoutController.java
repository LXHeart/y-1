package com.grassland.identity.auth;

import com.grassland.identity.security.CookieSigner;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
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
    private final String cookieName;

    public LogoutController(DatabaseClient db, CookieSigner cookieSigner,
                            @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.db = db;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
    }

    @PostMapping("/api/auth/logout")
    public Mono<ResponseEntity<Map<String, Object>>> logout(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null) {
            return Mono.just(ok());
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
        return delete.thenReturn(ok());
    }

    private ResponseEntity<Map<String, Object>> ok() {
        return ResponseEntity.ok()
            .header("Set-Cookie", cookieName + "=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax")
            .body(Map.of("success", true, "data", Map.of("loggedOut", true)));
    }
}
