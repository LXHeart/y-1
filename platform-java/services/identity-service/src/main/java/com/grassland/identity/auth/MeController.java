package com.grassland.identity.auth;

import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.session.LegacySessionBridge;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LegacyUserLookup;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class MeController {
    private final LegacySessionBridge sessionBridge;
    private final LegacyUserLookup userLookup;
    private final CookieSigner cookieSigner;
    private final String cookieName;

    public MeController(LegacySessionBridge sessionBridge, LegacyUserLookup userLookup, CookieSigner cookieSigner,
                        @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.sessionBridge = sessionBridge;
        this.userLookup = userLookup;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
    }

    @GetMapping("/api/auth/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(ServerHttpRequest request) {
        String sid = extractSid(request);
        if (sid == null) {
            return Mono.error(new IdentityException(401, "请先登录"));
        }
        return sessionBridge.findUserId(sid)
            .switchIfEmpty(Mono.error(new IdentityException(401, "请先登录")))
            .flatMap(userLookup::findById)
            .switchIfEmpty(Mono.error(new IdentityException(401, "用户不存在")))
            .flatMap(user -> {
                if (!user.isActive()) {
                    return Mono.error(new IdentityException(403, "当前账号不可用"));
                }
                return Mono.just(user);
            })
            .map(this::toResponse);
    }

    private ResponseEntity<Map<String, Object>> toResponse(AuthUser user) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.id());
        userInfo.put("email", user.email());
        if (user.displayName() != null && !user.displayName().isBlank()) {
            userInfo.put("displayName", user.displayName());
        }
        userInfo.put("role", user.role());
        return ResponseEntity.ok(Map.of("success", true, "data", Map.of("user", userInfo)));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
            .body(Map.of("success", false, "error", error.getMessage()));
    }

    private String extractSid(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null) {
            return null;
        }
        String value = cookie.getValue();
        try {
            value = java.net.URLDecoder.decode(value.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        return cookieSigner.unsign(value).orElse(null);
    }
}
