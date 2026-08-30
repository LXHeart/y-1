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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImageGenerationClient.class);

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

    /**
     * 任务书 #56：按解析结果调用图像端点；任务书 #58 决策 G 起端点必填（BYOK 或平台凭据解析），
     * 静态 env 端点已删——null 一律 503，绝不静默回落。
     */
    public Mono<GeneratedImage> generate(String prompt, String size, Endpoint endpoint) {
        if (endpoint == null) {
            return Mono.error(new IntelligenceException(503, "平台凭据缺失：图片生成端点未配置"));
        }
        String baseUrl = endpoint.baseUrl();
        String apiKey = endpoint.apiKey();
        String model = endpoint.model();
        if (isBlank(baseUrl) || isBlank(apiKey) || isBlank(model)) {
            return Mono.error(new IntelligenceException(400,
                    "图像密钥配置不完整：base URL、密钥与模型均必填"));
        }
        // MiniMax 方言：端点为 POST /image_generation，返回 data[].image_url（URL 而非 b64）——
        // 实测定于 2026-08-30（api.minimaxi.com：/images/generations 404）
        if (endpoint != null && endpoint.minimaxDialect()) {
            return generateMinimax(prompt, baseUrl, apiKey, model);
        }
        return webClient.post()
                .uri(stripTrailingSlash(baseUrl) + "/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body(prompt, size, model))
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

    /** 一次图像调用的目标端点（BYOK 或平台凭据解析结果；决策 G：无 null 语义）。 */
    public record Endpoint(String baseUrl, String apiKey, String model, String provider) {

        public static Endpoint of(
                com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution provider,
                String decryptedKey) {
            return new Endpoint(provider.baseUrl(), decryptedKey, provider.model(), provider.provider());
        }

        /** MiniMax 方言判定（provider 或 baseUrl 任一命中；MiniMax 无 /images/generations）。 */
        boolean minimaxDialect() {
            if (provider != null && provider.toLowerCase().contains("minimax")) {
                return true;
            }
            return baseUrl != null && baseUrl.toLowerCase().contains("minimax");
        }
    }

    /** MiniMax 方言：POST {base}/image_generation → data[0].image_url 下载字节转 b64（兼容 b64_json）。 */
    private Mono<GeneratedImage> generateMinimax(String prompt, String baseUrl, String apiKey, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        // 官方契约：response_format=url|base64；url 24h 过期——直取 base64 免下载
        body.put("response_format", "base64");
        return webClient.post()
                .uri(stripTrailingSlash(baseUrl) + "/image_generation")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return response.bodyToMono(String.class).flatMap(this::minimaxImage);
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

    /** MiniMax 结果解析：b64_json 直取；image_url 走响应式下载转 b64（reactor 线程内禁 block）。 */
    private Mono<GeneratedImage> minimaxImage(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception error) {
            return Mono.error(invalidResult());
        }
        JsonNode base = root.path("base_resp");
        if (base.has("status_code") && base.get("status_code").asInt(0) != 0) {
            String message = base.path("status_msg").asText("图片生成服务返回错误");
            return Mono.error(new IntelligenceException(502, "图片生成失败：" + message));
        }
        // 官方契约 data 为对象（image_base64/image_url）；data 与 image_base64 的数组形态均留兼容
        // （实测 2026-08-30：image-01 的 image_base64 返回单元素数组）
        JsonNode data = root.path("data");
        JsonNode first = data.isArray() ? data.path(0) : data;
        JsonNode b64Node = first.path("image_base64");
        if (b64Node.isArray()) {
            b64Node = b64Node.path(0);
        }
        String b64 = validBase64(b64Node.asText(null));
        if (b64 == null) {
            JsonNode jsonNode = first.path("b64_json");
            if (jsonNode.isArray()) {
                jsonNode = jsonNode.path(0);
            }
            b64 = validBase64(jsonNode.asText(null));
        }
        if (b64 != null) {
            return Mono.just(new GeneratedImage(null, b64, null));
        }
        String imageUrl = nonBlank(first.path("image_url").asText(null));
        if (imageUrl == null) {
            log.warn("MiniMax image response has no parseable image payload: {}",
                    json.length() > 600 ? json.substring(0, 600) + "…" : json);
            return Mono.error(invalidResult());
        }
        return webClient.get().uri(imageUrl)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(java.time.Duration.ofSeconds(60))
                .map(bytes -> (GeneratedImage) new GeneratedImage(
                        null, Base64.getEncoder().encodeToString(bytes), null))
                .onErrorMap(error -> error instanceof IntelligenceException
                        ? error : invalidResult());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> body(String prompt, String size, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
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
