package com.grassland.intelligence.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * intelligence 调用者解析（草场 intelligence Slice 1 / HLD 7.4）：**仅**信任 edge-bff 签发的
 * {@code X-Grassland-Identity} 断言，无 cookie 回退（intelligence 是纯下游，识人完全靠 BFF 断言）。
 *
 * <p>断言缺/失效 → 401；{@link #requireMerchant}/{@link #requireRecommender} 额外要求对应活动身份 → 403。
 * 冒烟端点（{@code /api/intelligence/smoke/*}）只需 {@link #resolve}（任意登录用户）。
 */
@Component
public class IntelligenceCallerResolver {

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public IntelligenceCallerResolver(IdentityAssertionSigner signer,
                                      @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new IntelligenceException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.sessionToken(),
                        a.organizationId(), a.permissionTier()))
                .switchIfEmpty(Mono.error(new IntelligenceException(401, "未登录")));
    }

    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要商家身份")));
    }

    public Mono<Caller> requireRecommender(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isRecommender)
                .switchIfEmpty(Mono.error(new IntelligenceException(403, "需要推荐官身份")));
    }

    /** 断言解析出的调用者。{@code activeIdentityType} 为 null=消费者。 */
    public record Caller(String accountId, String activeIdentityType, String sessionToken,
                         String organizationId, String permissionTier) {
        public boolean isMerchant() {
            return "merchant".equalsIgnoreCase(activeIdentityType);
        }

        public boolean isRecommender() {
            return "recommender".equalsIgnoreCase(activeIdentityType);
        }
    }
}
