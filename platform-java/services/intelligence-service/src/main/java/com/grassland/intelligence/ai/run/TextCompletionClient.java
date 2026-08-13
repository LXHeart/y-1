package com.grassland.intelligence.ai.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ProviderUrlGuard;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.security.IntelligenceException;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI 兼容 text completion 客户端（GL-P3-AI-001 控制面执行链路）。
 *
 * <p>平台 run 与 BYOK run 共用：平台传 env 平台 api-key 作 bearer，BYOK 传解密后的明文 key 作 bearer。
 * 解析 {@code choices[0].message.content} + {@code usage.prompt_tokens/completion_tokens} 供结算计量。
 *
 * <p>平台地址执行基础 URL 校验；BYOK 地址只允许 HTTPS，并在保存和执行时验证全部公网 DNS 结果。
 * 实际 Netty 连接使用固定地址解析器，避免校验后再次解析形成 DNS rebinding 窗口。
 */
@Component
public class TextCompletionClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration timeout;
    private final DnsPinningResolver dnsPinning;
    private final PlatformProviderPolicy platformProviderPolicy;

    public TextCompletionClient(
            @Value("${ai.qwen.read-timeout-ms:120000}") long readTimeoutMs,
            DnsPinningResolver dnsPinning,
            PlatformProviderPolicy platformProviderPolicy) {
        this.timeout = Duration.ofMillis(readTimeoutMs);
        this.dnsPinning = dnsPinning;
        this.platformProviderPolicy = platformProviderPolicy;
    }

    public Mono<TextCompletionResult> complete(
            String baseUrl, String bearer, String model, String prompt, int maxTokens, boolean byok) {
        return completeMessages(baseUrl, bearer, model, List.of(ChatMessage.user(prompt)), maxTokens, byok);
    }

    public Mono<TextCompletionResult> completeMessages(
            String baseUrl, String bearer, String model, List<ChatMessage> messages,
            int maxTokens, boolean byok) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream().map(TextCompletionClient::messageBody).toList());
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        body.put("enable_thinking", false);

        return Mono.fromCallable(() -> byok ? pinnedByokClient(baseUrl) : platformClient(baseUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(client -> client.post().uri("chat/completions")
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
                }))
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, e -> new IntelligenceException(504, "AI provider 调用超时"))
                .onErrorMap(e -> e instanceof IntelligenceException ? e : new IntelligenceException(502, "AI provider 调用失败"));
    }

    private static Map<String, Object> messageBody(ChatMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", message.role());
        body.put("content", message.multimodal()
                ? message.parts().stream().map(TextCompletionClient::partBody).toList()
                : message.content());
        return body;
    }

    private static Map<String, Object> partBody(ContentPart part) {
        return switch (part) {
            case ContentPart.Text text -> Map.of("type", "text", "text", text.text());
            case ContentPart.Image image -> Map.of(
                    "type", "image_url", "image_url", Map.of("url", image.url()));
            case ContentPart.Video video -> Map.of(
                    "type", "video_url", "video_url", Map.of("url", video.url()));
        };
    }

    private WebClient platformClient(String baseUrl) {
        platformProviderPolicy.validateBaseUrl(baseUrl);
        return WebClient.builder().baseUrl(withTrailingSlash(baseUrl)).build();
    }

    private WebClient pinnedByokClient(String baseUrl) {
        java.net.URI target = ProviderUrlGuard.validateByokForExecution(baseUrl, dnsPinning);
        List<InetAddress> pinnedAddresses = dnsPinning.getPinnedAddresses(target.getHost()).stream()
                .sorted(Comparator.comparing(InetAddress::getHostAddress))
                .toList();
        if (pinnedAddresses.isEmpty()) {
            throw new IllegalArgumentException("BYOK provider 没有可用的固定地址");
        }

        // URI 仍保留原始 hostname（Host header / TLS SNI），只替换 Netty 的地址解析结果，
        // 确保校验后的请求不会再次走系统 DNS，关闭 DNS rebinding 的 TOCTOU 窗口。
        HttpClient httpClient = HttpClient.create()
                .resolver(new PinnedAddressResolverGroup(target.getHost(), pinnedAddresses));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(withTrailingSlash(baseUrl))
                .build();
    }

    private TextCompletionResult parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode choices = root.path("choices");
            String content = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText("")
                    : "";
            JsonNode usage = root.path("usage");
            if (!usage.isObject()) {
                throw new IntelligenceException(502, "AI provider 缺少 usage");
            }
            int inputTokens = requireUsageInt(usage, "prompt_tokens");
            int outputTokens = requireUsageInt(usage, "completion_tokens");
            Math.addExact(inputTokens, outputTokens);
            return new TextCompletionResult(content, inputTokens, outputTokens);
        } catch (IntelligenceException e) {
            throw e;
        } catch (Exception e) {
            throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
        }
    }

    private static int requireUsageInt(JsonNode usage, String fieldName) {
        JsonNode value = usage.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IntelligenceException(502, "AI provider usage 无效");
        }
        int parsed = value.intValue();
        if (parsed < 0) {
            throw new IntelligenceException(502, "AI provider usage 无效");
        }
        return parsed;
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    static final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        private final String expectedHost;
        private final List<InetAddress> addresses;

        PinnedAddressResolverGroup(String expectedHost, List<InetAddress> addresses) {
            this.expectedHost = expectedHost;
            this.addresses = List.copyOf(addresses);
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                @Override
                protected boolean doIsResolved(InetSocketAddress address) {
                    return false;
                }

                @Override
                protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
                    try {
                        promise.setSuccess(resolveOne(unresolved));
                    } catch (RuntimeException error) {
                        promise.setFailure(error);
                    }
                }

                @Override
                protected void doResolveAll(
                        InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
                    try {
                        validateHost(unresolved);
                        List<InetSocketAddress> resolved = addresses.stream()
                                .map(address -> new InetSocketAddress(address, unresolved.getPort()))
                                .toList();
                        promise.setSuccess(resolved);
                    } catch (RuntimeException error) {
                        promise.setFailure(error);
                    }
                }

                private InetSocketAddress resolveOne(InetSocketAddress unresolved) {
                    validateHost(unresolved);
                    return new InetSocketAddress(addresses.getFirst(), unresolved.getPort());
                }

                private void validateHost(InetSocketAddress unresolved) {
                    if (!expectedHost.equalsIgnoreCase(unresolved.getHostString())) {
                        throw new SecurityException("Unexpected outbound host");
                    }
                }
            };
        }
    }
}
