package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Volcengine Ark/Seedance async task protocol adapter. */
@Component
public class SeedanceVideoGenerationProvider implements VideoGenerationProvider {

    private final VideoGenerationProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public SeedanceVideoGenerationProvider(VideoGenerationProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return "seedance";
    }

    @Override
    public Mono<ProviderResult> submit(ProviderCommand command) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", command.prompt()));
        if (!command.images().isEmpty()) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("type", "image_url");
            image.put("image_url", Map.of("url", VideoProviderJson.dataImage(command.images().getFirst())));
            image.put("role", "first_frame");
            content.add(image);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", command.model());
        payload.put("content", content);
        payload.put("duration", command.durationSeconds());
        payload.put("ratio", command.aspectRatio());
        return client().post().uri(properties.resolvedCreatePath()).bodyValue(payload)
                .retrieve().bodyToMono(String.class).timeout(properties.getRequestTimeout())
                .map(this::readJson)
                .map(node -> {
                    String taskId = VideoProviderJson.text(node, "/id", "/task_id", "/data/id");
                    if (taskId == null) {
                        throw new IllegalStateException("Seedance 创建任务响应缺少 task id");
                    }
                    return new ProviderResult(
                            ProviderResult.State.QUEUED, taskId, 5, null,
                            command.durationSeconds(), null, null);
                });
    }

    @Override
    public Mono<ProviderResult> poll(String providerTaskId, int requestedDurationSeconds) {
        String path = properties.resolvedPollPath().replace("{taskId}", providerTaskId);
        return client().get().uri(path).retrieve().bodyToMono(String.class)
                .timeout(properties.getRequestTimeout())
                .map(this::readJson)
                .map(node -> mapStatus(node, providerTaskId, requestedDurationSeconds));
    }

    private ProviderResult mapStatus(JsonNode node, String taskId, int duration) {
        String raw = VideoProviderJson.text(node, "/status", "/data/status");
        String status = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (List.of("succeeded", "success", "completed").contains(status)) {
            String url = VideoProviderJson.text(node, "/content/video_url", "/video_url",
                    "/data/video_url", "/data/content/video_url");
            if (url == null) {
                return failed(taskId, duration, "missing_result", "Seedance 成功响应缺少 video_url");
            }
            return new ProviderResult(ProviderResult.State.SUCCEEDED, taskId, 100, url,
                    duration, null, null);
        }
        if (List.of("failed", "error", "cancelled", "canceled").contains(status)) {
            return failed(taskId, duration,
                    VideoProviderJson.text(node, "/error/code", "/error_code", "/data/error/code"),
                    VideoProviderJson.text(node, "/error/message", "/message", "/data/error/message"));
        }
        boolean queued = List.of("queued", "pending", "created").contains(status);
        return new ProviderResult(
                queued ? ProviderResult.State.QUEUED : ProviderResult.State.PROCESSING,
                taskId, VideoProviderJson.progress(node, queued ? 10 : 50), null,
                duration, null, null);
    }

    private static ProviderResult failed(String taskId, int duration, String code, String message) {
        return new ProviderResult(ProviderResult.State.FAILED, taskId, 100, null, duration,
                code == null ? "provider_failed" : code,
                message == null ? "Seedance 视频生成失败" : message);
    }

    private WebClient client() {
        return WebClient.builder().baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception error) {
            throw new IllegalStateException("Seedance 响应 JSON 无效", error);
        }
    }
}
