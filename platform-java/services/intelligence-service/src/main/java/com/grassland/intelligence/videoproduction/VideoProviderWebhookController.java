package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Public provider callback; all mutations are gated by signature, inbox and task ownership. */
@RestController
public class VideoProviderWebhookController {
    private static final int MAX_WEBHOOK_BYTES = 1_048_576;
    private final ObjectMapper mapper = new ObjectMapper();
    private final VideoProviderWebhookVerifier verifier;
    private final VideoProviderWebhookRepository inbox;
    private final VideoGenerationWorker worker;

    public VideoProviderWebhookController(VideoProviderWebhookVerifier verifier,
                                          VideoProviderWebhookRepository inbox,
                                          VideoGenerationWorker worker) {
        this.verifier = verifier;
        this.inbox = inbox;
        this.worker = worker;
    }

    @PostMapping("/api/video-production/webhooks/{provider}")
    public Mono<ResponseEntity<Map<String, Object>>> receive(
            @PathVariable String provider, ServerWebExchange exchange) {
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        String eventId = exchange.getRequest().getHeaders().getFirst("X-Video-Event-Id");
        String timestamp = exchange.getRequest().getHeaders().getFirst("X-Video-Timestamp");
        String signature = exchange.getRequest().getHeaders().getFirst("X-Video-Signature");
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_WEBHOOK_BYTES).flatMap(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            DataBufferUtils.release(buffer);
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            var verified = verifier.verify(normalized, eventId, timestamp, signature, raw,
                    java.time.Instant.now().getEpochSecond());
            try {
                JsonNode node = mapper.readTree(raw);
                return handle(normalized, verified.eventId(), node);
            } catch (Exception error) {
                return Mono.error(new IllegalArgumentException("视频 provider 回调 JSON 无效", error));
            }
        }).thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true)))
                .onErrorMap(error -> error instanceof IllegalArgumentException
                        ? error : new IllegalArgumentException("视频 provider 回调无效", error));
    }

    private Mono<Void> handle(String provider, String eventId, JsonNode node) {
        String taskId = VideoProviderJson.text(node, "/task_id", "/id", "/data/task_id", "/data/id");
        if (taskId == null) return Mono.error(new IllegalArgumentException("回调缺少 provider task id"));
        return inbox.findJob(provider, taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("回调任务不存在")))
                .flatMap(job -> inbox.claim(provider, eventId).flatMap(first -> {
                    if (!first) return Mono.empty();
                    return worker.processWebhook(job, result(node, taskId))
                            .onErrorResume(error -> inbox.release(provider, eventId).then(Mono.error(error)));
                }));
    }

    private static VideoGenerationProvider.ProviderResult result(JsonNode node, String taskId) {
        String status = VideoProviderJson.text(node, "/status", "/data/status");
        String normalized = status == null ? "processing" : status.toLowerCase(Locale.ROOT);
        if (normalized.equals("success") || normalized.equals("succeeded") || normalized.equals("completed")) {
            String resultUrl = VideoProviderJson.text(node,
                    "/video_url", "/download_url", "/file/download_url",
                    "/content/video_url", "/data/video_url", "/data/download_url",
                    "/data/file/download_url", "/data/content/video_url");
            if (resultUrl == null) {
                return new VideoGenerationProvider.ProviderResult(
                        VideoGenerationProvider.ProviderResult.State.UNKNOWN, taskId, 100, null,
                        duration(node), "provider_result_pending",
                        "provider 成功回调尚未提供可下载视频地址");
            }
            return new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.SUCCEEDED, taskId, 100,
                    resultUrl, duration(node), null, null);
        }
        if (normalized.equals("failed") || normalized.equals("error")
                || normalized.equals("cancelled") || normalized.equals("canceled")) {
            return new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.FAILED, taskId, 100, null,
                    duration(node), VideoProviderJson.text(node,
                            "/error_code", "/error/code", "/data/error_code", "/data/error/code",
                            "/base_resp/status_code", "/data/base_resp/status_code"),
                    VideoProviderJson.text(node,
                            "/error_message", "/error/message", "/data/error_message",
                            "/data/error/message", "/base_resp/status_msg",
                            "/data/base_resp/status_msg", "/message"));
        }
        if (normalized.equals("queued") || normalized.equals("pending")
                || normalized.equals("submitted")) {
            return new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.QUEUED, taskId,
                    VideoProviderJson.progress(node, 0), null, duration(node), null, null);
        }
        if (normalized.equals("processing") || normalized.equals("running")
                || normalized.equals("in_progress")) {
            return new VideoGenerationProvider.ProviderResult(
                    VideoGenerationProvider.ProviderResult.State.PROCESSING, taskId,
                    VideoProviderJson.progress(node, 0), null, duration(node), null, null);
        }
        return new VideoGenerationProvider.ProviderResult(
                VideoGenerationProvider.ProviderResult.State.UNKNOWN, taskId,
                VideoProviderJson.progress(node, 0), null, duration(node),
                "provider_unknown_status", status);
    }

    private static Integer duration(JsonNode node) {
        return VideoProviderJson.integer(node, "/duration", "/data/duration");
    }
}
