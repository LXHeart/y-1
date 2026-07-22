package com.grassland.identity.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacySessionBridgeTest {
    private final LegacySessionBridge bridge = new LegacySessionBridge(null);

    @Test
    void extractsUserIdFromSessionJson() {
        String sess = "{\"user\":{\"id\":\"11111111-1111-1111-1111-111111111111\",\"email\":\"a@b.com\",\"role\":\"user\"},\"cookie\":{}}";
        assertThat(bridge.extractUserId(sess)).isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void returnsNullWhenSessionHasNoUser() {
        assertThat(bridge.extractUserId("{\"cookie\":{}}")).isNull();
        assertThat(bridge.extractUserId("{\"user\":{}}")).isNull();
        assertThat(bridge.extractUserId("not-json")).isNull();
    }
}
