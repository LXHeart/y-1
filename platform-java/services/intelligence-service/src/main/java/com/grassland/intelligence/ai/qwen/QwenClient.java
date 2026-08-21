package com.grassland.intelligence.ai.qwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.MultimodalResult;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Qwen（OpenAI 兼容）流式 chat client——草场 intelligence Slice 1 唯一 {@link AiCapabilityAdapter} 实现。
 *
 * <p>合并 legacy 两份重复的 SSE 读取实现（{@code qwen-provider.ts:1167 requestQwenTextChatStream} 与
 * {@code video-production.service.ts:143 requestTextChatStream}）为单一实现：
 * POST {@code /chat/completions}（{@code stream:true, enable_thinking:false}）→ 按 {@code \n} 分行
 * → 剥 {@code data: } 前缀 → 遇 {@code [DONE]} 终止 → {@code choices[0].delta.content} 映射为 {@link ChatChunk}。
 *
 * <p>{@code bodyToFlux(String.class)} 依赖 reactor-netty 的 StringDecoder 按 {@code \n} 分行（与 legacy
 * 的 {@code reader.read()} + {@code buffer.split('\n')} 等价）。malformed 行吞掉（与 legacy 一致）。
 */
@Component
public class QwenClient implements AiCapabilityAdapter {

    private final PlatformModelConfig config;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public QwenClient(PlatformModelConfig config, com.grassland.intelligence.ai.DnsPinningResolver dnsPinning) {
        this.config = config;
        // GL-P3-AI-001 尾巴：平台默认 Qwen 通道同样固定连接地址——创建时解析一次（env 固定表
        // 优先），连接期不再走系统 DNS，与 BYOK/平台 provider 执行路径同口径。
        this.webClient = com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory.pinnedPlatformClient(
                QwenClient.class, config.baseUrl(), dnsPinning,
                config.readTimeout(), 256 * 1024);
    }

    @Override
    public Flux<ChatChunk> startTextRun(TextRunCommand command) {
        String endpoint = stripTrailingSlash(config.baseUrl()) + "/chat/completions";
        return webClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + config.apiKey())
                .bodyValue(buildBody(command))
                .retrieve()
                .onStatus(s -> s.is4xxClientError(),
                        r -> Mono.error(new IntelligenceException(400, "AI 上游拒绝请求（请检查平台模型配置）")))
                .onStatus(s -> s.is5xxServerError(),
                        r -> Mono.error(new IntelligenceException(502, "AI 上游暂不可用")))
                .bodyToFlux(String.class)
                .map(String::trim)
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring("data: ".length()).trim())
                .takeWhile(line -> !"[DONE]".equals(line))
                .mapNotNull(this::extractContent);
    }

    @Override
    public Mono<String> completeText(TextCompletionCommand command) {
        return requestCompletion(command.messages(), command.timeout(), command.failureMessage(), "请求超时，请稍后重试")
                .map(MultimodalResult::content);
    }

    /** 非流式多模态完成（草场 Slice 10 视频改编）：复用 OpenAI 兼容 /chat/completions，自定义超时。 */
    public Mono<String> completeMultimodal(List<ContentPart> parts, Duration timeout) {
        return requestCompletion(List.of(ChatMessage.user(parts)), timeout,
                "视频内容改编失败，请稍后重试", "视频内容改编超时，请稍后重试")
                .map(MultimodalResult::content);
    }

    /**
     * 非流式多模态完成（草场 Slice 13 Stage 5 Bilibili 视频分析）：返回内容 + 上游 run id。
     * 复用 {@link #requestCompletion}，后者解析 {@code choices[0].message.content} 与顶层 {@code id}。
     */
    public Mono<MultimodalResult> completeMultimodalMeta(List<ContentPart> parts, Duration timeout) {
        return requestCompletion(List.of(ChatMessage.user(parts)), timeout,
                "视频内容提取失败，请稍后重试", "视频内容提取超时，请稍后重试");
    }

    private Mono<MultimodalResult> requestCompletion(List<ChatMessage> messages, Duration timeout,
                                                      String failureMessage, String timeoutMessage) {
        String endpoint = stripTrailingSlash(config.baseUrl()) + "/chat/completions";
        return webClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + config.apiKey())
                .bodyValue(buildCompletionBody(messages))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return response.bodyToMono(String.class).map(this::extractCompletionResult);
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(ignored -> Mono.error(completionError(status, failureMessage)));
                })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class,
                        error -> new IntelligenceException(504, timeoutMessage))
                .onErrorMap(error -> {
                    if (error instanceof IntelligenceException) {
                        return error;
                    }
                    return new IntelligenceException(502, failureMessage);
                });
    }

    private Map<String, Object> buildBody(TextRunCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", command.messages().stream().map(QwenClient::toMessageMap).toList());
        body.put("stream", true);
        body.put("enable_thinking", false);
        return body;
    }

    private Map<String, Object> buildCompletionBody(List<ChatMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", messages.stream().map(QwenClient::toMessageMap).toList());
        body.put("stream", false);
        body.put("enable_thinking", false);
        return body;
    }

    /** content 多模态时序列化为 text/image_url 片断数组，否则明文字符串（与 legacy OpenAI 兼容格式一致）。 */
    private static Map<String, Object> toMessageMap(ChatMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", message.role());
        if (message.multimodal()) {
            result.put("content", message.parts().stream().map(QwenClient::toPartMap).toList());
        } else {
            result.put("content", message.content());
        }
        return result;
    }

    private static Map<String, Object> toPartMap(ContentPart part) {
        return switch (part) {
            case ContentPart.Text text -> Map.of("type", "text", "text", text.text());
            case ContentPart.Image image -> Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", image.url()));
            case ContentPart.Video video -> Map.of(
                    "type", "video_url",
                    "video_url", Map.of("url", video.url()));
        };
    }

    private ChatChunk extractContent(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                return new ChatChunk(content.asText());
            }
            return null;
        } catch (Exception e) {
            return null;   // malformed SSE 行吞掉（与 legacy catch-then-skip 一致）
        }
    }

    private MultimodalResult extractCompletionResult(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IntelligenceException(502, "AI 上游返回了空内容");
            }
            String runId = root.path("id").asText(null);
            return new MultimodalResult(
                    content, (runId == null || runId.isBlank()) ? null : runId,
                    "qwen", config.model());
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw new IntelligenceException(502, "AI 上游返回了无效响应");
        }
    }

    private static IntelligenceException completionError(int status, String failureMessage) {
        if (status == 402) {
            return new IntelligenceException(400, "图片生成服务配额不足，请联系管理员充值");
        }
        if (status == 429) {
            return new IntelligenceException(400, "图片生成请求过于频繁，请稍后重试");
        }
        return new IntelligenceException(status >= 500 ? 502 : 400, failureMessage);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
