package com.grassland.identity.security;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Verifies service assertions for identity's internal-only HTTP endpoints. */
@Component
public class InternalServiceCallerResolver {

    private final ObjectProvider<IdentityAssertionSigner> signers;
    private final String headerName;

    public InternalServiceCallerResolver(
            ObjectProvider<IdentityAssertionSigner> signers,
            @Value("${identity-assertion.header-name:X-Grassland-Identity}") String headerName) {
        this.signers = signers;
        this.headerName = headerName;
    }

    public Mono<IdentityAssertion> requireServicePrincipal(
            ServerHttpRequest request, String expectedPrincipal) {
        return requireServicePrincipal(request, Set.of(expectedPrincipal));
    }

    /** Verifies that the caller is one of the explicitly trusted service principals. */
    public Mono<IdentityAssertion> requireServicePrincipal(
            ServerHttpRequest request, Set<String> expectedPrincipals) {
        IdentityAssertionSigner signer = signers.getIfAvailable();
        if (signer == null) {
            return Mono.error(new IdentityException(503, "内部身份校验暂时不可用"));
        }
        String token = request.getHeaders().getFirst(headerName);
        if (token == null || token.isBlank()) {
            return Mono.error(new IdentityException(401, "缺少服务身份"));
        }
        return signer.verifyReactive(token, Instant.now())
                .switchIfEmpty(Mono.error(new IdentityException(401, "服务身份无效")))
                .filter(assertion -> assertion.isService()
                        && expectedPrincipals.stream()
                                .anyMatch(expected -> expected.equalsIgnoreCase(assertion.principal())))
                .switchIfEmpty(Mono.error(new IdentityException(403, "无权调用内部端点")));
    }
}
