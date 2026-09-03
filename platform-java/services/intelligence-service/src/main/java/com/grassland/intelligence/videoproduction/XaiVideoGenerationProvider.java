package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * xAI Grok Imagine 异步视频生成适配器（对齐万相 provider 的 poll-only 形态）。
 *
 * <p>
 * 协议（docs.x.ai REST 参考 /v1/videos）：POST {@code /v1/videos/generations} （body
 * {@code model/prompt/image.url?/duration/aspect_ratio/resolution}）→
 * {@code request_id}；轮询 GET {@code /v1/videos/{request_id}} →
 * {@code status ∈ pending/done/expired/failed}，{@code done} 带
 * {@code video.url}（临时链接，归档由管线下游完成）与 {@code video.duration}。 成片自带音轨。失败载荷
 * {@code error.code/message}；{@code video.respect_moderation=false}
 * 表示生成被平台审核过滤，按失败收口（与本项目内容安全 posture 一致，不放行不可控素材）。
 *
 * <p>
 * {@code duration} 上游限 1–15 秒（管线 planned_seconds 4–6 在域内，钳位防御）；
 * {@code resolution} 固定 {@code 1080p}——管线两档画幅（1080x1920/1920x1080）均为 1080
 * 线，低清档会在合成 normalize 时被放大损失画质。{@code aspect_ratio} 直传 （上游值集含管线的 9:16 与 16:9）。
 */
public class XaiVideoGenerationProvider implements VideoGenerationProvider {

	private static final int MIN_DURATION_SECONDS = 1;
	private static final int MAX_DURATION_SECONDS = 15;

	private final VideoProviderEndpoint endpoint;
	private final ObjectMapper mapper = new ObjectMapper();

	public XaiVideoGenerationProvider(VideoProviderEndpoint endpoint) {
		this.endpoint = endpoint;
	}

	@Override
	public String id() {
		return "xai";
	}

	@Override
	public Mono<ProviderResult> submit(ProviderCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", command.model());
		payload.put("prompt", command.prompt());
		if (!command.images().isEmpty()) {
			payload.put("image", Map.of("url", VideoProviderJson.dataImage(command.images().getFirst())));
		}
		payload.put("duration", clampDuration(command.durationSeconds()));
		payload.put("aspect_ratio", command.aspectRatio());
		payload.put("resolution", "1080p");
		return client().post().uri(endpoint.createPath()).bodyValue(payload).retrieve().bodyToMono(String.class)
				.timeout(endpoint.requestTimeout()).map(this::readJson).map(node -> {
					String requestId = VideoProviderJson.text(node, "/request_id", "/id");
					if (requestId == null) {
						throw new IllegalStateException("xAI 创建任务响应缺少 request_id");
					}
					return new ProviderResult(ProviderResult.State.QUEUED, requestId, 5, null,
							command.durationSeconds(), null, null);
				});
	}

	@Override
	public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
		String path = endpoint.pollPath().replace("{taskId}", providerTaskId);
		return client().get().uri(path).retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout())
				.map(this::readJson).map(node -> mapStatus(node, providerTaskId, requestedDurationSeconds));
	}

	private ProviderResult mapStatus(JsonNode node, String taskId, int duration) {
		String status = VideoProviderJson.text(node, "/status", "/state");
		String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
		Integer actualDuration = VideoProviderJson.integer(node, "/video/duration");
		int effectiveDuration = actualDuration == null ? duration : actualDuration;
		switch (normalized) {
			case "done" -> {
				JsonNode moderation = node.at("/video/respect_moderation");
				if (moderation.isBoolean() && !moderation.asBoolean()) {
					return failed(taskId, effectiveDuration, "moderation_filtered", "xAI 平台审核过滤了该生成结果");
				}
				String url = VideoProviderJson.text(node, "/video/url", "/video_url", "/url");
				if (url == null) {
					return failed(taskId, effectiveDuration, "missing_result", "xAI 成功响应缺少 video.url");
				}
				return new ProviderResult(ProviderResult.State.SUCCEEDED, taskId, 100, url, effectiveDuration, null,
						null);
			}
			case "failed", "expired" -> {
				return failed(taskId, effectiveDuration, VideoProviderJson.text(node, "/error/code", "/code"),
						VideoProviderJson.text(node, "/error/message", "/message"));
			}
			case "pending" -> {
				return new ProviderResult(ProviderResult.State.QUEUED, taskId, VideoProviderJson.progress(node, 10),
						null, effectiveDuration, null, null);
			}
			default -> {
				return new ProviderResult(ProviderResult.State.PROCESSING, taskId, VideoProviderJson.progress(node, 50),
						null, effectiveDuration, null, null);
			}
		}
	}

	private static int clampDuration(int seconds) {
		return Math.max(MIN_DURATION_SECONDS, Math.min(MAX_DURATION_SECONDS, seconds));
	}

	private static ProviderResult failed(String taskId, int duration, String code, String message) {
		return new ProviderResult(ProviderResult.State.FAILED, taskId, 100, null, duration,
				code == null ? "provider_failed" : code, message == null ? "xAI 视频生成失败" : message);
	}

	private WebClient client() {
		return ManagedWebClientFactory.builder(XaiVideoGenerationProvider.class, endpoint.requestTimeout())
				.baseUrl(endpoint.baseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey())
				.build();
	}

	private JsonNode readJson(String body) {
		try {
			return mapper.readTree(body);
		} catch (Exception error) {
			throw new IllegalStateException("xAI 响应 JSON 无效", error);
		}
	}
}
