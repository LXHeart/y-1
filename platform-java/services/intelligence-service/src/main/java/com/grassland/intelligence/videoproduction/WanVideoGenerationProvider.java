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
 * 阿里万相（DashScope 兼容）异步视频生成适配器（任务书 #64 卡6，P7）。
 *
 * <p>协议：POST {@code /api/v1/services/aigc/video-generation/video-synthesis}（Header
 * {@code X-DashScope-Async: enable}，body {@code model/input.prompt/input.img_url}）→
 * {@code output.task_id}；轮询 GET {@code /api/v1/tasks/{taskId}} →
 * {@code output.task_status ∈ PENDING/RUNNING/SUCCEEDED/FAILED}，成功带 {@code output.video_url}。
 * poll-only（不接 webhook），契约形态对齐 MiniMax provider。
 */
public class WanVideoGenerationProvider implements VideoGenerationProvider {

    private static final String ASYNC_HEADER = "X-DashScope-Async";

    private final VideoProviderEndpoint endpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    public WanVideoGenerationProvider(VideoProviderEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public String id() {
        return "wan";
    }

    @Override
    public Mono<ProviderResult> submit(ProviderCommand command) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", command.prompt());
        if (!command.images().isEmpty()) {
            input.put("img_url", VideoProviderJson.dataImage(command.images().getFirst()));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("duration", command.durationSeconds());
        parameters.put("size", sizeFor(command.aspectRatio()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("input", input);
        payload.put("parameters", parameters);
        return client().post().uri(endpoint.createPath())
                .header(ASYNC_HEADER, "enable")
                .bodyValue(payload)
                .retrieve().bodyToMono(String.class).timeout(endpoint.requestTimeout())
                .map(this::readJson)
                .map(node -> {
                    String taskId = VideoProviderJson.text(node, "/output/task_id", "/task_id", "/id");
                    if (taskId == null) {
                        throw new IllegalStateException("万相创建任务响应缺少 task_id");
                    }
                    return new ProviderResult(ProviderResult.State.QUEUED, taskId, 5, null,
                            command.durationSeconds(), null, null);
                });
    }

    @Override
    public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
        String path = endpoint.pollPath().replace("{taskId}", providerTaskId);
        return client().get().uri(path).retrieve().bodyToMono(String.class)
                .timeout(endpoint.requestTimeout())
                .map(this::readJson)
                .map(node -> mapStatus(node, providerTaskId, requestedDurationSeconds));
    }

    private ProviderResult mapStatus(JsonNode node, String taskId, int duration) {
        String raw = VideoProviderJson.text(node, "/output/task_status", "/task_status", "/status");
        String status = raw == null ? "" : raw.toUpperCase(Locale.ROOT);
        if (List.of("SUCCEEDED", "SUCCESS", "COMPLETED").contains(status)) {
            String url = VideoProviderJson.text(node, "/output/video_url", "/video_url",
                    "/output/video_url", "/data/video_url");
            if (url == null) {
                return failed(taskId, duration, "missing_result", "万相成功响应缺少 video_url");
            }
            return new ProviderResult(ProviderResult.State.SUCCEEDED, taskId, 100, url, duration, null, null);
        }
        if (List.of("FAILED", "ERROR", "CANCELLED", "CANCELED").contains(status)) {
            return failed(taskId, duration,
                    VideoProviderJson.text(node, "/output/code", "/code", "/error/code"),
                    VideoProviderJson.text(node, "/output/message", "/message", "/error/message"));
        }
        boolean queued = List.of("PENDING", "QUEUED", "SUBMITTED").contains(status);
        return new ProviderResult(
                queued ? ProviderResult.State.QUEUED : ProviderResult.State.PROCESSING,
                taskId, VideoProviderJson.progress(node, queued ? 10 : 50), null, duration, null, null);
    }

    private static String sizeFor(String aspectRatio) {
        return "16:9".equals(aspectRatio) ? "1280P" : "1080P";
    }

    private static ProviderResult failed(String taskId, int duration, String code, String message) {
        return new ProviderResult(ProviderResult.State.FAILED, taskId, 100, null, duration,
                code == null ? "provider_failed" : code,
                message == null ? "万相视频生成失败" : message);
    }

    private WebClient client() {
        return ManagedWebClientFactory.builder(WanVideoGenerationProvider.class, endpoint.requestTimeout())
                .baseUrl(endpoint.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey())
                .build();
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("万相响应 JSON 无效", error);
        }
    }
}
