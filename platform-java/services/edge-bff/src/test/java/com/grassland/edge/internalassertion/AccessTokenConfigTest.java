package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccessTokenConfigTest {

    @Test
    void parsesPreviousKeys() {
        var keys = AccessTokenConfig.parsePreviousKeys("access-token-v1=old-secret, access-token-v0=older-secret");

        assertThat(keys).containsOnlyKeys("access-token-v1", "access-token-v0");
        assertThat(new String(keys.get("access-token-v1"), StandardCharsets.UTF_8)).isEqualTo("old-secret");
    }

    @Test
    void rejectsMalformedAndDuplicateEntries() {
        assertThatThrownBy(() -> AccessTokenConfig.parsePreviousKeys("missing-separator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessTokenConfig.parsePreviousKeys("v1=one,v1=two"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
