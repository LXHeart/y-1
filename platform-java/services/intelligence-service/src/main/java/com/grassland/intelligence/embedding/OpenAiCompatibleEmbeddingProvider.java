package com.grassland.intelligence.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory;
import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** OpenAI-compatible JSON embedding adapter. */
@Component
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final String PROVIDER = "openai-compatible";

    private final ObjectMapper mapper = new ObjectMapper();
    private final EmbeddingProviderProperties properties;
    private final OpenAiCompatibleHttpClientFactory clients;

    public OpenAiCompatibleEmbeddingProvider(
            EmbeddingProviderProperties properties,
            OpenAiCompatibleHttpClientFactory clients) {
        this.properties = properties;
        this.clients = clients;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * 别名只收「底座就是 OpenAI 形状」的 provider 名——向量走 {@code embeddings}，与文本方言无关。
     *
     * <p>{@code openai-completions}/{@code openai-responses} 指向同一个 OpenAI 底座，该端点真实存在。
     *
     * <p><b>故意不收</b> {@code anthropic-messages}（Anthropic 无 embeddings 端点）与
     * {@code google-generative-ai}（Gemini 向量线形状不同，请求/响应体都对不上）：必须 fail-closed 503。
     * 这条路径上 503 会被降级成 {@code semantic.status=fallback}（HTTP 200、纯规则排序），
     * 静默丢检索质量已经够糟，再让它拿错形状的体去打一个不存在的路由只会更糟。
     *
     * <p>{@code qwen} 保留：BYOK provider 是自由串，V57 只迁平台表，存量 BYOK 行仍可能写着 qwen。
     */
    @Override
    public Set<String> aliases() {
        return Set.of("qwen", "openai-completions", "openai-responses");
    }

    @Override
    public String algorithmVersion() {
        // 无 command 上下文（默认链兜底路径）：模型不可知，用占位——真实路径都在 algorithmVersion(Command)
        return algorithmVersionFor("unresolved");
    }

    @Override
    public String algorithmVersion(Command command) {
        return algorithmVersionFor(command == null || command.invocation() == null
                ? "unresolved" : command.invocation().model());
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    @Override
    public Mono<Result> embed(String normalizedText) {
        return Mono.error(new IllegalArgumentException("真实 Embedding 调用缺少 Provider 路由上下文"));
    }

    @Override
    public Mono<Result> embed(Command command) {
        return Mono.defer(() -> {
                    requireCommand(command);
                    ProviderInvocation invocation = command.invocation();
                    WebClient client = clients.create(
                            OpenAiCompatibleEmbeddingProvider.class,
                            invocation,
                            properties.requestTimeout(),
                            properties.maxResponseBytes());
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", invocation.model());
                    body.put("input", command.normalizedText());
                    body.put("encoding_format", "float");
                    if (properties.sendDimensions()) {
                        body.put("dimensions", properties.dimensions());
                    }
                    return client.post()
                            .uri(relativePath(properties.embeddingsPath()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .headers(headers -> headers.setBearerAuth(invocation.bearer()))
                            .bodyValue(body)
                            .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                                    ? response.bodyToMono(String.class)
                                            .switchIfEmpty(Mono.error(invalidResponse()))
                                            .map(this::parseJson)
                                            .map(this::parse)
                                    : response.releaseBody().then(Mono.error(providerFailure())))
                            .timeout(properties.requestTimeout());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(OpenAiCompatibleEmbeddingProvider::sanitizeError);
    }

    private JsonNode parseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw invalidResponse();
        }
    }

    private Result parse(JsonNode root) {
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray() || data.isEmpty()) {
            throw invalidResponse();
        }
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray()) {
            throw invalidResponse();
        }
        List<Double> vector = new ArrayList<>(embedding.size());
        for (JsonNode value : embedding) {
            if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                throw invalidResponse();
            }
            vector.add(value.doubleValue());
        }
        JsonNode usage = root.get("usage");
        JsonNode promptTokens = usage == null ? null : usage.get("prompt_tokens");
        if (promptTokens == null || !promptTokens.isIntegralNumber()
                || !promptTokens.canConvertToInt() || promptTokens.intValue() < 0) {
            throw invalidResponse();
        }
        return new Result(List.copyOf(vector), promptTokens.intValue(), false);
    }

    private static void requireCommand(Command command) {
        if (command == null || command.normalizedText() == null || command.normalizedText().isBlank()
                || command.invocation() == null) {
            throw new IllegalArgumentException("Embedding 参数不完整");
        }
    }

    private String algorithmVersionFor(String model) {
        String normalized = model == null ? "unknown" : model.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return "openai-compatible-v1:" + normalized + ":" + properties.dimensions();
    }

    private static String relativePath(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static Throwable sanitizeError(Throwable error) {
        if (error instanceof IntelligenceException) {
            return error;
        }
        if (isTimeout(error)) {
            return new IntelligenceException(504, "provider_timeout", "Embedding服务调用超时");
        }
        IntelligenceException sanitized = providerFailure();
        sanitized.initCause(error);
        return sanitized;
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

    private static IntelligenceException providerFailure() {
        return new IntelligenceException(502, "provider_failure", "Embedding服务调用失败");
    }

    private static IntelligenceException invalidResponse() {
        return new IntelligenceException(502, "provider_invalid_response", "Embedding服务返回无效数据");
    }
}
