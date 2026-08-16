package com.grassland.intelligence.settings;

import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 从用户 analysis settings（user_settings type='analysis'）解析某 feature 的 BYOK provider 配置。
 *
 * <p>读取**未 mask** 的原始 DB 值（密钥只活在进程内，不进日志/响应）。返回的 {@link ByokConfig} 由调用方
 * 决定可用性：视频内容改编要求 provider 为 OpenAI 兼容的 qwen 系（provider=coze 走独立协议，不支持），
 * 且 baseUrl/apiKey 或 apiToken/model 齐备。baseUrl 的 SSRF 校验（HTTPS + 公网 DNS 固定）在执行侧由
 * {@code TextCompletionClient} 的 BYOK 分支强制（validateByokForExecution）。
 */
@Component
public class AnalysisByokResolver {

    private final UserSettingsRepository repo;
    private final ModelListingService.ObjectMapperHolder json = new ModelListingService.ObjectMapperHolder();

    public AnalysisByokResolver(UserSettingsRepository repo) {
        this.repo = repo;
    }

    /** 解析 feature 的 BYOK 配置；无 analysis 行或无该 feature → 空 Mono（调用方回落平台模型）。 */
    public Mono<ByokConfig> resolve(String accountId, String feature) {
        return repo.findByAccountAndType(accountId, "analysis")
                .map(json::parse)
                .defaultIfEmpty(Map.of())
                .flatMap(settings -> {
                    ByokConfig config = extract(settings, feature);
                    return config == null ? Mono.empty() : Mono.just(config);
                });
    }

    @SuppressWarnings("unchecked")
    private static ByokConfig extract(Map<String, Object> settings, String feature) {
        Object featuresObj = settings.get("features");
        if (!(featuresObj instanceof Map<?, ?> features)) {
            return null;
        }
        Object featureObj = features.get(feature);
        if (!(featureObj instanceof Map<?, ?> raw)) {
            return null;
        }
        return new ByokConfig(
                text(raw.get("provider")),
                text(raw.get("baseUrl")),
                text(raw.get("apiKey")),
                text(raw.get("apiToken")),
                text(raw.get("model")));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    /**
     * 用户级 BYOK provider 配置。{@link #bearer()} 优先 apiKey、回落 apiToken（legacy coze 语义）；
     * {@link #complete()} 判断 OpenAI 兼容调用是否齐备（baseUrl + bearer + model）。
     */
    public record ByokConfig(String provider, String baseUrl, String apiKey, String apiToken, String model) {

        public String bearer() {
            return apiKey != null ? apiKey : apiToken;
        }

        public boolean complete() {
            return baseUrl != null && model != null && bearer() != null;
        }
    }
}
