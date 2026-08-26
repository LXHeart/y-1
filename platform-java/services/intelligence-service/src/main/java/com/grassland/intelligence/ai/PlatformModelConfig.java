package com.grassland.intelligence.ai;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 平台默认 AI 模型配置（HLD §12.3 平台默认配置的第一步）。从环境变量读 Qwen base-url/api-key/model。
 *
 * <p>intelligence **不读 legacy per-user 设置**。此对象只承载平台 Qwen 凭据与启动默认值；
 * 运行时模型、主备、健康和并发配置由 model-control-plane 解析，个人 BYOK 由独立密钥控制面处理。
 * 启动期 {@link #validate} 校验 base-url/api-key 非空 + SSRF 防护；缺失或非法即 fail-fast。
 */
@Component
public class PlatformModelConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public PlatformModelConfig(Environment env) {
        this.baseUrl = env.getProperty("ai.qwen.base-url", "");
        this.apiKey = env.getProperty("ai.qwen.api-key", "");
        this.model = env.getProperty("ai.qwen.model", "qwen-plus");
        this.connectTimeout = Duration.ofMillis(env.getProperty("ai.qwen.connect-timeout-ms", Long.class, 5000L));
        this.readTimeout = Duration.ofMillis(env.getProperty("ai.qwen.read-timeout-ms", Long.class, 120000L));
    }

    /**
     * 启动期校验（任务书 #47 D8 放宽）。
     *
     * <p>{@code base-url} 仍必填——{@code PlatformProviderPolicy} 在构造期就用它奠定受信 origin 集，
     * 缺失会让整个 provider 白名单失去锚点。
     *
     * <p>{@code api-key} <b>改为可空</b>：S2 起平台密钥的真相源是 {@code platform_provider_credential}，
     * env 只是「该 capability 的凭据没配密钥时」的 bootstrap 兜底。两者都没有也不再拒绝启动——那会与
     * ADR-D16「content_safety 缺省不种、深检降级为仅 L1」直接打架（一个可选能力缺凭据不该让整个服务
     * 起不来）。代价改为运行时按 capability 503，由 {@code AiExecutionService.decryptIfNeeded} 抛出，
     * 不静默拿空 bearer 去打上游。
     *
     * <p>提供了 key 就仍校验强度——占位值比没有更危险（看起来配好了，实际上游 401）。
     */
    @PostConstruct
    void validate() {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("intelligence-service 需要配置 ai.qwen.base-url");
        }
        if (!apiKey.isBlank()) {
            String normalizedKey = apiKey.trim().toLowerCase(Locale.ROOT);
            if (normalizedKey.length() < 16
                    || normalizedKey.contains("replace-with")
                    || normalizedKey.contains("placeholder")
                    || normalizedKey.contains("changeme")
                    || normalizedKey.startsWith("your-")) {
                throw new IllegalStateException("intelligence-service 的 ai.qwen.api-key 不能使用短值或模板占位值");
            }
        }
        ProviderUrlGuard.validate(baseUrl);
    }

    /** env 是否提供了可用的 bootstrap 兜底密钥（D8：无兜底且凭据无密钥 → 运行时 503）。 */
    public boolean hasBootstrapKey() {
        return !apiKey.isBlank();
    }

    public String baseUrl() { return baseUrl; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration readTimeout() { return readTimeout; }
}
