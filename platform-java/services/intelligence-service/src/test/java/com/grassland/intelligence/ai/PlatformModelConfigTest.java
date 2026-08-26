package com.grassland.intelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link PlatformModelConfig} 启动 fail-fast：base-url/api-key 缺失或 base-url SSRF 非法即拒。
 * 直接构造（不经 Spring 容器 → {@code @PostConstruct} 不自动触发），手动调包级 {@code validate()} 复刻启动期行为。
 */
class PlatformModelConfigTest {

    private static final String VALID_KEY = "sk-synthetic-unit-test-key";

    private PlatformModelConfig with(String baseUrl, String apiKey, String model) {
        MockEnvironment env = new MockEnvironment();
        if (baseUrl != null) env.setProperty("ai.qwen.base-url", baseUrl);
        if (apiKey != null) env.setProperty("ai.qwen.api-key", apiKey);
        if (model != null) env.setProperty("ai.qwen.model", model);
        return new PlatformModelConfig(env);
    }

    @Test
    @DisplayName("base-url 缺失 → fail-fast（受信 origin 集失去锚点）")
    void missingBaseUrlFailsFast() {
        assertThatThrownBy(() -> with(null, VALID_KEY, null).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with("  ", "  ", null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 任务书 #47 D8：api-key 缺失<b>不再</b>拒绝启动——平台密钥的真相源已是
     * {@code platform_provider_credential}，env 只是兜底。严格 fail-fast 会与 ADR-D16
     * 「content_safety 缺省不种、深检降级为仅 L1」打架。代价改为运行时按 capability 503。
     */
    @Test
    @DisplayName("api-key 缺失 → 启动通过（凭据表是真相源），hasBootstrapKey=false")
    void missingApiKeyNoLongerFailsFast() {
        PlatformModelConfig cfg = with("https://dashscope.aliyuncs.com", null, null);
        cfg.validate();
        assertThat(cfg.hasBootstrapKey()).isFalse();
    }

    @Test
    @DisplayName("短值或模板 api-key → fail-fast")
    void placeholderApiKeyFailsFast() {
        for (String apiKey : new String[]{"sk-short", "replace-with-qwen-api-key", "placeholder-qwen-key",
                "changeme-qwen-api-key", "your-qwen-api-key"}) {
            assertThatThrownBy(() -> with("https://dashscope.aliyuncs.com", apiKey, null).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("模板占位值");
        }
    }

    @Test
    @DisplayName("base-url 指向私有 IP → SSRF 拒绝（fail-fast）")
    void privateBaseUrlFailsFast() {
        assertThatThrownBy(() -> with("http://127.0.0.1", VALID_KEY, null).validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已拒绝");
    }

    @Test
    @DisplayName("合法配置 → validate 通过，读取值正确（含默认超时/模型）")
    void validConfigPassesAndReadsValues() {
        PlatformModelConfig cfg = with("https://dashscope.aliyuncs.com", VALID_KEY, "qwen-turbo");
        cfg.validate();
        assertThat(cfg.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(cfg.apiKey()).isEqualTo(VALID_KEY);
        assertThat(cfg.model()).isEqualTo("qwen-turbo");
        assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(cfg.readTimeout()).isEqualTo(Duration.ofMillis(120000));
    }

    @Test
    @DisplayName("model 默认 qwen-plus；超时可覆盖")
    void defaultsAndOverrides() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ai.qwen.base-url", "https://dashscope.aliyuncs.com");
        env.setProperty("ai.qwen.api-key", VALID_KEY);
        env.setProperty("ai.qwen.connect-timeout-ms", "2000");
        env.setProperty("ai.qwen.read-timeout-ms", "30000");
        PlatformModelConfig cfg = new PlatformModelConfig(env);
        cfg.validate();
        assertThat(cfg.model()).isEqualTo("qwen-plus");
        assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(cfg.readTimeout()).isEqualTo(Duration.ofMillis(30000));
    }
}
