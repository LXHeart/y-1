package com.grassland.finance.security;

import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * finance 调用者解析（Epic 4 Slice 4D / HLD 7.4）：**仅**信任 edge-bff 签发的 {@code X-Grassland-Identity} 断言，
 * 无 cookie 回退（finance 是纯下游，识人完全靠 BFF 断言，不读 identity 库）。
 *
 * <p>断言缺/失效 → 401；{@link #requireMerchant} 额外要求 activeIdentityType=merchant（断言携带）→ 403。
 * 资源级授权（如 merchant 确属某 org）仍须服务端用 {@code caller.organizationId} 自查（HLD 7.4 末句）。
 */
@Component
public class FinanceCallerResolver {

    private final IdentityAssertionSigner signer;
    private final String headerName;

    public FinanceCallerResolver(IdentityAssertionSigner signer,
                                 @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signer = signer;
        this.headerName = headerName;
    }

    public Mono<Caller> resolve(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(headerName);
        if (header == null || header.isBlank()) {
            return Mono.error(new FinanceException(401, "未登录"));
        }
        return Mono.justOrEmpty(signer.verify(header, Instant.now()))
                .map(a -> new Caller(a.accountId(), a.activeIdentityType(), a.organizationId(), a.permissionTier()))
                .switchIfEmpty(Mono.error(new FinanceException(401, "未登录")));
    }

    public Mono<Caller> requireMerchant(ServerHttpRequest request) {
        return resolve(request)
                .filter(Caller::isMerchant)
                .switchIfEmpty(Mono.error(new FinanceException(403, "需要商家身份")));
    }

    /** 断言解析出的调用者。{@code organizationId}/{@code permissionTier} 为商家身份关联 org 及其 tier（非商家为 null），
     *  供 org 级资源授权（如账户归属）自查。 */
    public record Caller(String accountId, String activeIdentityType, String organizationId, String permissionTier) {
        public boolean isMerchant() {
            return "merchant".equalsIgnoreCase(activeIdentityType);
        }
    }
}
