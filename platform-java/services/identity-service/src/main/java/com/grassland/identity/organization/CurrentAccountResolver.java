package com.grassland.identity.organization;

import com.grassland.identity.admin.BackendRoleRepository;
import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.assertion.BackendRoles;
import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.session.LegacySessionBridge;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LegacyUserLookup;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 从请求 cookie 解析当前登录 account（封装 MeController 的鉴权链路，供受保护端点复用）。
 *
 * <p>cookie → unsign → sid → {@link LegacySessionBridge#findUserId} → {@link LegacyUserLookup#findById} → {@link AuthUser}。
 */
@Component
public class CurrentAccountResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentAccountResolver.class);

    private final LegacySessionBridge sessionBridge;
    private final LegacyUserLookup userLookup;
    private final CookieSigner cookieSigner;
    private final String cookieName;
    private final ObjectProvider<IdentityAssertionSigner> signerProvider;
    private final String assertionHeaderName;
    private final BackendRoleRepository backendRoles;

    public CurrentAccountResolver(LegacySessionBridge sessionBridge, LegacyUserLookup userLookup,
                                  CookieSigner cookieSigner,
                                  @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName,
                                  ObjectProvider<IdentityAssertionSigner> signerProvider,
                                  @Value("${identity-assertion.header-name:X-Grassland-Identity}") String assertionHeaderName,
                                  BackendRoleRepository backendRoles) {
        this.sessionBridge = sessionBridge;
        this.userLookup = userLookup;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
        this.signerProvider = signerProvider;
        this.assertionHeaderName = assertionHeaderName;
        this.backendRoles = backendRoles;
    }

    public Mono<AuthUser> resolve(ServerHttpRequest request) {
        return resolvePrincipal(request).map(SessionPrincipal::user);
    }

    /**
     * 解析当前会话主体（账号 + sid）。草场身份域 Slice 2I（HLD D-08 per-session：活动身份按 session 隔离，端点需 sid）。
     * cookie → unsign → sid → {@link LegacySessionBridge#findUserId} → {@link LegacyUserLookup#findById} → {@link SessionPrincipal}。
     */
    /**
     * 解析当前会话主体（账号 + sid）。草场身份域 Slice 2K（HLD 7.4 内部身份断言消费）：
     * <b>优先</b>信任 BFF 签发的 {@code X-Grassland-Identity} 断言头（验签通过即跳过 session 表 JSON 读取），
     * account 级 role/status 仍自查 app_users（identity 是权威）；断言缺失/验签失败则回退 cookie 链路（Slice 2I）。
     */
    public Mono<SessionPrincipal> resolvePrincipal(ServerHttpRequest request) {
        IdentityAssertionSigner signer = signerProvider.getIfAvailable();
        String assertionHeader = signer == null ? null : request.getHeaders().getFirst(assertionHeaderName);
        if (signer != null && assertionHeader != null && !assertionHeader.isBlank()) {
            Optional<IdentityAssertion> verified = signer.verify(assertionHeader, Instant.now());
            if (verified.isPresent()) {
                IdentityAssertion assertion = verified.get();
                return userLookup.findById(assertion.accountId())
                        .switchIfEmpty(Mono.error(new IdentityException(401, "用户不存在")))
                        .flatMap(user -> activePrincipal(user, assertion.sessionToken()));
            }
            log.warn("identity assertion present but failed verification; falling back to cookie");
        }
        return resolveViaCookie(request);
    }

    private Mono<SessionPrincipal> resolveViaCookie(ServerHttpRequest request) {
        String sid = extractSid(request);
        if (sid == null) {
            return Mono.error(new IdentityException(401, "请先登录"));
        }
        return sessionBridge.findUserId(sid)
                .switchIfEmpty(Mono.error(new IdentityException(401, "请先登录")))
                .flatMap(userLookup::findById)
                .switchIfEmpty(Mono.error(new IdentityException(401, "用户不存在")))
                .flatMap(user -> activePrincipal(user, sid));
    }

    private Mono<SessionPrincipal> activePrincipal(AuthUser user, String sid) {
        if (!user.isActive()) {
            return Mono.error(new IdentityException(403, "当前账号不可用"));
        }
        return Mono.just(new SessionPrincipal(user, sid));
    }

    /**
     * 要求当前账号为平台管理员（{@code role==admin}），放行返回该账号；非管理员 → 403；未登录 → 401（由 {@link #resolve} 抛）。
     * 草场身份域 Slice 2H（D-05 平台 admin 门禁）。
     *
     * <p>向后兼容（GL-P2-ADMIN-001）：先查 backend_role 表是否含 platform_admin；
     * 旧用户无 backend_role 行时回退 {@code app_users.role=='admin'} 判定（backfill 已把老 admin 迁入 backend_role，
     * 但保留兜底防御 backfill 缺失）。
     */
    public Mono<AuthUser> requireAdmin(ServerHttpRequest request) {
        return resolve(request)
                .filterWhen(user -> backendRoles.findByAccountId(user.id())
                        .map(roles -> roles.contains(BackendRole.PLATFORM_ADMIN))
                        .defaultIfEmpty("admin".equalsIgnoreCase(user.role())))
                .switchIfEmpty(Mono.error(new IdentityException(403, "需要平台管理员权限")));
    }

    /**
     * 要求当前账号持有任一指定后台角色（含 PLATFORM_ADMIN 超集语义）。
     * identity 是 account 权威，**自查 backend_role 表**（不信 BFF 断言的 role claim）。
     * 未登录 → 401（由 {@link #resolve} 抛）；登录但无所需角色 → 403。
     *
     * @param required 所需角色（任一匹配即通过；PLATFORM_ADMIN 恒通过）
     */
    public Mono<AuthUser> requireRole(ServerHttpRequest request, BackendRole... required) {
        return resolve(request)
                .filterWhen(user -> backendRoles.findByAccountId(user.id())
                        .map(roles -> BackendRoles.hasAny(roles, required))
                        .defaultIfEmpty(false))
                .switchIfEmpty(Mono.error(new IdentityException(403, "权限不足")));
    }

    /** 当前账号的后台角色集合（查 backend_role 表）。供 MeController / AdminUserRepository 合并展示。 */
    public Mono<java.util.Set<BackendRole>> resolveBackendRoles(String accountId) {
        return backendRoles.findByAccountId(accountId);
    }

    private String extractSid(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(cookieName);
        if (cookie == null) {
            return null;
        }
        String value = cookie.getValue();
        try {
            value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
        }
        return cookieSigner.unsign(value).orElse(null);
    }
}
