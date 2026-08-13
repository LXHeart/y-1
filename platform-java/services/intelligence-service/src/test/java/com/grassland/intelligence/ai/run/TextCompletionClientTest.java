package com.grassland.intelligence.ai.run;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.DnsPinningResolver;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import io.netty.util.concurrent.DefaultEventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TextCompletionClient")
class TextCompletionClientTest {

    private WireMockServer provider;

    @BeforeEach
    void startProvider() {
        provider = new WireMockServer(0);
        provider.start();
    }

    @AfterEach
    void stopProvider() {
        provider.stop();
    }

    @Test
    @DisplayName("provider baseUrl 的路径前缀会被保留")
    void preservesProviderBasePath() {
        provider.stubFor(post(urlEqualTo("/compatible-mode/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"ok"}}],
                         "usage":{"prompt_tokens":1,"completion_tokens":2}}
                        """)));
        PlatformProviderPolicy policy = mock(PlatformProviderPolicy.class);
        when(policy.validateBaseUrl(provider.baseUrl() + "/compatible-mode/v1"))
                .thenReturn(java.net.URI.create(provider.baseUrl() + "/compatible-mode/v1"));
        TextCompletionClient client = new TextCompletionClient(5_000, DnsPinningResolver.create(), policy);

        TextCompletionResult result = client.complete(
                provider.baseUrl() + "/compatible-mode/v1", "key", "model", "prompt", 16, false).block();

        assertThat(result.content()).isEqualTo("ok");
        provider.verify(1, postRequestedFor(urlEqualTo("/compatible-mode/v1/chat/completions")));
    }

    @Test
    @DisplayName("冻结执行客户端保留多模态图片片断")
    void serializesMultimodalParts() {
        provider.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"ok"}}],
                         "usage":{"prompt_tokens":3,"completion_tokens":1}}
                        """)));
        PlatformProviderPolicy policy = mock(PlatformProviderPolicy.class);
        when(policy.validateBaseUrl(provider.baseUrl()))
                .thenReturn(java.net.URI.create(provider.baseUrl()));
        TextCompletionClient client = new TextCompletionClient(
                5_000, DnsPinningResolver.create(), policy);

        TextCompletionResult result = client.completeMessages(
                provider.baseUrl(), "key", "vision-model",
                List.of(ChatMessage.system("frozen task"), ChatMessage.user(List.of(
                        ContentPart.image("data:image/png;base64,iVBORw0KGgo="),
                        ContentPart.text("describe")))),
                16, false).block();

        assertThat(result.content()).isEqualTo("ok");
        provider.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("frozen task"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("image_url"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.containing("data:image/png;base64")));
    }

    @Test
    @DisplayName("provider 缺少 usage 时拒绝结算")
    void rejectsMissingUsage() {
        assertInvalidUsage("""
                {"choices":[{"message":{"content":"ok"}}]}
                """);
    }

    @Test
    @DisplayName("provider 返回负 usage 时拒绝结算")
    void rejectsNegativeUsage() {
        assertInvalidUsage("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":-1,"completion_tokens":2}}
                """);
    }

    @Test
    @DisplayName("provider 返回超出 int 的 usage 时拒绝结算")
    void rejectsOverflowUsage() {
        assertInvalidUsage("""
                {"choices":[{"message":{"content":"ok"}}],
                 "usage":{"prompt_tokens":9223372036854775807,"completion_tokens":2}}
                """);
    }

    @Test
    @DisplayName("BYOK Netty resolver 把原 hostname 映射到固定 IP 并拒绝其他 host")
    void pinnedResolverUsesOnlyApprovedAddress() throws Exception {
        InetAddress approved = InetAddress.getByName("8.8.8.8");
        var group = new TextCompletionClient.PinnedAddressResolverGroup(
                "api.example.com", List.of(approved));
        var executor = new DefaultEventExecutor();
        try {
            var resolver = group.getResolver(executor);
            InetSocketAddress resolved = resolver.resolve(
                    InetSocketAddress.createUnresolved("api.example.com", 443)).get();
            assertThat(resolved.getAddress().getHostAddress()).isEqualTo("8.8.8.8");

            assertThatThrownBy(() -> resolver.resolve(
                    InetSocketAddress.createUnresolved("other.example.com", 443)).get())
                    .hasCauseInstanceOf(SecurityException.class);
        } finally {
            group.close();
            executor.shutdownGracefully().sync();
        }
    }

    private void assertInvalidUsage(String responseBody) {
        provider.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(okJson(responseBody)));
        PlatformProviderPolicy policy = mock(PlatformProviderPolicy.class);
        when(policy.validateBaseUrl(provider.baseUrl()))
                .thenReturn(java.net.URI.create(provider.baseUrl()));
        TextCompletionClient client = new TextCompletionClient(5_000, DnsPinningResolver.create(), policy);

        assertThatThrownBy(() -> client.complete(
                provider.baseUrl(), "key", "model", "prompt", 16, false).block())
                .isInstanceOf(RuntimeException.class);
    }
}
