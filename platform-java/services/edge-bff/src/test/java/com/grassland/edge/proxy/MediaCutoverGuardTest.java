package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class MediaCutoverGuardTest {

    private static EdgeRoutingProperties routing(boolean enabled) {
        return new EdgeRoutingProperties(
                Map.of("legacy", URI.create("http://legacy"), "intelligence", URI.create("http://intelligence")),
                List.of(new RouteProperties("POST", "/api/douyin/extract-video", "intelligence", enabled, true)),
                "legacy");
    }

    @Test
    void disabledCutoverDoesNotRequireProductionSecrets() {
        new MediaCutoverGuard(routing(false), new MockEnvironment()).validate();
    }

    @Test
    void enabledCutoverRequiresSharedSecret() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("PUBLIC_BACKEND_ORIGIN", "https://public.example");
        assertThatThrownBy(() -> new MediaCutoverGuard(routing(true), env).validate())
                .hasMessageContaining("DOUYIN_PROXY_TOKEN_SECRET");
    }

    @Test
    void enabledCutoverRejectsNonOriginPublicUrl() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DOUYIN_PROXY_TOKEN_SECRET", "x".repeat(32))
                .withProperty("PUBLIC_BACKEND_ORIGIN", "/relative");
        assertThatThrownBy(() -> new MediaCutoverGuard(routing(true), env).validate())
                .hasMessageContaining("PUBLIC_BACKEND_ORIGIN must be an absolute");
    }
}
