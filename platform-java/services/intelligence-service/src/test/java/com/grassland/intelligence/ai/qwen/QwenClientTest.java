package com.grassland.intelligence.ai.qwen;

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
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.PlatformModelConfig;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@link QwenClient} 流式解析：WireMock 回放 OpenAI 兼容 SSE → {@code choices[0].delta.content} 映射为 {@code ChatChunk}；
 * 遇 {@code [DONE]} 终止；malformed 行吞掉；4xx→400、5xx→502。复刻 legacy {@code qwen-provider.ts:1167} 读取语义。
 *
 * <p>共用一个 WireMockServer（@BeforeAll 起，类级稳定，避免每用例起停服务导致的连接竞态），
 * 每用例 {@code resetMappings} 隔离 stub。
 */
class QwenClientTest {

    private static WireMockServer wireMock;
    private static QwenClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ai.qwen.base-url", wireMock.baseUrl());
        env.setProperty("ai.qwen.api-key", "sk-test");
        env.setProperty("ai.qwen.model", "qwen-plus");
        client = new QwenClient(new PlatformModelConfig(env));
    }

    @AfterAll
    static void stopServer() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetMappings();
    }

    private TextRunCommand command() {
        return new TextRunCommand(List.of(ChatMessage.system("你是助手"), ChatMessage.user("你好")));
    }

    @Test
    @DisplayName("逐块解析 delta.content，遇 [DONE] 终止")
    void parsesStreamedDeltasAndStopsAtDone() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}]}\n\n"   // 空 delta（role-only）→ mapNotNull 吞掉
                + "data: not-json\n\n"                          // malformed 行 → 吞掉
                + "data: [DONE]\n\n";
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody(body)));

        List<ChatChunk> chunks = client.startTextRun(command()).collectList().block();

        assertThat(chunks).containsExactly(new ChatChunk("你好"), new ChatChunk("，世界"));

        // 请求体含 stream:true + enable_thinking:false + Bearer 鉴权（messages 内对象键序由 Map.of 决定，故用 containing）。
        wireMock.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(containing("\"stream\":true"))
                .withRequestBody(containing("\"enable_thinking\":false"))
                .withRequestBody(containing("\"role\":\"system\""))
                .withRequestBody(containing("\"content\":\"你好\"")));
    }

    @Test
    @DisplayName("上游 4xx → IntelligenceException(400)")
    void maps4xxTo400() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(401).withBody("unauthorized")));
        assertThatThrownBy(() -> client.startTextRun(command()).collectList().block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    @DisplayName("上游 5xx → IntelligenceException(502)")
    void maps5xxTo502() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(503).withBody("down")));
        assertThatThrownBy(() -> client.startTextRun(command()).collectList().block())
                .isInstanceOfSatisfying(IntelligenceException.class, e -> assertThat(e.status()).isEqualTo(502));
    }

    @Test
    @DisplayName("空流（仅 [DONE]）→ 零 chunk 正常完成")
    void emptyStreamCompletesWithNoChunks() {
        wireMock.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(aResponse().withStatus(200).withBody("data: [DONE]\n\n")));
        List<ChatChunk> chunks = client.startTextRun(command()).collectList().block();
        assertThat(chunks).isEmpty();
    }
}
