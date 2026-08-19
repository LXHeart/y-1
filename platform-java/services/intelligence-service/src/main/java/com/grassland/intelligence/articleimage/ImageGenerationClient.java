package com.grassland.intelligence.articleimage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.http.ManagedWebClientFactory;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** OpenAI-compatible {@code /images/generations} client。 */
@Component
public class ImageGenerationClient {

    private final ImageGenerationConfig config;
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ImageGenerationClient(ImageGenerationConfig config) {
        this.config = config;
        this.webClient = ManagedWebClientFactory.builder(
                        ImageGenerationClient.class,
                        config.connectTimeout(), config.readTimeout(), 16 * 1024 * 1024)
                .build();
    }

    public Mono<GeneratedImage> generate(String prompt, String size) {
        return webClient.post()
                .uri(stripTrailingSlash(config.baseUrl()) + "/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + config.apiKey())
                .bodyValue(body(prompt, size))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return response.bodyToMono(String.class).map(this::parseResult);
                    }
                    return response.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(ignored -> Mono.error(providerError(status)));
                })
                .onErrorMap(error -> {
                    if (error instanceof IntelligenceException) {
                        return error;
                    }
                    if (isTimeout(error)) {
                        return new IntelligenceException(504, "图片生成失败，请稍后重试");
                    }
                    return new IntelligenceException(502, "图片生成失败，请稍后重试");
                });
    }

    private Map<String, Object> body(String prompt, String size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model());
        body.put("prompt", prompt);
        body.put("n", 1);
        body.put("size", size);
        body.put("response_format", "b64_json");
        return body;
    }

    private GeneratedImage parseResult(String json) {
        try {
            JsonNode first = mapper.readTree(json).path("data").path(0);
            String base64 = validBase64(first.path("b64_json").asText(null));
            if (base64 == null) {
                throw invalidResult();
            }
            return new GeneratedImage(
                    null,
                    base64,
                    nonBlank(first.path("revised_prompt").asText(null)));
        } catch (IntelligenceException error) {
            throw error;
        } catch (Exception error) {
            throw invalidResult();
        }
    }

    private static IntelligenceException providerError(int status) {
        if (status == 402) {
            return new IntelligenceException(400, "图片生成服务配额不足，请联系管理员充值");
        }
        if (status == 429) {
            return new IntelligenceException(400, "图片生成请求过于频繁，请稍后重试");
        }
        if (status >= 500) {
            return new IntelligenceException(502, "图片生成服务暂时不可用，请稍后重试");
        }
        return new IntelligenceException(400, "图片生成失败，请稍后重试");
    }

    private static IntelligenceException invalidResult() {
        return new IntelligenceException(502, "图片生成服务返回了无效图片数据");
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TimeoutException
                    || current instanceof io.netty.handler.timeout.ReadTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String validBase64(String raw) {
        String value = nonBlank(raw);
        if (value == null) {
            return null;
        }
        try {
            Base64.getDecoder().decode(value);
            return value;
        } catch (IllegalArgumentException error) {
            throw invalidResult();
        }
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
