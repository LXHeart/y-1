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

    private PlatformModelConfig with(String baseUrl, String apiKey, String model) {
        MockEnvironment env = new MockEnvironment();
        if (baseUrl != null) env.setProperty("ai.qwen.base-url", baseUrl);
        if (apiKey != null) env.setProperty("ai.qwen.api-key", apiKey);
        if (model != null) env.setProperty("ai.qwen.model", model);
        return new PlatformModelConfig(env);
    }

    @Test
    @DisplayName("base-url/api-key 任一缺失 → fail-fast")
    void missingConfigFailsFast() {
        assertThatThrownBy(() -> with("https://dashscope.aliyuncs.com", null, null).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with(null, "sk-xxx", null).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> with("  ", "  ", null).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("base-url 指向私有 IP → SSRF 拒绝（fail-fast）")
    void privateBaseUrlFailsFast() {
        assertThatThrownBy(() -> with("http://127.0.0.1", "sk-xxx", null).validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已拒绝");
    }

    @Test
    @DisplayName("合法配置 → validate 通过，读取值正确（含默认超时/模型）")
    void validConfigPassesAndReadsValues() {
        PlatformModelConfig cfg = with("https://dashscope.aliyuncs.com", "sk-xxx", "qwen-turbo");
        cfg.validate();
        assertThat(cfg.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com");
        assertThat(cfg.apiKey()).isEqualTo("sk-xxx");
        assertThat(cfg.model()).isEqualTo("qwen-turbo");
        assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(cfg.readTimeout()).isEqualTo(Duration.ofMillis(120000));
    }

    @Test
    @DisplayName("model 默认 qwen-plus；超时可覆盖")
    void defaultsAndOverrides() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ai.qwen.base-url", "https://dashscope.aliyuncs.com");
        env.setProperty("ai.qwen.api-key", "sk-xxx");
        env.setProperty("ai.qwen.connect-timeout-ms", "2000");
        env.setProperty("ai.qwen.read-timeout-ms", "30000");
        PlatformModelConfig cfg = new PlatformModelConfig(env);
        cfg.validate();
        assertThat(cfg.model()).isEqualTo("qwen-plus");
        assertThat(cfg.connectTimeout()).isEqualTo(Duration.ofMillis(2000));
        assertThat(cfg.readTimeout()).isEqualTo(Duration.ofMillis(30000));
    }
}
