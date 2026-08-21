package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Service
public class VideoSegmentAnalysisService {
	private final FrozenTextExecutionService frozenText;
	private final String publicOrigin;

	public VideoSegmentAnalysisService(FrozenTextExecutionService frozenText, Environment environment) {
		this.frozenText = frozenText;
		this.publicOrigin = environment.getProperty("app.public-backend-origin", "").replaceFirst("/$", "");
	}

	/**
	 * 独立模式分段分析（GL-P3-AI-001 尾巴清偿）：所有片段合并为一次批量 AI run——
	 * 一次计费/留痕/失败退款（executeIndependentBatch），归一与任务模式同规则。
	 */
	public Mono<Map<String, Object>> analyze(String platform, List<String> artifactIds, Duration timeout,
			ServerWebExchange exchange) {
		if (publicOrigin.isBlank())
			return Mono.error(new IllegalStateException("PUBLIC_BACKEND_ORIGIN 未配置"));
		List<List<ChatMessage>> batches = artifactIds.stream()
				.map(id -> List.of(ChatMessage
						.user(List.of(ContentPart.video(publicOrigin + "/api/" + platform + "/analysis-media/" + id),
								ContentPart.text(VideoAnalysisPrompts.analysis())))))
				.toList();
		return frozenText
				.executeIndependentBatch(exchange, batches, 4096, CreditFeature.VIDEO_ANALYSIS, timeout,
						completions -> merge(
								completions
										.stream().map(completion -> VideoAnalysisResultNormalizer
												.normalize(completion.content(), completion.providerRunId()))
										.toList()));
	}

	public Mono<Map<String, Object>> analyzeTask(String platform, List<String> artifactIds, ServerWebExchange exchange,
			java.util.UUID snapshotId, ChatMessage promptContext) {
		if (publicOrigin.isBlank())
			return Mono.error(new IllegalStateException("PUBLIC_BACKEND_ORIGIN 未配置"));
		List<List<ChatMessage>> batches = artifactIds.stream()
				.map(id -> List.of(promptContext,
						ChatMessage.user(
								List.of(ContentPart.video(publicOrigin + "/api/" + platform + "/analysis-media/" + id),
										ContentPart.text(VideoAnalysisPrompts.analysis())))))
				.toList();
		return frozenText.executeBatch(exchange, snapshotId, batches, 4096, CreditFeature.VIDEO_ANALYSIS,
				completions -> merge(completions.stream()
						.map(completion -> VideoAnalysisResultNormalizer.normalize(completion.content(), null))
						.toList()));
	}

	public static Map<String, Object> merge(List<Map<String, Object>> segments) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (String field : List.of("videoCaptions", "videoScript", "charactersDescription", "voiceDescription",
				"propsDescription", "sceneDescription")) {
			String value = segments.stream().map(item -> item.get(field)).filter(String.class::isInstance)
					.map(String.class::cast).filter(text -> !text.isBlank()).distinct().reduce((a, b) -> a + "\n" + b)
					.orElse(null);
			if (value != null)
				result.put(field, value);
		}
		segments.stream().map(item -> item.get("runId")).filter(String.class::isInstance).findFirst()
				.ifPresent(runId -> result.put("runId", runId));
		return result;
	}
}
