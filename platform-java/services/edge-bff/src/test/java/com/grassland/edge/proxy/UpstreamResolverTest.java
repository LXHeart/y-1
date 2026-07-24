package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamResolverTest {
    private static final URI LEGACY = URI.create("http://legacy:3000");
    private static final URI IDENTITY = URI.create("http://identity:8082");
    private static final URI MARKETPLACE = URI.create("http://marketplace:8083");

    private final EdgeRoutingProperties properties = new EdgeRoutingProperties(
        Map.of("legacy", LEGACY, "identity", IDENTITY, "marketplace", MARKETPLACE),
        List.of(
            new RouteProperties("GET", "/api/auth/me", "identity", true),
            new RouteProperties(null, "/api/v2/**", "identity", true),
            // Slice 4C：/api/tasks** 全方法 → marketplace（无 method；前缀覆盖子路径）
            new RouteProperties(null, "/api/tasks", "marketplace", true)),
        "legacy");

    private final UpstreamResolver resolver = new UpstreamResolver(properties);

    @Test
    void routesAuthMeToIdentity() {
        assertThat(resolver.resolve("GET", "/api/auth/me")).isEqualTo(IDENTITY);
    }

    @Test
    void routesLegacyByDefault() {
        assertThat(resolver.resolve("POST", "/api/auth/login")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/api/douyin/proxy/token")).isEqualTo(LEGACY);
        assertThat(resolver.resolve("GET", "/health")).isEqualTo(LEGACY);
    }

    @Test
    void methodSpecificity() {
        // POST /api/auth/me should NOT match the GET-only route -> legacy
        assertThat(resolver.resolve("POST", "/api/auth/me")).isEqualTo(LEGACY);
    }

    UpstreamResolver disabledRouteResolver() {
        EdgeRoutingProperties disabled = new EdgeRoutingProperties(
            Map.of("legacy", LEGACY, "identity", IDENTITY),
            List.of(new RouteProperties("GET", "/api/auth/me", "identity", false)),
            "legacy");
        return new UpstreamResolver(disabled);
    }

    @Test
    void disabledRouteFallsBackToLegacy() {
        assertThat(disabledRouteResolver().resolve("GET", "/api/auth/me")).isEqualTo(LEGACY);
    }

    @Test
    void prefixGlobMatches() {
        assertThat(resolver.resolve("GET", "/api/v2/anything")).isEqualTo(IDENTITY);
        assertThat(resolver.resolve("GET", "/api/v2")).isEqualTo(IDENTITY);
    }

    // ---------- Slice 4C: /api/tasks** → marketplace（内部上游，触发断言签发）----------

    @Test
    void routesTasksToMarketplaceAllMethodsAndSubPaths() {
        assertThat(resolver.resolve("POST", "/api/tasks")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/tasks")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("GET", "/api/tasks/" + TASK_ID)).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("POST", "/api/tasks/" + TASK_ID + "/applications")).isEqualTo(MARKETPLACE);
        assertThat(resolver.resolve("POST", "/api/tasks/" + TASK_ID + "/applications/" + APP_ID + "/accept"))
                .isEqualTo(MARKETPLACE);
    }

    @Test
    void tasksIsInternalUpstreamSoAssertionGetsSigned() {
        // isInternalUpstream=true → InternalAssertionFilter 签发 X-Grassland-Identity（HLD 7.4 端到端打通）
        assertThat(resolver.isInternalUpstream("POST", "/api/tasks")).isTrue();
        assertThat(resolver.isInternalUpstream("GET", "/api/tasks/" + TASK_ID + "/applications")).isTrue();
        // 非 task 路径仍是 legacy（内部判定 false → 不签断言）
        assertThat(resolver.isInternalUpstream("GET", "/api/douyin/proxy/token")).isFalse();
    }

    private static final String TASK_ID = "11111111-1111-1111-1111-111111111111";
    private static final String APP_ID = "22222222-2222-2222-2222-222222222222";
}
