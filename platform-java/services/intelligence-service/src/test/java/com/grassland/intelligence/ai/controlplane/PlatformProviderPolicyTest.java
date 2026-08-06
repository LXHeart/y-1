package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.PlatformModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformProviderPolicy")
class PlatformProviderPolicyTest {

    @Mock
    PlatformModelConfig defaults;

    @Test
    @DisplayName("默认拒绝 HTTP 平台模型地址，包括 loopback")
    void rejectsHttpOriginsByDefault() {
        when(defaults.baseUrl()).thenReturn("https://dashscope.aliyuncs.com");
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                defaults, "https://dashscope.aliyuncs.com");

        assertThatThrownBy(() -> policy.validate("qwen", "http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }
}
