package com.grassland.intelligence.articleimage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ImageGenerationClientTest {

    private static WireMockServer wireMock;
    private static ImageGenerationClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MockEnvironment env = new MockEnvironment()
                .withProperty("ai.qwen.base-url", wireMock.baseUrl())
                .withProperty("ai.qwen.api-key", "qwen-key")
                .withProperty("ai.qwen.model", "qwen-plus")
                .withProperty("ai.image-generation.base-url", wireMock.baseUrl())
                .withProperty("ai.image-generation.api-key", "image-key")
                .withProperty("ai.image-generation.model", "wanx-v1")
                .withProperty("ai.image-generation.read-timeout-ms", "1000");
        client = new ImageGenerationClient(new ImageGenerationConfig(env));
    }

    @AfterAll
    static void stopServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetMappings();
    }

    @Test
    @DisplayName("images/generations wire contract includes model prompt n and size")
    void generatesImageUsingOpenAiImagesContract() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":[{"url":"https://images.example/generated.png","revised_prompt":"优化后"}]}
                                """)));

        GeneratedImage result = client.generate("原始提示词", "1024x1024").block(Duration.ofSeconds(2));

        assertThat(result).isEqualTo(new GeneratedImage(
                "https://images.example/generated.png", null, "优化后"));
        wireMock.verify(postRequestedFor(urlEqualTo("/images/generations"))
                .withHeader("Authorization", equalTo("Bearer image-key"))
                .withRequestBody(containing("\"model\":\"wanx-v1\""))
                .withRequestBody(containing("\"prompt\":\"原始提示词\""))
                .withRequestBody(containing("\"n\":1"))
                .withRequestBody(containing("\"size\":\"1024x1024\"")));
    }

    @Test
    @DisplayName("b64_json response is returned for local persistence")
    void acceptsBase64ImageResult() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":[{"b64_json":"iVBORw0KGgo=","revised_prompt":"优化后"}]}
                                """)));

        GeneratedImage result = client.generate("提示词", "1024x1792").block(Duration.ofSeconds(2));

        assertThat(result).isEqualTo(new GeneratedImage(null, "iVBORw0KGgo=", "优化后"));
    }

    @Test
    @DisplayName("b64_json responses larger than WebClient defaults remain supported")
    void acceptsLargeBase64ImageResult() {
        String base64 = "A".repeat(300 * 1024);
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"b64_json\":\"" + base64 + "\"}]}")));

        GeneratedImage result = client.generate("提示词", "1024x1024").block(Duration.ofSeconds(3));

        assertThat(result.base64()).hasSize(300 * 1024);
    }

    @Test
    @DisplayName("invalid provider base64 maps to 502")
    void rejectsInvalidBase64Result() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[{\"b64_json\":\"not-base64!\"}]}")));

        assertThatThrownBy(() -> client.generate("提示词", "1024x1024").block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));
    }

    @Test
    @DisplayName("provider 402 keeps legacy quota message and maps to 400")
    void mapsQuotaFailure() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(402).withBody("quota")));

        assertThatThrownBy(() -> client.generate("提示词", "1024x1024").block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.status()).isEqualTo(400);
                    assertThat(error.getMessage()).contains("配额不足");
                });
    }

    @Test
    @DisplayName("provider 5xx maps to safe 502 without leaking body")
    void mapsProviderFailure() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(503).withBody("secret upstream detail")));

        assertThatThrownBy(() -> client.generate("提示词", "1792x1024").block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(IntelligenceException.class, error -> {
                    assertThat(error.status()).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain("secret upstream detail");
                });
    }

    @Test
    @DisplayName("empty provider data maps to 502")
    void rejectsEmptyProviderResult() {
        wireMock.stubFor(post(urlEqualTo("/images/generations"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":[]}")));

        assertThatThrownBy(() -> client.generate("提示词", "1024x1024").block(Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));
    }

    @Test
    @DisplayName("image generation config falls back to platform Qwen values")
    void configFallsBackToQwen() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ai.qwen.base-url", "https://qwen.example/v1")
                .withProperty("ai.qwen.api-key", "qwen-key")
                .withProperty("ai.qwen.model", "qwen-image");

        ImageGenerationConfig config = new ImageGenerationConfig(env);

        assertThat(config.baseUrl()).isEqualTo("https://qwen.example/v1");
        assertThat(config.apiKey()).isEqualTo("qwen-key");
        assertThat(config.model()).isEqualTo("qwen-image");
    }
}
