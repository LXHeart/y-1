package com.grassland.identity.auth;

import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.security.LoginRateLimiter;
import com.grassland.identity.security.PasswordVerifier;
import com.grassland.identity.session.SessionWriter;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LegacyUserLookup;
import com.grassland.identity.user.LegacyUserRepository;
import com.grassland.identity.user.LoginUser;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class LoginController {
    private static final String LOGIN_ERROR = "\u90ae\u7bb1\u6216\u5bc6\u7801\u9519\u8bef";

    private final LegacyUserLookup userLookup;
    private final LegacyUserRepository userRepository;
    private final PasswordVerifier passwordVerifier;
    private final SessionWriter sessionWriter;
    private final LoginRateLimiter rateLimiter;

    public LoginController(LegacyUserLookup userLookup, LegacyUserRepository userRepository,
                           PasswordVerifier passwordVerifier, SessionWriter sessionWriter,
                           LoginRateLimiter rateLimiter) {
        this.userLookup = userLookup;
        this.userRepository = userRepository;
        this.passwordVerifier = passwordVerifier;
        this.sessionWriter = sessionWriter;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Mono<LoginRequest> bodyMono, ServerHttpRequest request) {
        return bodyMono.flatMap(body -> {
            String email = body.email();
            String password = body.password();
            String ip = extractIp(request);
            LoginRateLimiter.CheckResult rateCheck = rateLimiter.check(ip, email);
            if (!rateCheck.allowed()) {
                return Mono.just(build429(rateCheck));
            }
            return userLookup.findByEmail(email == null ? null : email.trim().toLowerCase())
                .flatMap(user -> attemptLogin(user, password, ip, email))
                .switchIfEmpty(Mono.defer(() -> {
                    rateLimiter.recordOutcome(ip, email, true);
                    return Mono.just(build401());
                }));
        });
    }

    private Mono<ResponseEntity<Map<String, Object>>> attemptLogin(LoginUser user, String password, String ip, String email) {
        boolean passwordOk = user.isActive() && passwordVerifier.verify(password, user.passwordHash());
        if (!passwordOk) {
            rateLimiter.recordOutcome(ip, email, true);
            return Mono.just(build401());
        }
        AuthUser authUser = new AuthUser(user.id(), user.email(), user.displayName(), user.role(), user.status());
        return userRepository.recordLogin(user.id())
            .then(sessionWriter.createSession(authUser))
            .map(created -> {
                rateLimiter.recordOutcome(ip, email, false);
                return build200(authUser, created.setCookieHeader());
            });
    }

    private ResponseEntity<Map<String, Object>> build200(AuthUser user, String setCookie) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.id());
        userInfo.put("email", user.email());
        if (user.displayName() != null && !user.displayName().isBlank()) {
            userInfo.put("displayName", user.displayName());
        }
        userInfo.put("role", user.role());
        return ResponseEntity.ok()
            .header("Set-Cookie", setCookie)
            .body(Map.of("success", true, "data", Map.of("user", userInfo)));
    }

    private ResponseEntity<Map<String, Object>> build401() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error", LOGIN_ERROR));
    }

    private ResponseEntity<Map<String, Object>> build429(LoginRateLimiter.CheckResult rateCheck) {
        return ResponseEntity.status(429).body(Map.of("success", false, "error", "\u767b\u5f55\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002"));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private String extractIp(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : null;
    }
}
