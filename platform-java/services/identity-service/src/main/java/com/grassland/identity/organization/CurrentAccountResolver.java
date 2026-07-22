package com.grassland.identity.organization;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.CookieSigner;
import com.grassland.identity.session.LegacySessionBridge;
import com.grassland.identity.user.AuthUser;
import com.grassland.identity.user.LegacyUserLookup;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    private final LegacySessionBridge sessionBridge;
    private final LegacyUserLookup userLookup;
    private final CookieSigner cookieSigner;
    private final String cookieName;

    public CurrentAccountResolver(LegacySessionBridge sessionBridge, LegacyUserLookup userLookup,
                                  CookieSigner cookieSigner,
                                  @Value("${identity.legacy.session.cookie-name:y1.sid}") String cookieName) {
        this.sessionBridge = sessionBridge;
        this.userLookup = userLookup;
        this.cookieSigner = cookieSigner;
        this.cookieName = cookieName;
    }

    public Mono<AuthUser> resolve(ServerHttpRequest request) {
        String sid = extractSid(request);
        if (sid == null) {
            return Mono.error(new IdentityException(401, "请先登录"));
        }
        return sessionBridge.findUserId(sid)
                .switchIfEmpty(Mono.error(new IdentityException(401, "请先登录")))
                .flatMap(userLookup::findById)
                .switchIfEmpty(Mono.error(new IdentityException(401, "用户不存在")));
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
