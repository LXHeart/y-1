package com.grassland.intelligence.ai.qwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.security.IntelligenceException;
import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

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

    public QwenClient(PlatformModelConfig config) {
        this.config = config;
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(config.connectTimeout().toMillis()))
                .responseTimeout(config.readTimeout());
        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024))
                .build();
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
        String endpoint = stripTrailingSlash(config.baseUrl()) + "/chat/completions";
        return webClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + config.apiKey())
                .bodyValue(buildCompletionBody(command))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return response.bodyToMono(String.class).map(this::extractMessageContent);
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(ignored -> Mono.error(completionError(status, command.failureMessage())));
                })
                .timeout(command.timeout())
                .onErrorMap(error -> {
                    if (error instanceof IntelligenceException) {
                        return error;
                    }
                    return new IntelligenceException(502, command.failureMessage());
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

    private Map<String, Object> buildCompletionBody(TextCompletionCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("messages", command.messages().stream().map(QwenClient::toMessageMap).toList());
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

    private String extractMessageContent(String json) {
        try {
            String content = mapper.readTree(json)
                    .path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IntelligenceException(502, "AI 上游返回了空内容");
            }
            return content;
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
