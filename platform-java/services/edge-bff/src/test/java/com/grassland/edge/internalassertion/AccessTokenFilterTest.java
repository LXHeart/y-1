package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.edge.proxy.UpstreamResolver;
import com.grassland.edge.proxy.EdgeRoutingProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class AccessTokenFilterTest {

    @RestController
    static class EchoController {
        @RequestMapping("/**")
        Mono<Map<String, String>> echo(ServerHttpRequest request) {
            Map<String, String> body = new LinkedHashMap<>();
            String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authorization != null) {
                body.put("authorization", authorization);
            }
            return Mono.just(body);
        }
    }

    @Test
    void validBearerStoresIdentityAndStripsRawCredential() {
        ResolvedIdentity identity = identity();
        AccessTokenIdentityResolver resolver = mock(AccessTokenIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(Mono.just(identity));
        AccessTokenFilter filter = new AccessTokenFilter(provider(resolver), upstream(true));

        client(filter).get().uri("/api/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{}");
    }

    @Test
    void invalidOrRevokedBearerFailsClosedWithoutCookieFallback() {
        AccessTokenIdentityResolver resolver = mock(AccessTokenIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(Mono.empty());
        AccessTokenFilter filter = new AccessTokenFilter(provider(resolver), upstream(true));

        client(filter).get().uri("/api/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer revoked-token")
                .cookie("y1.sid", "still-valid-cookie")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void failClosedRouteStripsBearerWithoutTryingToAuthenticate() {
        AccessTokenFilter filter = new AccessTokenFilter(provider(null), upstream(false));
        client(filter).get().uri("/api/unknown-tool")
                .header(HttpHeaders.AUTHORIZATION, "Bearer access-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{}");
    }

    @Test
    void refreshAndRevokePreserveTheirRefreshTokenBearer() {
        AccessTokenFilter filter = new AccessTokenFilter(provider(null), upstream(true));
        for (String path : new String[] {"/api/auth/refresh", "/api/auth/revoke"}) {
            client(filter).post().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer refresh-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.authorization").isEqualTo("Bearer refresh-token");
        }
    }

    @Test
    void disabledRefreshRouteStripsBearer() {
        AccessTokenFilter filter = new AccessTokenFilter(provider(null), upstream(false));
        client(filter).post().uri("/api/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer refresh-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{}");
    }

    @Test
    void nonBearerAuthorizationOnInternalRouteIsRejected() {
        AccessTokenFilter filter = new AccessTokenFilter(provider(null), upstream(true));
        client(filter).get().uri("/api/tasks")
                .header(HttpHeaders.AUTHORIZATION, "Basic credentials")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private static WebTestClient client(AccessTokenFilter filter) {
        return WebTestClient.bindToController(new EchoController())
                .webFilter(filter)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AccessTokenIdentityResolver> provider(AccessTokenIdentityResolver resolver) {
        ObjectProvider<AccessTokenIdentityResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(resolver);
        return provider;
    }

    private static UpstreamResolver upstream(boolean internal) {
        UpstreamResolver upstream = mock(UpstreamResolver.class);
        when(upstream.isInternalUpstream(anyString(), anyString())).thenReturn(internal);
        when(upstream.resolveUpstreamName(anyString(), anyString()))
                .thenReturn(internal ? "identity" : EdgeRoutingProperties.FAIL_CLOSED);
        return upstream;
    }

    private static ResolvedIdentity identity() {
        return new ResolvedIdentity(
                "11111111-1111-1111-1111-111111111111", "user", "active", "merchant", "refresh-id",
                "org-1", "basic_publish", null, "level1", false);
    }
}
