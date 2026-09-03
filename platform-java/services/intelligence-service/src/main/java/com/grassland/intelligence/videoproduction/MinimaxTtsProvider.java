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
 * MiniMax T2A 异步合成适配器（任务书 #64 卡5，P1）：submit（model/voice/text）→ taskId → poll →
 * 音频 file URL。契约形态对齐 {@link MinimaxVideoGenerationProvider} 的 file_id/URL 取法
 * （成功无直链时经 /v1/files/retrieve 兜底）；按控制面解析的 {@link VideoProviderEndpoint} 构造。
 *
 * <p>
 * 返回的字级/句级时间戳（若上游给）映射为 cues 的职责在 worker（TtsCues）；本类只搬运协议。
 */
public class MinimaxTtsProvider implements TtsProvider {

	/** 默认音色（任务书 §卡5「voice(默认)」）；治理台暂不暴露音色配置。 */
	static final String DEFAULT_VOICE = "male-qn-qingse";

	/** 官方异步契约（2026-09-03 实测纠偏：原 t2a_v2_async 词序反了致 404）。 */
	private static final String CREATE_PATH = "/v1/t2a_async_v2";
	private static final String POLL_PATH = "/v1/query/t2a_async_query_v2";
	private static final String RETRIEVE_PATH = "/v1/files/retrieve";

	private final VideoProviderEndpoint endpoint;
	private final ObjectMapper mapper = new ObjectMapper();

	public MinimaxTtsProvider(VideoProviderEndpoint endpoint) {
		this.endpoint = endpoint;
	}

	@Override
	public String id() {
		return "minimax";
	}

	@Override
	public Mono<TtsResult> submit(TtsCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("model", command.model());
		payload.put("text", command.text());
		payload.put("voice_setting", Map.of("voice_id",
				command.voice() == null || command.voice().isBlank() ? DEFAULT_VOICE : command.voice()));
		return client().post().uri(endpoint.createPath()).bodyValue(payload).retrieve().bodyToMono(String.class)
				.timeout(endpoint.requestTimeout()).map(this::readJson).map(node -> {
					String taskId = VideoProviderJson.text(node, "/task_id", "/data/task_id", "/id");
					if (taskId == null) {
						// 同步直回音频（t2a_v2 形态）：file_id 立即可取
						String fileId = VideoProviderJson.text(node, "/data/audio/file_id", "/audio/file_id",
								"/file_id");
						if (fileId == null) {
							throw new IllegalStateException("MiniMax TTS 创建任务响应缺少 task_id/file_id");
						}
						return retrieve(fileId).map(url -> new TtsResult(TtsResult.State.SUCCEEDED, taskIdOf(fileId),
								url, null, null, null));
					}
					return Mono.just(new TtsResult(TtsResult.State.QUEUED, taskId, null, null, null, null));
				}).flatMap(mono -> mono);
	}

	@Override
	public Mono<TtsResult> poll(String providerTaskId) {
		return client().get()
				.uri(builder -> builder.path(endpoint.pollPath()).queryParam("task_id", providerTaskId).build())
				.retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout()).map(this::readJson)
				.flatMap(node -> mapStatus(node, providerTaskId));
	}

	private Mono<TtsResult> mapStatus(JsonNode node, String taskId) {
		String raw = VideoProviderJson.text(node, "/status", "/data/status");
		String status = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
		if (List.of("success", "succeeded", "completed").contains(status)) {
			String direct = VideoProviderJson.text(node, "/data/audio/url", "/audio/url", "/file/download_url",
					"/download_url");
			if (direct != null) {
				return Mono.just(succeeded(taskId, direct));
			}
			String fileId = VideoProviderJson.text(node, "/data/audio/file_id", "/audio/file_id", "/file_id",
					"/data/file_id");
			if (fileId == null) {
				return Mono.just(failed(taskId, "missing_file", "MiniMax TTS 成功响应缺少音频 file_id"));
			}
			return retrieve(fileId).map(url -> succeeded(taskId, url));
		}
		if (List.of("fail", "failed", "error", "cancelled", "canceled", "expired").contains(status)) {
			return Mono.just(
					failed(taskId, VideoProviderJson.text(node, "/base_resp/status_code", "/error/code", "/error_code"),
							VideoProviderJson.text(node, "/base_resp/status_msg", "/error/message", "/message")));
		}
		boolean queued = List.of("queueing", "queued", "pending").contains(status);
		return Mono.just(new TtsResult(queued ? TtsResult.State.QUEUED : TtsResult.State.PROCESSING, taskId, null, null,
				null, null));
	}

	private Mono<String> retrieve(String fileId) {
		return client().get()
				.uri(builder -> builder.path(endpoint.retrievePath()).queryParam("file_id", fileId).build()).retrieve()
				.bodyToMono(String.class).timeout(endpoint.requestTimeout()).map(this::readJson).map(node -> {
					String url = VideoProviderJson.text(node, "/file/download_url", "/download_url",
							"/data/download_url");
					if (url == null) {
						throw new IllegalStateException("MiniMax TTS 文件响应缺少 download_url");
					}
					return url;
				});
	}

	private static TtsResult succeeded(String taskId, String url) {
		return new TtsResult(TtsResult.State.SUCCEEDED, taskId, url, null, null, null);
	}

	private static TtsResult failed(String taskId, String code, String message) {
		return new TtsResult(TtsResult.State.FAILED, taskId, null, null, code == null ? "provider_failed" : code,
				message == null ? "MiniMax TTS 合成失败" : message);
	}

	/** 同步直回时 providerTaskId 用 file_id 承载（后续 poll 不会发生）。 */
	private static String taskIdOf(String fileId) {
		return "file:" + fileId;
	}

	private WebClient client() {
		return ManagedWebClientFactory.builder(MinimaxTtsProvider.class, endpoint.requestTimeout())
				.baseUrl(endpoint.baseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey())
				.build();
	}

	private JsonNode readJson(String body) {
		try {
			return mapper.readTree(body);
		} catch (Exception error) {
			throw new IllegalStateException("MiniMax TTS 响应 JSON 无效", error);
		}
	}
}
