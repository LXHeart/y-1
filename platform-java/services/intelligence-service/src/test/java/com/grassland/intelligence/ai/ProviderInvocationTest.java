package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderInvocationTest {

    @Test
    void toStringAlwaysRedactsBearer() {
        ProviderInvocation invocation = new ProviderInvocation(
                "openai-compatible", "https://api.openai.com/v1", "model", "secret-bearer-value", true);

        assertThat(invocation.toString())
                .contains("openai-compatible", "[REDACTED]")
                .doesNotContain("secret-bearer-value");
    }
}
