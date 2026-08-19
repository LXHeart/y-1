package com.grassland.intelligence.imagestudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import io.netty.channel.ChannelOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;

/**
 * OpenAI 兼容抠图提供者（任务书 #43 D3）。
 *
 * <p>使用 OpenAI images/edits 风格请求：multipart form 上传图片，期望返回 b64_json PNG。
 * baseUrl / model 来自平台模型配置（控制面 {@code image_edit}）；apiKey 沿用平台默认。
 * 错误映射照 {@code ImageGenerationClient}。
 */
public class OpenAiCompatibleImageMattingProvider implements ImageMattingProvider {

    /** 共享连接池：provider 每次执行按控制面配置新建实例，client 必须复用否则泄漏 Netty 连接池。 */
    private static final WebClient WEB_CLIENT = buildWebClient();

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleImageMattingProvider(String baseUrl, String model, String apiKey) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.model = model;
        this.apiKey = apiKey;
    }

    private static WebClient buildWebClient() {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(java.time.Duration.ofSeconds(60));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    public Mono<MattingResult> matting(MattingCommand command) {
        return Mono.defer(() -> {
            org.springframework.http.client.MultipartBodyBuilder builder =
                    new org.springframework.http.client.MultipartBodyBuilder();
            builder.part("image", new org.springframework.core.io.ByteArrayResource(command.image()) {
                @Override
                public String getFilename() {
                    return "input.png";
                }
            }).contentType(MediaType.parseMediaType(
                    command.mimeType() == null ? "application/octet-stream" : command.mimeType()));
            builder.part("model", model);
            builder.part("response_format", "b64_json");

            return WEB_CLIENT.post()
                    .uri(baseUrl + "/images/edits")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(builder.build())
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status >= 200 && status < 300) {
                            return response.bodyToMono(String.class).map(this::parseResult);
                        }
                        return response.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(ignored -> Mono.error(providerError(status)));
                    })
                    .onErrorMap(error -> {
                        if (error instanceof IntelligenceException) return error;
                        if (isTimeout(error)) {
                            return new IntelligenceException(504, "抠图服务超时，请稍后重试");
                        }
                        return new IntelligenceException(502, "抠图服务调用失败，请稍后重试");
                    })
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private MattingResult parseResult(String json) {
        try {
            var root = mapper.readTree(json);
            var first = root.path("data").path(0);
            String b64 = first.path("b64_json").asText(null);
            if (b64 == null || b64.isBlank()) {
                throw new IntelligenceException(502, "抠图服务返回了无效数据");
            }
            byte[] png = Base64.getDecoder().decode(b64.trim());
            return new MattingResult(png, model, false);
        } catch (IntelligenceException e) {
            throw e;
        } catch (Exception e) {
            throw new IntelligenceException(502, "抠图服务返回了无法解析的内容");
        }
    }

    private static IntelligenceException providerError(int status) {
        if (status == 402) {
            return new IntelligenceException(400, "抠图服务配额不足");
        }
        if (status == 429) {
            return new IntelligenceException(400, "抠图请求过于频繁，请稍后重试");
        }
        if (status >= 500) {
            return new IntelligenceException(502, "抠图服务暂时不可用，请稍后重试");
        }
        return new IntelligenceException(400, "抠图失败，请稍后重试");
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
