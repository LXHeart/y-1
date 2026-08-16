package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.settings.AnalysisByokResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 视频内容改编 provider 编排。
 *
 * <p>任务模式（{@link #adaptTask}）走 {@link FrozenTextExecutionService} 冻结执行（快照内 AI 配置 + 平台额度）。
 * 独立模式（{@link #adapt}）：优先使用用户 analysis settings 里的 BYOK 配置（features.video，
 * OpenAI 兼容 qwen 系；D-11 BYOK 不扣平台额度），经 {@code TextCompletionClient} BYOK 分支强制
 * HTTPS + 公网 DNS 固定（SSRF/rebinding 防护）；未配置 BYOK 时回落平台级 Qwen
 * （{@code ai.video-recreation.provider} 非 qwen → 400 不支持）。provider=coze 走独立协议，
 * 改编能力不支持（显式 400 引导切换）。
 */
@Service
public class VideoRecreationAdaptationService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final String UNSUPPORTED = "当前视频分析服务不支持内容改编，请切换到 Qwen 后重试";
    private static final int MAX_COMPLETION_TOKENS = 4096;

    private final AiCapabilityAdapter ai;
    private final VideoRecreationAdaptationResultNormalizer normalizer;
    private final FrozenTextExecutionService frozenText;
    private final AnalysisByokResolver byok;
    private final TextCompletionClient textCompletion;
    private final Duration timeout;
    private final String provider;

    public VideoRecreationAdaptationService(
            AiCapabilityAdapter ai, VideoRecreationAdaptationResultNormalizer normalizer,
            FrozenTextExecutionService frozenText, AnalysisByokResolver byok,
            TextCompletionClient textCompletion, Environment environment) {
        this.ai = ai;
        this.normalizer = normalizer;
        this.frozenText = frozenText;
        this.byok = byok;
        this.textCompletion = textCompletion;
        this.provider = environment.getProperty("ai.video-recreation.provider", "qwen");
        long timeoutMs = environment.getProperty(
                "ai.video-recreation.timeout-ms", Long.class, DEFAULT_TIMEOUT.toMillis());
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
    }

    /** 独立模式改编：用户 BYOK（features.video）优先，未配置回落平台 Qwen。 */
    public Mono<Map<String, Object>> adapt(VideoRecreationAdaptationRequest request, String accountId) {
        String prompt = VideoRecreationAdaptationPrompts.build(request);
        List<ContentPart> parts = new ArrayList<>(request.referenceImages());
        parts.add(ContentPart.text(prompt));
        if (accountId == null || accountId.isBlank()) {
            return adaptWithPlatform(parts);
        }
        return byok.resolve(accountId, "video")
                .flatMap(config -> {
                    if (config.provider() != null && !"qwen".equalsIgnoreCase(config.provider())) {
                        return Mono.error(new IntelligenceException(400, UNSUPPORTED));
                    }
                    return config.complete()
                            ? adaptWithByok(parts, config)
                            : adaptWithPlatform(parts);
                })
                .switchIfEmpty(Mono.defer(() -> adaptWithPlatform(parts)));
    }

    private Mono<Map<String, Object>> adaptWithPlatform(List<ContentPart> parts) {
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.error(new IntelligenceException(400, UNSUPPORTED));
        }
        return ai.completeMultimodal(parts, timeout)
                .map(content -> normalizer.normalize(content, null));
    }

    /** BYOK 改编：OpenAI 兼容多模态调用，D-11 不扣平台额度；baseUrl 执行前强制校验（HTTPS/DNS 固定）。 */
    private Mono<Map<String, Object>> adaptWithByok(List<ContentPart> parts, AnalysisByokResolver.ByokConfig config) {
        return textCompletion.completeMessages(
                        config.baseUrl(), config.bearer(), config.model(),
                        List.of(ChatMessage.user(parts)), MAX_COMPLETION_TOKENS, true)
                .map(result -> normalizer.normalize(result.content(), null))
                .onErrorMap(IllegalArgumentException.class,
                        error -> new IntelligenceException(400, error.getMessage()));
    }

    public Mono<Map<String, Object>> adaptTask(
            VideoRecreationAdaptationRequest request,
            VideoRecreationTaskCreationContext.Binding binding,
            ServerWebExchange exchange) {
        String prompt = VideoRecreationAdaptationPrompts.build(request);
        List<ContentPart> parts = new ArrayList<>(request.referenceImages());
        parts.add(ContentPart.text(prompt));
        return frozenText.execute(
                exchange, binding.snapshot().id(),
                List.of(binding.promptContext(), ChatMessage.user(parts)),
                MAX_COMPLETION_TOKENS, CreditFeature.AI_RUN_TEXT,
                completion -> normalizer.normalize(completion.content(), null));
    }
}
