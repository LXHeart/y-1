package com.grassland.identity.auth;

import com.grassland.identity.identityprofile.DeviceFingerprint;
import com.grassland.identity.mobile.RefreshTokenService;
import com.grassland.identity.security.Argon2PasswordHasher;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.security.LoginRateLimiter;
import com.grassland.identity.security.PasswordVerifier;
import com.grassland.identity.session.SessionWriter;
import com.grassland.identity.user.AccountFlagRepository;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.UserLookup;
import com.grassland.identity.user.UserRepository;
import com.grassland.identity.user.LoginUser;
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
import reactor.core.scheduler.Schedulers;

@RestController
public class LoginController {
    // 任务书 #49：登录标识双轨（账号名或邮箱）后统一为「账号」
    private static final String LOGIN_ERROR = "\u8d26\u53f7\u6216\u5bc6\u7801\u9519\u8bef";

    private final UserLookup userLookup;
    private final UserRepository userRepository;
    private final PasswordVerifier passwordVerifier;
    private final Argon2PasswordHasher argon2Hasher;
    private final SessionWriter sessionWriter;
    private final LoginRateLimiter rateLimiter;
    private final RefreshTokenService refreshTokens;
    private final AccountFlagRepository accountFlags;

    public LoginController(UserLookup userLookup, UserRepository userRepository,
                           PasswordVerifier passwordVerifier, Argon2PasswordHasher argon2Hasher,
                           SessionWriter sessionWriter, LoginRateLimiter rateLimiter,
                           RefreshTokenService refreshTokens, AccountFlagRepository accountFlags) {
        this.userLookup = userLookup;
        this.userRepository = userRepository;
        this.passwordVerifier = passwordVerifier;
        this.argon2Hasher = argon2Hasher;
        this.sessionWriter = sessionWriter;
        this.rateLimiter = rateLimiter;
        this.refreshTokens = refreshTokens;
        this.accountFlags = accountFlags;
    }

    @PostMapping(value = "/api/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Mono<LoginRequest> bodyMono, ServerHttpRequest request) {
        return bodyMono.flatMap(body -> {
            String email = body.email();
            String password = body.password();
            String ip = DeviceFingerprint.from(request).ipAddress();
            LoginRateLimiter.CheckResult rateCheck = rateLimiter.check(ip, email);
            if (!rateCheck.allowed()) {
                return Mono.just(build429(rateCheck));
            }
            // 任务书 #49 D7：标识双查——先按登录名旁表命中子账号，miss 再按邮箱（存量路径零变化）；
            // 限流键沿用输入原文（语义与行为同现状）。
            return userLookup.findByIdentifier(email == null ? null : email.trim())
                .flatMap(user -> attemptLogin(user, password, ip, email, request))
                .switchIfEmpty(Mono.defer(() -> {
                    rateLimiter.recordOutcome(ip, email, true);
                    return Mono.just(build401());
                }));
        });
    }

    private Mono<ResponseEntity<Map<String, Object>>> attemptLogin(LoginUser user, String password, String ip, String email,
                                                                   ServerHttpRequest request) {
        // 密码错误 → 统一 401（不暴露账号存在性）。密码正确但状态非 active（任务书 #48：子账号
        // pending_review/rejected/suspended）→ 明确区分的 403，让用户知道找谁解封。
        boolean passwordOk = passwordVerifier.verify(password, user.passwordHash());
        if (!passwordOk) {
            rateLimiter.recordOutcome(ip, email, true);
            return Mono.just(build401());
        }
        if (!user.isActive()) {
            rateLimiter.recordOutcome(ip, email, false);
            return Mono.just(buildAccountBlocked(user.status()));
        }
        AuthUser authUser = new AuthUser(user.id(), user.email(), user.displayName(), user.role(), user.status());
        // GL-P3-IDENTITY-001: 登录成功后，检查是否需要升级密码为 Argon2id。
        // Argon2 是 CPU/内存重操作（64MB/3 轮），须在 boundedElastic 上跑，不能阻塞 Netty 事件循环。
        boolean needsRehash = passwordVerifier.needsRehash(user.passwordHash());
        Mono<Void> loginOps = userRepository.recordLogin(user.id());
        if (needsRehash) {
            loginOps = loginOps.then(
                Mono.fromCallable(() -> argon2Hasher.hash(password))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(newHash -> userRepository.upgradePasswordHash(user.id(), newHash))
            );
        }
        // GL-P3-IDENTITY-001 移动端 token 模式：请求带 X-Device-Info → 签发 access/refresh token，
        // 不建 session 行、不发 Set-Cookie（移动端无 cookie jar）。Web（无该头）路径逐字节不变。
        String deviceInfo = header(request, "X-Device-Info");
        // 任务书 #48：管理员代建/重置密码后必须首登改密——登录响应携带标记，前端路由锁到改密页；
        // 服务端硬闸在 edge InternalAssertionFilter（428）。
        // 任务书 #49 D11：子账号响应带登录名与 hasEmail（占位邮箱不暴露给前端当真邮箱）。
        Mono<Boolean> mustChange = accountFlags.mustChangePassword(user.id()).defaultIfEmpty(Boolean.FALSE);
        Mono<String> username = userLookup.findUsernameById(user.id()).defaultIfEmpty("");
        if (deviceInfo != null && !deviceInfo.isBlank()) {
            if (!refreshTokens.isConfigured()) {
                return Mono.just(build503());
            }
            return loginOps
                .then(Mono.zip(mustChange, username))
                .flatMap(pair -> refreshTokens.issue(authUser, DeviceFingerprint.from(request),
                        resolveDeviceName(request), deviceInfo).map(issued -> {
                            rateLimiter.recordOutcome(ip, email, false);
                            return buildToken200(authUser, issued, Boolean.TRUE.equals(pair.getT1()), pair.getT2());
                        }));
        }
        return loginOps
            .then(Mono.zip(mustChange, username))
            .flatMap(pair -> sessionWriter.createSession(authUser, request).map(created -> {
                rateLimiter.recordOutcome(ip, email, false);
                return build200(authUser, created.setCookieHeader(), Boolean.TRUE.equals(pair.getT1()), pair.getT2());
            }));
    }

