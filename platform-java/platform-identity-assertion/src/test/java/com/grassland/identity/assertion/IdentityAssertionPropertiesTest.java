package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** compact-constructor 校验与默认值（仿 ObjectStoragePropertiesTest）。 */
class IdentityAssertionPropertiesTest {

    @Test
    void disabled_doesNotRequireSecret() {
        var props = new IdentityAssertionProperties(false, null, 0, null, null, -1, null, null, null, null, null);
        assertThat(props.enabled()).isFalse();
        assertThat(props.secret()).isNull();
    }

    @Test
    void defaults_applied() {
        var props = new IdentityAssertionProperties(false, null, 0, null, null, -1, null, null, null, null, null);
        assertThat(props.ttlSeconds()).isEqualTo(60);
        assertThat(props.leewaySeconds()).isEqualTo(5);
        assertThat(props.audience()).isEqualTo("grassland-internal");
        assertThat(props.headerName()).isEqualTo("X-Grassland-Identity");
        assertThat(props.internalHeaderDenylist()).containsExactly(
                "X-Grassland-Identity", "X-Grassland-Account-Id",
                "X-Grassland-Active-Identity", "X-Grassland-Session-Token");
    }

    @Test
    void durationAccessors() {
        var props = new IdentityAssertionProperties(false, null, 120, null, null, 10, null, null, null, null, null);
        assertThat(props.ttl()).isEqualTo(Duration.ofSeconds(120));
        assertThat(props.leeway()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void enabled_blankSecret_throws() {
        assertThatThrownBy(() -> new IdentityAssertionProperties(true, "  ", 60, null, null, 5, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity-assertion.secret");
    }

    @Test
    void enabled_nullSecret_throws() {
        assertThatThrownBy(() -> new IdentityAssertionProperties(true, null, 60, null, null, 5, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customDenylist_preservedAndCopied() {
        var props = new IdentityAssertionProperties(true, "s", 60, null, null, 5, List.of("X-Custom-1", "X-Custom-2"), null, null, null, null);
        assertThat(props.internalHeaderDenylist()).containsExactly("X-Custom-1", "X-Custom-2");
    }

    @Test
    void redisReplayRequiresUrlWhenEnabledInKeyringMode() {
        var key = new IdentityAssertionProperties.KeyEntry(
                "edge-user-identity-v1", null, "user", "grassland-identity", "secret");

        assertThatThrownBy(() -> new IdentityAssertionProperties(
                true, null, 60, null, null, 5, null, "edge-bff", List.of(key), List.of(),
                new IdentityAssertionProperties.ReplayProtectionConfig(true, "redis", "", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis-url");
    }

    @Test
    void replayStorageRejectsUnknownValue() {
        assertThatThrownBy(() -> new IdentityAssertionProperties.ReplayProtectionConfig(
                true, "filesystem", "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redis or memory");
    }
}
