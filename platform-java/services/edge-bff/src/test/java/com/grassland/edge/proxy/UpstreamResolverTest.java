package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UpstreamResolverTest {
    private static final URI LEGACY = URI.create("http://legacy:3000");
    private static final URI IDENTITY = URI.create("http://identity:8082");

    private final EdgeRoutingProperties properties = new EdgeRoutingProperties(
        Map.of("legacy", LEGACY, "identity", IDENTITY),
        List.of(
            new RouteProperties("GET", "/api/auth/me", "identity", true),
            new RouteProperties(null, "/api/v2/**", "identity", true)),
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
}
