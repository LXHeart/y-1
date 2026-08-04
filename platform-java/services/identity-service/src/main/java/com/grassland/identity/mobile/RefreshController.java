package com.grassland.identity.mobile;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.identityprofile.DeviceFingerprint;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * POST /api/auth/refresh（GL-P3-IDENTITY-001）：refresh token → 新 access token（不轮换）。
 *
 * <p>token 两种给法：{@code Authorization: Bearer <refresh_token>} 头，或 JSON body {@code refresh_token}
 * （设计文档 §3.4）。因此<b>不声明 consumes</b>——Bearer-only 调用可能没有 JSON body/Content-Type。
 *
 * <p>IP 取 {@link DeviceFingerprint}（XFF 首段感知）而非 LoginController 的裸 remote address：
 * 移动端经 edge-bff 进来时 remote address 恒为容器网络 IP，限流会全量打到同一键。
 */
@RestController
public class RefreshController {

    private final RefreshTokenService service;
    private final RefreshRateLimiter rateLimiter;

    public RefreshController(RefreshTokenService service, RefreshRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestBody(required = false) Mono<Map<String, String>> bodyMono,
            ServerHttpRequest request) {
        if (!service.isConfigured()) {
            return Mono.just(build503());
        }
        Mono<String> tokenMono = bearerToken(request) != null
                ? Mono.just(bearerToken(request))
                : (bodyMono == null ? Mono.empty()
                        : bodyMono.map(body -> body.getOrDefault("refresh_token", "")).filter(t -> !t.isBlank()));
        return tokenMono
                .switchIfEmpty(Mono.just(""))
                .flatMap(token -> {
                    if (token.isBlank()) {
                        return Mono.just(build401());
                    }
                    String ip = DeviceFingerprint.from(request).ipAddress();
                    String tokenHash = RefreshTokenService.sha256Hex(token);
                    RefreshRateLimiter.CheckResult rateCheck = rateLimiter.check(ip, tokenHash);
                    if (!rateCheck.allowed()) {
                        return Mono.just(build429());
                    }
                    return service.refresh(token)
                            .map(issued -> {
                                rateLimiter.recordOutcome(ip, tokenHash, false);
                                return ResponseEntity.ok(Map.<String, Object>of("success", true,
                                        "data", Map.of(
                                                "access_token", issued.accessToken(),
                                                "expires_in", issued.expiresInSeconds())));
                            })
                            .switchIfEmpty(Mono.fromCallable(() -> {
                                rateLimiter.recordOutcome(ip, tokenHash, true);
                                return build401();
                            }));
                });
    }

    private static String bearerToken(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(0);
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private ResponseEntity<Map<String, Object>> build401() {
        return ResponseEntity.status(401).body(Map.of("success", false, "error",
                "refresh token 无效或已过期，请重新登录。"));
    }

    private ResponseEntity<Map<String, Object>> build429() {
        return ResponseEntity.status(429).body(Map.of("success", false, "error",
                "刷新请求过于频繁，请稍后再试。"));
    }

    private ResponseEntity<Map<String, Object>> build503() {
        return ResponseEntity.status(503).body(Map.of("success", false, "error",
                "移动端登录暂未启用"));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
