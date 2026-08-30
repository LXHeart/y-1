package com.grassland.intelligence.embedding;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory;
import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiCompatibleEmbeddingProviderTest {

    private WireMockServer server;
    private OpenAiCompatibleEmbeddingProvider provider;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        OpenAiCompatibleHttpClientFactory clients = mock(OpenAiCompatibleHttpClientFactory.class);
        when(clients.create(eq(OpenAiCompatibleEmbeddingProvider.class), any(), any(), anyInt()))
                .thenReturn(WebClient.builder().baseUrl(server.baseUrl() + "/").build());
        provider = new OpenAiCompatibleEmbeddingProvider(
                new EmbeddingProviderProperties(
                        "/embeddings", Duration.ofSeconds(5), 65_536, 3, true),
                clients);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void sendsJsonAndParsesVectorAndUsage() {
        server.stubFor(post(urlEqualTo("/embeddings")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"data":[{"index":0,"embedding":[0.1,-0.2,0.3]}],
                         "usage":{"prompt_tokens":7,"total_tokens":7}}
                        """)));
        ProviderInvocation invocation = new ProviderInvocation(
                "openai-compatible", server.baseUrl(), "text-embedding-3-small",
                "runtime-secret-key-5678", false);

        EmbeddingProvider.Result result = provider.embed(
                new EmbeddingProvider.Command("coffee shop poster", invocation)).block(Duration.ofSeconds(5));

        assertThat(result.vector()).containsExactly(0.1, -0.2, 0.3);
        assertThat(result.inputTokens()).isEqualTo(7);
        assertThat(result.sandbox()).isFalse();
        assertThat(provider.algorithmVersion(new EmbeddingProvider.Command("x", invocation)))
                .isEqualTo("openai-compatible-v1:text-embedding-3-small:3");
        server.verify(postRequestedFor(urlEqualTo("/embeddings"))
                .withHeader("Authorization", equalTo("Bearer runtime-secret-key-5678"))
                .withRequestBody(equalToJson("""
                        {"model":"text-embedding-3-small","input":"coffee shop poster",
                         "encoding_format":"float","dimensions":3}
                        """, true, true)));
    }

    @Test
    void rejectsMissingUsageAndDoesNotExposeResponse() {
        server.stubFor(post(urlEqualTo("/embeddings")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3]}],\"detail\":\"provider-secret\"}")));
        ProviderInvocation invocation = new ProviderInvocation(
                "openai-compatible", server.baseUrl(), "text-embedding-3-small",
                "runtime-secret-key-5678", false);

        assertThatThrownBy(() -> provider.embed(
                        new EmbeddingProvider.Command("coffee", invocation)).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.code()).isEqualTo("provider_invalid_response");
                    assertThat(error.getMessage()).doesNotContain("provider-secret")
                            .doesNotContain("runtime-secret-key-5678");
                });
    }
}
