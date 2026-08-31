package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
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
 * <p>
 * 任务模式（{@link #adaptTask}）走 {@link FrozenTextExecutionService} 冻结执行（快照内 AI 配置 +
 * 平台额度）。 独立模式（{@link #adapt}）经 {@link RoutedTextCompletionService}
 * 统一路由（2026-08-26 双通道收敛， 取代旧 {@code AnalysisByokResolver} 读 user_settings
 * {@code features.video} 的老路）：登录用户按
 * 「模型密钥」开关用自定义模型（BYOK，不扣平台额度）或平台内置模型（管理后台控制面）；匿名回落平台。 BYOK 分支沿用
 * {@code TextCompletionClient} 的 HTTPS + 全量公网 DNS 钉扎（SSRF/rebinding 防护）。
 */
@Service
public class VideoRecreationAdaptationService {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
	private static final int MAX_COMPLETION_TOKENS = 4096;

	private final VideoRecreationAdaptationResultNormalizer normalizer;
	private final FrozenTextExecutionService frozenText;
	private final RoutedTextCompletionService routed;
	private final CreationGenerationRecorder lineage;
	private final Duration timeout;

	// 任务书 #61：去AI味 skill 注入（免费 Routed 通道显式接入；计费流在执行环内统一注入）
	private final com.grassland.intelligence.humanize.HumanizeInjectionService humanize;

	public VideoRecreationAdaptationService(VideoRecreationAdaptationResultNormalizer normalizer,
			FrozenTextExecutionService frozenText, RoutedTextCompletionService routed,
			CreationGenerationRecorder lineage, Environment environment,
			com.grassland.intelligence.humanize.HumanizeInjectionService humanize) {
		this.normalizer = normalizer;
		this.frozenText = frozenText;
		this.routed = routed;
		this.lineage = lineage;
		this.humanize = humanize;
		long timeoutMs = environment.getProperty("ai.video-recreation.timeout-ms", Long.class,
				DEFAULT_TIMEOUT.toMillis());
		this.timeout = Duration.ofMillis(Math.max(1, Math.min(timeoutMs, 600_000)));
	}

	/** 独立模式改编：统一路由（BYOK 开关/平台控制面）。 */
	public Mono<Map<String, Object>> adapt(VideoRecreationAdaptationRequest request, String accountId) {
		return adapt(request, accountId, null);
	}

	public Mono<Map<String, Object>> adapt(VideoRecreationAdaptationRequest request, String accountId,
			String organizationId) {
		String prompt = VideoRecreationAdaptationPrompts.build(request);
		List<ContentPart> parts = new ArrayList<>(request.referenceImages());
		parts.add(ContentPart.text(prompt));
		return humanize.injectCreative(List.of(ChatMessage.user(parts)))
				.flatMap(msgs -> routed.resolveFor(accountId, organizationId)
						.flatMap(resolution -> routed
								.completeWith(resolution, msgs, MAX_COMPLETION_TOKENS, timeout, "视频内容改编失败，请稍后重试")
								.map(completion -> recordAdaptation(request, prompt, completion, resolution, accountId,
										organizationId))));
	}

	public Mono<Map<String, Object>> adaptTask(VideoRecreationAdaptationRequest request,
			VideoRecreationTaskCreationContext.Binding binding, ServerWebExchange exchange) {
		String prompt = VideoRecreationAdaptationPrompts.build(request);
		List<ContentPart> parts = new ArrayList<>(request.referenceImages());
		parts.add(ContentPart.text(prompt));
		return frozenText
				.executeTraced(exchange, binding.snapshot().id(),
						List.of(binding.promptContext(), ChatMessage.user(parts)), MAX_COMPLETION_TOKENS,
						CreditFeature.AI_RUN_TEXT, completion -> normalizer.normalize(completion.content(), null))
				.flatMap(trace -> record(request, prompt, trace.value(), binding.snapshot().accountId(),
						binding.snapshot().organizationId(), CreationGeneration.Mode.TASK, binding.snapshot().id(),
						trace.runId(),
						trace.byok() ? CreationGeneration.Resolution.BYOK : CreationGeneration.Resolution.PLATFORM,
						trace.provider(), trace.model(), trace.platformModelVersion(), null).thenReturn(trace.value()));
	}

	/** 独立模式落痕：provider/model 取本次路由决策（旧 env 固定值作古）。 */
	private Map<String, Object> recordAdaptation(VideoRecreationAdaptationRequest request, String prompt,
			TextCompletionResult completion, RoutedTextCompletionService.Routed resolution, String accountId,
			String organizationId) {
		Map<String, Object> result = normalizer.normalize(completion.content(), completion.providerRunId());
		record(request, prompt, result, accountId, organizationId, CreationGeneration.Mode.INDEPENDENT, null, null,
				resolution.byok() ? CreationGeneration.Resolution.BYOK : CreationGeneration.Resolution.PLATFORM,
				resolution.resolution().provider(), resolution.resolution().model(),
				resolution.resolution().platformModelVersion() == 0
						? null
						: resolution.resolution().platformModelVersion(),
				completion.providerRunId());
		return result;
	}

	private Mono<CreationGeneration> record(VideoRecreationAdaptationRequest request, String prompt,
			Map<String, Object> result, String accountId, String organizationId, CreationGeneration.Mode mode,
			java.util.UUID contextSnapshotId, java.util.UUID aiRunId, CreationGeneration.Resolution resolution,
			String actualProvider, String model, Integer platformModelVersion, String upstreamRunId) {
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("topic", request.extractedContent());
		input.put("platform", request.platform());
		input.put("referenceImageCount", request.referenceImages().size());
		input.put("customInstruction", request.userInstructions());
		return lineage.record(new CreationGenerationRecorder.Command(CreationGeneration.Kind.VIDEO_ADAPTATION, mode,
				contextSnapshotId, aiRunId, resolution, actualProvider, model, platformModelVersion, upstreamRunId,
				prompt, input, List.of(), result, List.of(), accountId, organizationId));
	}
}