    /** 设备名：X-Device-Name（设计文档）缺省回落 X-Device-Label（既有设备指纹惯例）。 */
    private String resolveDeviceName(ServerHttpRequest request) {
        String name = header(request, "X-Device-Name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String label = header(request, "X-Device-Label");
        return (label != null && !label.isBlank()) ? label : null;
    }

    private String header(ServerHttpRequest request, String name) {
        return request.getHeaders().getFirst(name);
    }

    private ResponseEntity<Map<String, Object>> build200(AuthUser user, String setCookie, boolean mustChangePassword,
            String username) {
        return ResponseEntity.ok()
            .header("Set-Cookie", setCookie)
            .body(Map.of("success", true, "data", Map.of("user", userInfo(user, mustChangePassword, username))));
    }

    /**
     * 移动端 token 模式响应：与 Web 共用 user 字段，额外带 tokens，且刻意不发 Set-Cookie。
     */
    private ResponseEntity<Map<String, Object>> buildToken200(AuthUser user, RefreshTokenService.IssuedTokens issued,
                                                              boolean mustChangePassword, String username) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("access_token", issued.accessToken());
        tokens.put("refresh_token", issued.refreshToken());
        tokens.put("expires_in", issued.expiresInSeconds());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", userInfo(user, mustChangePassword, username));
        data.put("tokens", tokens);
        return ResponseEntity.ok().body(Map.of("success", true, "data", data));
    }

    private Map<String, Object> userInfo(AuthUser user, boolean mustChangePassword, String username) {
        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.id());
        userInfo.put("email", user.email());
        // #49：子账号带登录名；hasEmail=false 表示 email 是占位符（未绑定真邮箱）
        if (username != null && !username.isBlank()) {
            userInfo.put("username", username);
        }
        userInfo.put("hasEmail", user.email() != null && !user.email().endsWith("@sub.grassland.invalid"));
        if (user.displayName() != null && !user.displayName().isBlank()) {
            userInfo.put("displayName", user.displayName());
        }
        userInfo.put("role", user.role());
        userInfo.put("mustChangePassword", mustChangePassword);
        return userInfo;
    }

    /** 密码正确但账号状态不可用：按状态给可行动文案（任务书 #48）。 */
    private ResponseEntity<Map<String, Object>> buildAccountBlocked(String status) {
        String message;
        if ("pending_review".equalsIgnoreCase(status)) {
            message = "账号待商家主体审核通过后再登录";
        } else if ("suspended".equalsIgnoreCase(status)) {
            message = "账号已停用，请联系商家管理员";
        } else {
            message = "当前账号不可用";
        }
        return ResponseEntity.status(403).body(Map.of("success", false, "error", message));
    }

    /** access token secret 未配置时移动端登录 fail-closed；Web 路径不受影响。 */
    private ResponseEntity<Map<String, Object>> build503() {
        return ResponseEntity.status(503)
            .body(Map.of("success", false, "error", "移动端登录暂未启用"));
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

}
