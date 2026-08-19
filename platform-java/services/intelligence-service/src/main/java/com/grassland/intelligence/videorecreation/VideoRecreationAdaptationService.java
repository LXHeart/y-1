package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
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
    private final CreationGenerationRecorder lineage;
    private final Duration timeout;
    private final String provider;

    public VideoRecreationAdaptationService(
            AiCapabilityAdapter ai, VideoRecreationAdaptationResultNormalizer normalizer,
            FrozenTextExecutionService frozenText, AnalysisByokResolver byok,
            TextCompletionClient textCompletion, CreationGenerationRecorder lineage,
            Environment environment) {
        this.ai = ai;
        this.normalizer = normalizer;
        this.frozenText = frozenText;
        this.byok = byok;
        this.textCompletion = textCompletion;
        this.lineage = lineage;
        this.provider = environment.getProperty("ai.video-recreation.provider", "qwen");
        long timeoutMs = environment.getProperty(
                "ai.video-recreation.timeout-ms", Long.class, DEFAULT_TIMEOUT.toMillis());
        this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
    }

    /** 独立模式改编：用户 BYOK（features.video）优先，未配置回落平台 Qwen。 */
    public Mono<Map<String, Object>> adapt(VideoRecreationAdaptationRequest request, String accountId) {
        return adapt(request, accountId, null);
    }

    public Mono<Map<String, Object>> adapt(
            VideoRecreationAdaptationRequest request, String accountId, String organizationId) {
        String prompt = VideoRecreationAdaptationPrompts.build(request);
        List<ContentPart> parts = new ArrayList<>(request.referenceImages());
        parts.add(ContentPart.text(prompt));
        if (accountId == null || accountId.isBlank()) {
            return adaptWithPlatform(parts, request, prompt, accountId, organizationId);
        }
        return byok.resolve(accountId, "video")
                .flatMap(config -> {
                    if (config.provider() != null && !"qwen".equalsIgnoreCase(config.provider())) {
                        return Mono.error(new IntelligenceException(400, UNSUPPORTED));
                    }
                    return config.complete()
                            ? adaptWithByok(parts, config, request, prompt, accountId, organizationId)
                            : adaptWithPlatform(parts, request, prompt, accountId, organizationId);
                })
                .switchIfEmpty(Mono.defer(() -> adaptWithPlatform(
                        parts, request, prompt, accountId, organizationId)));
    }

    private Mono<Map<String, Object>> adaptWithPlatform(
            List<ContentPart> parts, VideoRecreationAdaptationRequest request, String prompt,
            String accountId, String organizationId) {
        if (!"qwen".equalsIgnoreCase(provider)) {
            return Mono.error(new IntelligenceException(400, UNSUPPORTED));
        }
        return ai.completeMultimodalMeta(parts, timeout)
                .flatMap(meta -> {
                    Map<String, Object> result = normalizer.normalize(meta.content(), meta.runId());
                    return record(request, prompt, result, accountId, organizationId,
                            CreationGeneration.Mode.INDEPENDENT, null, null,
                            CreationGeneration.Resolution.PLATFORM,
                            firstNonBlank(meta.provider(), provider), meta.model(), null, meta.runId())
                            .thenReturn(result);
                });
    }

    /** BYOK 改编：OpenAI 兼容多模态调用，D-11 不扣平台额度；baseUrl 执行前强制校验（HTTPS/DNS 固定）。 */
    private Mono<Map<String, Object>> adaptWithByok(
            List<ContentPart> parts, AnalysisByokResolver.ByokConfig config,
            VideoRecreationAdaptationRequest request, String prompt,
            String accountId, String organizationId) {
        return textCompletion.completeMessages(
                        config.baseUrl(), config.bearer(), config.model(),
                        List.of(ChatMessage.user(parts)), MAX_COMPLETION_TOKENS, true)
                .flatMap(completion -> {
                    Map<String, Object> result = normalizer.normalize(completion.content(), null);
                    return record(request, prompt, result, accountId, organizationId,
                            CreationGeneration.Mode.INDEPENDENT, null, null,
                            CreationGeneration.Resolution.BYOK,
                            config.provider(), config.model(), null, null).thenReturn(result);
                })
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
        return frozenText.executeTraced(
                exchange, binding.snapshot().id(),
                List.of(binding.promptContext(), ChatMessage.user(parts)),
                MAX_COMPLETION_TOKENS, CreditFeature.AI_RUN_TEXT,
                completion -> normalizer.normalize(completion.content(), null))
                .flatMap(trace -> record(
                                request, prompt, trace.value(), binding.snapshot().accountId(),
                                binding.snapshot().organizationId(), CreationGeneration.Mode.TASK,
                                binding.snapshot().id(), trace.runId(),
                                trace.byok() ? CreationGeneration.Resolution.BYOK
                                        : CreationGeneration.Resolution.PLATFORM,
                                trace.provider(), trace.model(), trace.platformModelVersion(), null)
                        .thenReturn(trace.value()));
    }

    private Mono<CreationGeneration> record(
            VideoRecreationAdaptationRequest request, String prompt, Map<String, Object> result,
            String accountId, String organizationId, CreationGeneration.Mode mode,
            java.util.UUID contextSnapshotId, java.util.UUID aiRunId,
            CreationGeneration.Resolution resolution, String actualProvider, String model,
            Integer platformModelVersion, String upstreamRunId) {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("topic", request.extractedContent());
        input.put("platform", request.platform());
        input.put("referenceImageCount", request.referenceImages().size());
        input.put("customInstruction", request.userInstructions());
        return lineage.record(new CreationGenerationRecorder.Command(
                CreationGeneration.Kind.VIDEO_ADAPTATION, mode, contextSnapshotId, aiRunId,
                resolution, actualProvider, model, platformModelVersion, upstreamRunId, prompt,
                input, List.of(), result, List.of(), accountId, organizationId));
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }
}
