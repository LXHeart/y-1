package com.grassland.intelligence.speech;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class OpenAiCompatibleSpeechRecognitionProviderTest {

    private WireMockServer server;
    private OpenAiCompatibleSpeechRecognitionProvider provider;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        OpenAiCompatibleHttpClientFactory clients = mock(OpenAiCompatibleHttpClientFactory.class);
        when(clients.create(eq(OpenAiCompatibleSpeechRecognitionProvider.class), any(), any(), anyInt()))
                .thenReturn(WebClient.builder().baseUrl(server.baseUrl() + "/").build());
        provider = new OpenAiCompatibleSpeechRecognitionProvider(
                new SpeechProviderProperties(
                        "openai-compatible", server.baseUrl(), "platform-secret-key-1234", "whisper-1",
                        "/audio/transcriptions", Duration.ofSeconds(5), 65_536, 0, 0, 1),
                clients);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void sendsMultipartAndParsesUsageWithoutLeakingBearer() {
        server.stubFor(post(urlEqualTo("/audio/transcriptions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"text":"hello world","language":"en","duration":1.2,
                         "usage":{"input_tokens":4,"output_tokens":2}}
                        """)));
        ProviderInvocation invocation = new ProviderInvocation(
                "openai-compatible", server.baseUrl(), "whisper-1", "runtime-secret-key-5678", false);

        SpeechRecognitionProvider.Result result = provider.transcribe(new SpeechRecognitionProvider.Command(
                UUID.randomUUID(), "checksum", "en-US", 1_100,
                new byte[] {'R', 'I', 'F', 'F'}, "audio/wav", invocation)).block(Duration.ofSeconds(5));

        assertThat(result.text()).isEqualTo("hello world");
        assertThat(result.detectedLanguage()).isEqualTo("en");
        assertThat(result.inputTokens()).isEqualTo(4);
        assertThat(result.outputTokens()).isEqualTo(2);
        assertThat(result.billedSeconds()).isEqualTo(2);
        assertThat(result.sandbox()).isFalse();
        server.verify(postRequestedFor(urlEqualTo("/audio/transcriptions"))
                .withHeader("Authorization", equalTo("Bearer runtime-secret-key-5678")));
        String multipart = server.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(multipart)
                .contains("name=\"file\"", "name=\"model\"", "whisper-1")
                .contains("name=\"language\"", "en")
                .contains("name=\"response_format\"", "verbose_json");
    }

    @Test
    void sanitizesProviderErrorBody() {
        server.stubFor(post(urlEqualTo("/audio/transcriptions")).willReturn(aResponse()
                .withStatus(500).withBody("upstream-secret-detail")));
        ProviderInvocation invocation = new ProviderInvocation(
                "openai-compatible", server.baseUrl(), "whisper-1", "runtime-secret-key-5678", false);

        assertThatThrownBy(() -> provider.transcribe(new SpeechRecognitionProvider.Command(
                        UUID.randomUUID(), "checksum", "auto", 1_000,
                        new byte[] {1}, "audio/wav", invocation)).block(Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.code()).isEqualTo("provider_failure");
                    assertThat(error.getMessage()).doesNotContain("upstream-secret-detail")
                            .doesNotContain("runtime-secret-key-5678");
                });
    }
}
