package com.grassland.intelligence.ai.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ProviderUrlGuard;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容 text completion 客户端（GL-P3-AI-001 控制面执行链路）。
 *
 * <p>平台 run 与 BYOK run 共用：平台传 env 平台 api-key 作 bearer，BYOK 传解密后的明文 key 作 bearer。
 * 解析 {@code choices[0].message.content} + {@code usage.prompt_tokens/completion_tokens} 供结算计量。
 *
 * <p>SSRF 第一道闸：调用前经 {@link ProviderUrlGuard}（字面私有 IP 拒绝、域名视为可信）。
 * BYOK pinned-DNS 全貌留后续 SSRF 硬化 slice（卡 D-11，{@code DnsPinningResolver} 已就位但未接出站 HTTP）。
 */
@Component
public class TextCompletionClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;

    public TextCompletionClient(@Value("${ai.qwen.read-timeout-ms:120000}") long readTimeoutMs) {
        this.timeout = Duration.ofMillis(readTimeoutMs);
    }

    public Mono<TextCompletionResult> complete(String baseUrl, String bearer, String model, String prompt, int maxTokens) {
        ProviderUrlGuard.validate(baseUrl);  // SSRF 第一道闸（非 2xx/私有 IP 字面量 → IllegalArgumentException → 400/502）
        WebClient client = WebClient.builder().baseUrl(stripTrailingSlash(baseUrl)).build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("enable_thinking", false);

        return client.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + bearer)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return response.bodyToMono(String.class).map(this::parse);
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(ignored -> Mono.error(new IntelligenceException(502, "AI provider 调用失败")));
                })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"))
                .onErrorMap(e -> e instanceof IntelligenceException ? e : new IntelligenceException(502, "AI provider 调用失败"));
    }

    private TextCompletionResult parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.path("choices");
            String content = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText("")
                    : "";
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);
            return new TextCompletionResult(content, inputTokens, outputTokens);
        } catch (Exception e) {
            throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
