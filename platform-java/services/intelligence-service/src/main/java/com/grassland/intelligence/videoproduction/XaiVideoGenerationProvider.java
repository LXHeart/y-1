package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * xAI Grok Imagine 异步视频生成适配器（对齐万相 provider 的 poll-only 形态）。
 *
 * <p>
 * 原生协议（docs.x.ai REST 参考 /v1/videos）：POST {@code /v1/videos/generations} （body
 * {@code model/prompt/image.url?/duration/aspect_ratio/resolution}）→
 * {@code request_id}；轮询 GET {@code /v1/videos/{request_id}} →
 * {@code status ∈ pending/done/expired/failed}，{@code done} 带
 * {@code video.url}（临时链接，归档由管线下游完成）与 {@code video.duration}。 成片自带音轨。失败载荷
 * {@code error.code/message}；{@code video.respect_moderation=false}
 * 表示生成被平台审核过滤，按失败收口（与本项目内容安全 posture 一致，不放行不可控素材）。
 *
 * <p>
 * new-api 中转实测契约（2026-09-03 凡人/fanrenapi，实跑取证）：任务状态词表为
 * {@code submitted/in_progress/completed/failed}（无 done/pending），完成响应<b>不带任何 URL</b>——
 * 成片在鉴权端点 {@code GET /v1/videos/{taskId}/content}（Bearer，裸 401；归档服务的裸 GET 下载不了）。
 * 故词表两侧并认；无 URL 的完成态由本适配器持凭据下载字节直传归档（{@code resultBytes}）。
 *
 * <p>
 * {@code duration} 上游限 1–15 秒（管线 planned_seconds 4–6 在域内，钳位防御）；
 * {@code resolution} 固定 {@code 720p}——上游值集仅 480p/720p（2026-09-03 实测 1080p 被拒
 * 400，docs.x.ai 契约核对），合成 normalize 时放大到管线画幅。参考图字段为顶层 {@code image_url}（直传 Data
 * URI）。{@code aspect_ratio} 直传（上游值集含管线的 9:16 与 16:9）。
 */
public class XaiVideoGenerationProvider implements VideoGenerationProvider {

	private static final int MIN_DURATION_SECONDS = 1;
	private static final int MAX_DURATION_SECONDS = 15;

	/** 与 VideoAssetArchiveService.MAX_BYTES 同限（成片字节经本适配器中转归档）。 */
	private static final int MAX_CONTENT_BYTES = 200 * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(XaiVideoGenerationProvider.class);

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
			payload.put("image_url", VideoProviderJson.dataImage(command.images().getFirst()));
		}
		payload.put("duration", clampDuration(command.durationSeconds()));
		payload.put("aspect_ratio", command.aspectRatio());
		payload.put("resolution", "720p");
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
				.map(this::readJson)
				.flatMap(node -> mapStatus(node, providerTaskId, requestedDurationSeconds));
	}

	private Mono<ProviderResult> mapStatus(JsonNode node, String taskId, int duration) {
		String status = VideoProviderJson.text(node, "/status", "/state");
		String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
		Integer actualDuration = VideoProviderJson.integer(node, "/video/duration");
		int effectiveDuration = actualDuration == null ? duration : actualDuration;
		switch (normalized) {
			// done=原生 xAI；completed=new-api 中转——同一完成语义
			case "done", "completed" -> {
				JsonNode moderation = node.at("/video/respect_moderation");
				if (moderation.isBoolean() && !moderation.asBoolean()) {
					return Mono.just(failed(taskId, effectiveDuration, "moderation_filtered",
							"xAI 平台审核过滤了该生成结果"));
				}
				String url = VideoProviderJson.text(node, "/video/url", "/video_url", "/url");
				if (url != null) {
					return Mono.just(new ProviderResult(ProviderResult.State.SUCCEEDED, taskId, 100, url,
							effectiveDuration, null, null));
				}
				// new-api 中转完成态无 URL：持凭据从 content 端点取字节（裸 GET 401，归档服务下不了）
				return downloadContent(taskId, effectiveDuration);
			}
			case "failed", "expired" -> {
				return Mono.just(failed(taskId, effectiveDuration,
						VideoProviderJson.text(node, "/error/code", "/code"),
						VideoProviderJson.text(node, "/error/message", "/message")));
			}
			case "pending", "submitted" -> {
				return Mono.just(new ProviderResult(ProviderResult.State.QUEUED, taskId,
						VideoProviderJson.progress(node, 10), null, effectiveDuration, null, null));
			}
			default -> {
				// 未知状态不再静默吞（2026-09-03 教训：completed 落这里轮询到 61 次上限判死）
				if (!normalized.isEmpty() && !"in_progress".equals(normalized)) {
					log.warn("xAI 轮询返回未知 status={} taskId={}，按 processing 继续轮询", status, taskId);
				}
				return Mono.just(new ProviderResult(ProviderResult.State.PROCESSING, taskId,
						VideoProviderJson.progress(node, 50), null, effectiveDuration, null, null));
			}
		}
	}

	/** 鉴权下载成片字节：GET {poll 路径}/content（原生 xAI 与 new-api 中转同形）。 */
	private Mono<ProviderResult> downloadContent(String taskId, int duration) {
		String path = endpoint.pollPath().replace("{taskId}", taskId) + "/content";
		return contentClient().get().uri(path).exchangeToMono(response -> {
			if (!response.statusCode().is2xxSuccessful()) {
				return response.bodyToMono(String.class).defaultIfEmpty("")
						.map(body -> failed(taskId, duration, "content_download_failed",
								"xAI 成片下载失败: HTTP " + response.statusCode().value()
										+ (body.isBlank() ? "" : " " + body.substring(0, Math.min(body.length(), 120)))));
			}
			long declared = response.headers().contentLength().orElse(-1L);
			if (declared > MAX_CONTENT_BYTES) {
				return Mono.just(failed(taskId, duration, "content_download_failed", "xAI 成片大小超出限制"));
			}
			return response.bodyToMono(byte[].class).timeout(endpoint.requestTimeout()).map(bytes -> {
				if (bytes.length == 0 || bytes.length > MAX_CONTENT_BYTES) {
					throw new IllegalStateException("xAI 成片大小超出限制");
				}
				return ProviderResult.withBytes(taskId, duration, bytes);
			});
		});
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

	/** 成片下载客户端：JSON 默认 codec 上限扛不住视频字节，抬到 200MB。 */
	private WebClient contentClient() {
		return ManagedWebClientFactory
				.builder(XaiVideoGenerationProvider.class, endpoint.requestTimeout(), MAX_CONTENT_BYTES)
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
