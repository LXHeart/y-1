package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlatformProviderPolicy（任务书 #58：受信 origin 表为唯一真相源）")
class PlatformProviderPolicyTest {

    private static TrustedOriginService origins(String... enabled) {
        TrustedOriginService service = mock(TrustedOriginService.class);
        Set<String> set = new HashSet<>();
        for (String origin : enabled) {
            set.add(origin);
        }
        when(service.enabledOrigins()).thenReturn(Set.copyOf(set));
        return service;
    }

    @Test
    @DisplayName("默认拒绝 HTTP 平台模型地址，包括 loopback")
    void rejectsHttpOriginsByDefault() {
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                origins("https://dashscope.aliyuncs.com:443"), false);

        assertThatThrownBy(() -> policy.validate("qwen", "http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void sandboxOnlyAcceptsTheBuiltInNonRoutableOrigin() {
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                origins("https://dashscope.aliyuncs.com:443"), false);

        assertThat(policy.validate("sandbox", "https://sandbox.invalid").toString())
                .isEqualTo("https://sandbox.invalid");
        assertThatThrownBy(() -> policy.validate("sandbox", "https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOnlyListedOriginsRegardlessOfProviderDialect() {
        // 决策 B：qwen 与 openai-compatible 共用同一张 origin 表——信任对象是目的地，不是方言
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                origins("https://api.openai.com:443", "https://dashscope.aliyuncs.com:443"), false);

        assertThat(policy.validate("openai-compatible", "https://api.openai.com/v1").getHost())
                .isEqualTo("api.openai.com");
        assertThat(policy.validate("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1").getHost())
                .isEqualTo("dashscope.aliyuncs.com");
        assertThatThrownBy(() -> policy.validate("openai-compatible", "https://example.com/v1"))
                .isInstanceOf(UntrustedPlatformOriginException.class)
                .hasMessageContaining("受信");
    }

    @Test
    void missingOriginFailsClosedWhenCacheNotWarmed() {
        // 预热失败 = 空集：所有平台 base-url 校验拒绝（fail-closed），Sandbox 除外
        PlatformProviderPolicy policy = new PlatformProviderPolicy(origins(), false);

        assertThatThrownBy(() -> policy.validate("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1"))
                .isInstanceOf(UntrustedPlatformOriginException.class);
        assertThat(policy.validate("sandbox", "https://sandbox.invalid").toString())
                .isEqualTo("https://sandbox.invalid");
    }

    @Test
    void loopbackHttpAllowedOnlyWithExplicitFlag() {
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                origins("http://localhost:9099"), true);

        assertThat(policy.validate("qwen", "http://localhost:9099/v1").getPort()).isEqualTo(9099);
    }

    @Test
    void defaultPortOriginsAreNormalizedForComparison() {
        // 表行是 https://x.com（无显式端口）时，校验 https://x.com:443 亦命中——归一化后比较
        PlatformProviderPolicy policy = new PlatformProviderPolicy(
                origins("https://api.openai.com:443"), false);

        assertThat(policy.validate("openai-compatible", "https://api.openai.com:443/v1").getPort())
                .isEqualTo(443);
    }
}
