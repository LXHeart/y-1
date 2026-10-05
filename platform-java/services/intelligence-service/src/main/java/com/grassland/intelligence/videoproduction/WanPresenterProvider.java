package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Audio-driven presenter protocol. Intentionally separate from ordinary prompt-to-video generation. */
public final class WanPresenterProvider {
    private final VideoProviderEndpoint endpoint;
    private final ObjectMapper json = new ObjectMapper();

    public WanPresenterProvider(VideoProviderEndpoint endpoint) { this.endpoint = endpoint; }

    /** URLs are ephemeral server-issued media links, never persisted in the draft. */
    public record Command(String imageUrl, String audioUrl, int audioDurationMs, String resolution) {}

    public Mono<VideoGenerationProvider.ProviderResult> submit(Command command) {
        return Mono.defer(() -> {
            if (command == null || command.audioDurationMs() <= 0 || command.audioDurationMs() >= 20_000)
                return Mono.error(new IllegalArgumentException("主播音频每段必须短于 20 秒"));
            if (!publicUrl(command.imageUrl()) || !publicUrl(command.audioUrl()))
                return Mono.error(new IllegalArgumentException("主播图片和音频需要可公开访问的媒体地址"));
            if (!"480P".equals(command.resolution()) && !"720P".equals(command.resolution()))
                return Mono.error(new IllegalArgumentException("主播分辨率仅支持 480P 或 720P"));
            Map<String, Object> body = Map.of("model", "wan2.2-s2v",
                    "input", Map.of("image_url", command.imageUrl(), "audio_url", command.audioUrl()),
                    "parameters", Map.of("resolution", command.resolution()));
            return client().post().uri("/api/v1/services/aigc/image2video/video-synthesis")
                    .header("X-DashScope-Async", "enable").bodyValue(body).retrieve()
                    .bodyToMono(String.class).timeout(endpoint.requestTimeout()).map(this::parse)
                    .map(node -> {
                        String id = VideoProviderJson.text(node, "/output/task_id");
                        if (id == null || id.isBlank()) throw new IllegalStateException("主播生成响应缺少任务编号");
                        return result(VideoGenerationProvider.ProviderResult.State.QUEUED, id, null, null, null);
                    });
        });
    }

    public Mono<VideoGenerationProvider.ProviderResult> poll(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,200}"))
            return Mono.error(new IllegalArgumentException("无效的主播生成任务编号"));
        return client().get().uri("/api/v1/tasks/{id}", taskId).retrieve().bodyToMono(String.class)
                .timeout(endpoint.requestTimeout()).map(this::parse).map(node -> {
                    String status = node.path("output").path("task_status").asText();
                    return switch (status) {
                        case "SUCCEEDED" -> {
                            String url = VideoProviderJson.text(node, "/output/results/video_url");
                            yield publicUrl(url)
                                    ? result(VideoGenerationProvider.ProviderResult.State.SUCCEEDED, taskId, url, null, null)
                                    : result(VideoGenerationProvider.ProviderResult.State.FAILED, taskId, null, "missing_result", "主播任务未返回有效视频");
                        }
                        case "FAILED", "CANCELED" -> result(VideoGenerationProvider.ProviderResult.State.FAILED,
                                taskId, null, "presenter_generation_failed", "主播视频生成失败");
                        case "PENDING" -> result(VideoGenerationProvider.ProviderResult.State.QUEUED, taskId, null, null, null);
                        case "RUNNING" -> result(VideoGenerationProvider.ProviderResult.State.PROCESSING, taskId, null, null, null);
                        default -> result(VideoGenerationProvider.ProviderResult.State.UNKNOWN, taskId, null, "unknown_state", "主播任务状态暂不明确");
                    };
                });
    }

    private static VideoGenerationProvider.ProviderResult result(VideoGenerationProvider.ProviderResult.State state,
            String id, String url, String code, String message) {
        // Duration is measured from the archived output. Do not invent progress or duration.
        return new VideoGenerationProvider.ProviderResult(state, id, null, url, null, code, message);
    }

    private static boolean publicUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && host != null
                    && uri.getUserInfo() == null && !host.equalsIgnoreCase("localhost") && !host.endsWith(".local")
                    && !host.equals("0.0.0.0") && !host.startsWith("127.") && !host.equals("[::1]");
        } catch (RuntimeException invalid) { return false; }
    }

    private WebClient client() {
        return ManagedWebClientFactory.builder(WanPresenterProvider.class, endpoint.requestTimeout())
                .baseUrl(endpoint.baseUrl()).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.apiKey()).build();
    }

    private JsonNode parse(String body) {
        try { return json.readTree(body); }
        catch (Exception invalid) { throw new IllegalStateException("主播服务返回无效响应"); }
    }
}
