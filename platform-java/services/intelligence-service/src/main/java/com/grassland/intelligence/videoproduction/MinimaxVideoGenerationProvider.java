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
 * MiniMax/Hailuo 异步视频生成协议适配器。
 *
 * <p>任务书 #64 卡2 起按控制面解析构造（非 Spring bean）：连接参数全部来自
 * {@link VideoProviderEndpoint}，model id 由 {@link ProviderCommand} 携带。
 */
public class MinimaxVideoGenerationProvider implements VideoGenerationProvider {

    private final VideoProviderEndpoint endpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    public MinimaxVideoGenerationProvider(VideoProviderEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public String id() {
        return "minimax";
    }

    @Override
    public Mono<ProviderResult> submit(ProviderCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("prompt", command.prompt());
        payload.put("duration", command.durationSeconds());
        payload.put("aspect_ratio", command.aspectRatio());
        payload.put("prompt_optimizer", true);
        if (!command.images().isEmpty()) {
            payload.put("first_frame_image", VideoProviderJson.dataImage(command.images().getFirst()));
        }
        return client().post().uri(endpoint.createPath()).bodyValue(payload)
                .retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout())
                .map(this::readJson)
                .map(node -> {
                    String taskId = VideoProviderJson.text(node, "/task_id", "/data/task_id", "/id");
                    if (taskId == null) {
                        throw new IllegalStateException("MiniMax 创建任务响应缺少 task_id");
                    }
                    return new ProviderResult(ProviderResult.State.QUEUED, taskId, 5, null,
                            command.durationSeconds(), null, null);
                });
    }

    @Override
    public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
        return client().get().uri(builder -> builder.path(endpoint.pollPath())
                        .queryParam("task_id", providerTaskId).build())
                .retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout())
                .map(this::readJson)
                .flatMap(node -> mapStatus(node, providerTaskId, requestedDurationSeconds));
    }

    private Mono<ProviderResult> mapStatus(JsonNode node, String taskId, int duration) {
        String raw = VideoProviderJson.text(node, "/status", "/data/status");
        String status = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (List.of("success", "succeeded", "completed").contains(status)) {
            String directUrl = VideoProviderJson.text(node, "/file/download_url", "/download_url",
                    "/video_url", "/data/download_url");
            if (directUrl != null) {
                return Mono.just(success(taskId, directUrl, duration));
            }
            String fileId = VideoProviderJson.text(node, "/file_id", "/data/file_id");
            if (fileId == null) {
                return Mono.just(failed(taskId, duration, "missing_file", "MiniMax 成功响应缺少 file_id"));
            }
            return retrieve(fileId).map(url -> success(taskId, url, duration));
        }
        if (List.of("fail", "failed", "error", "cancelled", "canceled").contains(status)) {
            return Mono.just(failed(taskId, duration,
                    VideoProviderJson.text(node, "/base_resp/status_code", "/error/code", "/error_code"),
                    VideoProviderJson.text(node, "/base_resp/status_msg", "/error/message", "/message")));
        }
        boolean queued = List.of("queueing", "queued", "pending").contains(status);
        return Mono.just(new ProviderResult(
                queued ? ProviderResult.State.QUEUED : ProviderResult.State.PROCESSING,
                taskId, VideoProviderJson.progress(node, queued ? 10 : 50), null,
                duration, null, null));
    }

    private Mono<String> retrieve(String fileId) {
        return client().get().uri(builder -> builder.path(endpoint.retrievePath())
                        .queryParam("file_id", fileId).build())
                .retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout())
                .map(this::readJson)
                .map(node -> {
                    String url = VideoProviderJson.text(node, "/file/download_url", "/download_url",
                            "/data/download_url");
                    if (url == null) {
                        throw new IllegalStateException("MiniMax 文件响应缺少 download_url");
                    }
                    return url;
                });
    }

    private static ProviderResult success(String taskId, String url, int duration) {
        return new ProviderResult(ProviderResult.State.SUCCEEDED, taskId, 100, url,
                duration, null, null);
    }

    private static ProviderResult failed(String taskId, int duration, String code, String message) {
        return new ProviderResult(ProviderResult.State.FAILED, taskId, 100, null, duration,
                code == null ? "provider_failed" : code,
                message == null ? "MiniMax 视频生成失败" : message);
    }

    private WebClient client() {
        return ManagedWebClientFactory.builder(
                        MinimaxVideoGenerationProvider.class, endpoint.requestTimeout())
                .baseUrl(endpoint.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey())
                .build();
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("MiniMax 响应 JSON 无效", error);
        }
    }
}
