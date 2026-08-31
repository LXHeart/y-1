package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link RoutedTextCompletionService} 路由分支单测：BYOK / 平台控制面 / 匿名 / DENIED / 错误文案映射。
 * 计费不归本服务管（刻意的），故断言只覆盖「用哪个模型 + 怎么报错」。
 */
class RoutedTextCompletionServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final List<ChatMessage> MESSAGES = List.of(ChatMessage.user("写一句探店评价"));

    private final IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
    private final ByokRoutingService routing = mock(ByokRoutingService.class);
    private final ProviderKeyDecryptor keyDecryptor = mock(ProviderKeyDecryptor.class);
    private final TextCompletionClient textCompletion = mock(TextCompletionClient.class);

    private RoutedTextCompletionService service;

    @BeforeEach
    void setUp() {
        service = new RoutedTextCompletionService(callers, routing, keyDecryptor, textCompletion);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/test").build());
    }

    @Test
    void personalByokResolutionRoutesToUserModel() {
        ProviderResolution byok = ProviderResolution.byok(
                "openai-compatible", "https://byok.example.com/v1", "my-model", "cipher", "kv-1");
        when(routing.resolveProvider(isNull(), eq("acc-1"), eq("text"), eq(true))).thenReturn(Mono.just(byok));
        when(keyDecryptor.decryptIfNeeded(byok)).thenReturn("plain-byok-key");
        when(textCompletion.completeMessages(eq("openai-compatible"), eq("https://byok.example.com/v1"),
                eq("plain-byok-key"), eq("my-model"),
                anyList(), anyInt(), eq(true), any())).thenReturn(
                Mono.just(new TextCompletionResult("好", 1, 1, null)));

        StepVerifier.create(service.completeFor("acc-1", null, MESSAGES, 128, TIMEOUT, "生成失败"))
                .assertNext(result -> assertThat(result.content()).isEqualTo("好"))
                .verifyComplete();
    }

    @Test
    void platformResolutionRoutesToControlPlaneModelWithDecryptedCredential() {
        ProviderResolution platform = ProviderResolution.platform(
                UUID.randomUUID(), "openai-compatible", "https://platform.example.com/v1", "platform-model",
                3, null, "platform-cipher", 7L);
        when(routing.resolveProvider(eq("org-1"), eq("acc-1"), eq("text"), eq(true))).thenReturn(Mono.just(platform));
        when(keyDecryptor.decryptIfNeeded(platform)).thenReturn("platform-key");
        when(textCompletion.completeMessages(eq("openai-compatible"), eq("https://platform.example.com/v1"),
                eq("platform-key"), eq("platform-model"),
                anyList(), anyInt(), eq(false), any())).thenReturn(
                Mono.just(new TextCompletionResult("ok", 2, 2, null)));

        StepVerifier.create(service.completeFor("acc-1", "org-1", MESSAGES, 128, TIMEOUT, "生成失败"))
                .assertNext(result -> assertThat(result.content()).isEqualTo("ok"))
                .verifyComplete();
    }

    @Test
    void anonymousRequestBypassesByKeyLayerAndGoesPlatformDirect() {
        when(callers.resolveOptional(any(ServerHttpRequest.class))).thenReturn(Mono.empty());
        ProviderResolution platform = ProviderResolution.platform(
                null, "openai-completions", "https://platform.example.com/v1", "platform-model", 1, null);
        when(routing.resolvePlatform("text")).thenReturn(Mono.just(platform));
        when(keyDecryptor.decryptIfNeeded(platform)).thenReturn("env-bootstrap-key");
        when(textCompletion.completeMessages(eq("openai-completions"), any(), eq("env-bootstrap-key"),
                eq("platform-model"),
                anyList(), anyInt(), anyBoolean(), any())).thenReturn(
                Mono.just(new TextCompletionResult("guest", 1, 1, null)));

        StepVerifier.create(service.complete(exchange(), MESSAGES, 64, TIMEOUT, "生成失败"))
                .assertNext(result -> assertThat(result.content()).isEqualTo("guest"))
                .verifyComplete();
    }

    @Test
    void deniedResolutionFailsClosedWith503() {
        when(routing.resolvePlatform("text")).thenReturn(
                Mono.just(ProviderResolution.denied("no_platform_model")));

        StepVerifier.create(service.completePlatformOnly(MESSAGES, 64, TIMEOUT, "生成失败"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(503);
                    assertThat(error.getMessage()).contains("平台未配置可用的内置模型");
                })
                .verify();
    }

    @Test
    void genericUpstreamFailureIsMappedToCallerMessageButConfigAndTimeoutErrorsKept() {
        ProviderResolution platform = ProviderResolution.platform(
                null, "openai-completions", "https://platform.example.com/v1", "m", 1, null);
        when(routing.resolvePlatform("text")).thenReturn(Mono.just(platform));
        when(keyDecryptor.decryptIfNeeded(platform)).thenReturn("k");

        when(textCompletion.completeMessages(any(), any(), any(), any(), anyList(), anyInt(), anyBoolean(), any()))
                .thenReturn(Mono.error(new IntelligenceException(502, "AI provider 调用失败")));
        StepVerifier.create(service.completePlatformOnly(MESSAGES, 64, TIMEOUT, "图片评价生成失败，请稍后重试"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IntelligenceException.class);
                    assertThat(((IntelligenceException) error).status()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("图片评价生成失败，请稍后重试");
                })
                .verify();

        when(textCompletion.completeMessages(any(), any(), any(), any(), anyList(), anyInt(), anyBoolean(), any()))
                .thenReturn(Mono.error(new IntelligenceException(503, "平台凭据解密不可用：未配置 CRYPTO_KEK_BASE64")));
        StepVerifier.create(service.completePlatformOnly(MESSAGES, 64, TIMEOUT, "图片评价生成失败，请稍后重试"))
                .expectErrorSatisfies(error -> {
                    assertThat(((IntelligenceException) error).status()).isEqualTo(503);
                    assertThat(error.getMessage()).contains("CRYPTO_KEK_BASE64");
                })
                .verify();
    }

    @Test
    void streamPassesChunksThroughWithCallerMessageOnError() {
        ProviderResolution byok = ProviderResolution.byok(
                "openai-compatible", "https://byok.example.com/v1", "my-model", "cipher", "kv-2");
        when(callers.resolveOptional(any(ServerHttpRequest.class))).thenReturn(Mono.just(new Caller(
                "acc-1", "recommender", "token", null, null, "user", "acc-1", null)));
        when(routing.resolveProvider(isNull(), eq("acc-1"), eq("text"), eq(true))).thenReturn(Mono.just(byok));
        when(keyDecryptor.decryptIfNeeded(byok)).thenReturn("plain");
        when(textCompletion.streamMessages(eq("openai-compatible"), eq("https://byok.example.com/v1"),
                eq("plain"), eq("my-model"),
                anyList(), anyInt(), eq(true), any()))
                .thenReturn(Flux.just(new ChatChunk("你"), new ChatChunk("好")))
                .thenReturn(Flux.error(new IntelligenceException(502, "AI provider 调用失败")));

        StepVerifier.create(service.stream(exchange(), MESSAGES, 256, TIMEOUT, "正文生成失败"))
                .expectNextMatches(chunk -> chunk.content().equals("你"))
                .expectNextMatches(chunk -> chunk.content().equals("好"))
                .verifyComplete();

        StepVerifier.create(service.stream(exchange(), MESSAGES, 256, TIMEOUT, "正文生成失败"))
                .expectErrorSatisfies(error -> {
                    assertThat(((IntelligenceException) error).status()).isEqualTo(502);
                    assertThat(error.getMessage()).isEqualTo("正文生成失败");
                })
                .verify();
    }
}
