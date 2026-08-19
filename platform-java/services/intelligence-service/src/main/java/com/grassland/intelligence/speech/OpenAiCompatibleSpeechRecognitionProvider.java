package com.grassland.intelligence.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory;
import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** OpenAI-compatible multipart speech transcription adapter. */
@Component
public final class OpenAiCompatibleSpeechRecognitionProvider implements SpeechRecognitionProvider {

    private static final String PROVIDER = "openai-compatible";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SpeechProviderProperties properties;
    private final OpenAiCompatibleHttpClientFactory clients;

    public OpenAiCompatibleSpeechRecognitionProvider(
            SpeechProviderProperties properties,
            OpenAiCompatibleHttpClientFactory clients) {
        this.properties = properties;
        this.clients = clients;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("qwen");
    }

    @Override
    public Mono<Result> transcribe(Command command) {
        return Mono.defer(() -> {
                    requireCommand(command);
                    ProviderInvocation invocation = command.invocation();
                    WebClient client = clients.create(
                            OpenAiCompatibleSpeechRecognitionProvider.class,
                            invocation,
                            properties.requestTimeout(),
                            properties.maxResponseBytes());
                    MultipartBodyBuilder body = new MultipartBodyBuilder();
                    body.part("file", namedAudio(command.audio(), command.mimeType()))
                            .contentType(safeMediaType(command.mimeType()));
                    body.part("model", invocation.model());
                    body.part("response_format", "verbose_json");
                    String language = providerLanguage(command.language());
                    if (language != null) {
                        body.part("language", language);
                    }
                    return client.post()
                            .uri(relativePath(properties.transcriptionPath()))
                            .contentType(MediaType.MULTIPART_FORM_DATA)
                            .headers(headers -> headers.setBearerAuth(invocation.bearer()))
                            .bodyValue(body.build())
                            .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                                    ? response.bodyToMono(String.class)
                                            .switchIfEmpty(Mono.error(invalidResponse()))
                                            .map(this::parseJson)
                                            .map(root -> parse(root, command))
                                    : response.releaseBody().then(Mono.error(providerFailure())))
                            .timeout(properties.requestTimeout());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(OpenAiCompatibleSpeechRecognitionProvider::sanitizeError);
    }

    private JsonNode parseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception error) {
            throw invalidResponse();
        }
    }

    private Result parse(JsonNode root, Command command) {
        String text = requiredText(root, "text");
        String language = optionalText(root, "language");
        JsonNode usage = root.path("usage");
        int inputTokens = optionalUsage(usage, "input_tokens", "prompt_tokens");
        int outputTokens = optionalUsage(usage, "output_tokens", "completion_tokens");
        int billedSeconds = billedSeconds(usage, root, command.durationMs());
        return new Result(text, language, inputTokens, outputTokens, false, billedSeconds);
    }

    private static int billedSeconds(JsonNode usage, JsonNode root, long durationMs) {
        double seconds = optionalNonNegativeNumber(usage, "seconds");
        if (seconds < 0) {
            seconds = optionalNonNegativeNumber(root, "duration");
        }
        long rounded = seconds >= 0 ? (long) Math.ceil(seconds) : Math.max(1L, (durationMs + 999L) / 1000L);
        if (rounded > Integer.MAX_VALUE) {
            throw invalidResponse();
        }
        return (int) rounded;
    }

    private static int optionalUsage(JsonNode usage, String primary, String fallback) {
        if (usage == null || !usage.isObject()) {
            return 0;
        }
        JsonNode node = usage.has(primary) ? usage.get(primary) : usage.get(fallback);
        if (node == null || node.isNull()) {
            return 0;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw invalidResponse();
        }
        return node.intValue();
    }

    private static double optionalNonNegativeNumber(JsonNode parent, String field) {
        if (parent == null || !parent.isObject() || !parent.has(field)) {
            return -1;
        }
        JsonNode node = parent.get(field);
        if (!node.isNumber() || !Double.isFinite(node.doubleValue()) || node.doubleValue() < 0) {
            throw invalidResponse();
        }
        return node.doubleValue();
    }

    private static String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) {
            throw invalidResponse();
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            return null;
        }
        return node.textValue().trim();
    }

    private static void requireCommand(Command command) {
        if (command == null || command.audio() == null || command.audio().length == 0
                || command.invocation() == null || command.durationMs() < 0) {
            throw new IllegalArgumentException("语音识别参数不完整");
        }
    }

    private static ByteArrayResource namedAudio(byte[] audio, String mimeType) {
        String extension = switch (mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT)) {
            case "audio/mpeg" -> "mp3";
            case "audio/mp4" -> "m4a";
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            default -> "wav";
        };
        return new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "speech." + extension;
            }
        };
    }

    private static MediaType safeMediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType == null ? "application/octet-stream" : mimeType);
        } catch (IllegalArgumentException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String providerLanguage(String language) {
        return switch (language == null ? "auto" : language) {
            case "zh-CN" -> "zh";
            case "en-US" -> "en";
            case "auto" -> null;
            default -> language;
        };
    }

    private static String relativePath(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static Throwable sanitizeError(Throwable error) {
        if (error instanceof IntelligenceException) {
            return error;
        }
        if (isTimeout(error)) {
            return new IntelligenceException(504, "provider_timeout", "语音识别服务超时");
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
        return new IntelligenceException(502, "provider_failure", "语音识别服务调用失败");
    }

    private static IntelligenceException invalidResponse() {
        return new IntelligenceException(502, "provider_invalid_response", "语音识别服务返回无效数据");
    }
}
