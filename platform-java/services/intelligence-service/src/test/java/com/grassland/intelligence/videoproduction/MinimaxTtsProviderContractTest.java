package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务书 #64 卡5：MiniMax T2A 异步协议契约（submit→taskId→poll→file_id→URL 兜底）。 */
@DisplayName("MiniMax TTS adapter contract")
class MinimaxTtsProviderContractTest {

    private static WireMockServer wireMock;
    private static MinimaxTtsProvider provider;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        provider = new MinimaxTtsProvider(new VideoProviderEndpoint(wireMock.baseUrl(), "tts-test-key",
                "/v1/t2a_v2_async", "/v1/query/t2a_v2_async", "/v1/files/retrieve",
                Duration.ofSeconds(10)));
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void submitQueuesByTaskIdAndPollMapsAudioUrl() {
        wireMock.stubFor(post(urlEqualTo("/v1/t2a_v2_async")).willReturn(okJson(
                "{\"task_id\":\"tts-task-1\"}")));
        TtsProvider.TtsResult submitted = provider
                .submit(new TtsProvider.TtsCommand(UUID.randomUUID(), "speech-02-hd", "旁白文本", null))
                .block();
        assertThat(submitted.state()).isEqualTo(TtsProvider.TtsResult.State.QUEUED);
        assertThat(submitted.providerTaskId()).isEqualTo("tts-task-1");
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/t2a_v2_async"))
                .withHeader("Authorization", equalTo("Bearer tts-test-key"))
                .withRequestBody(equalTo("{\"model\":\"speech-02-hd\",\"text\":\"旁白文本\",\"stream\":false,"
                        + "\"voice_setting\":{\"voice_id\":\"male-qn-qingse\"}}")));

        wireMock.stubFor(get(urlEqualTo("/v1/query/t2a_v2_async?task_id=tts-task-1")).willReturn(okJson(
                "{\"status\":\"processing\"}")));
        assertThat(provider.poll("tts-task-1").block().state())
                .isEqualTo(TtsProvider.TtsResult.State.PROCESSING);

        wireMock.stubFor(get(urlEqualTo("/v1/query/t2a_v2_async?task_id=tts-task-1")).willReturn(okJson(
                "{\"status\":\"success\",\"data\":{\"audio\":{\"file_id\":\"audio-file-1\"}}}")));
        wireMock.stubFor(get(urlEqualTo("/v1/files/retrieve?file_id=audio-file-1")).willReturn(okJson(
                "{\"file\":{\"download_url\":\"" + wireMock.baseUrl() + "/files/audio-file-1.mp3\"}}")));
        TtsProvider.TtsResult done = provider.poll("tts-task-1").block();
        assertThat(done.state()).isEqualTo(TtsProvider.TtsResult.State.SUCCEEDED);
        assertThat(done.audioUrl()).contains("/files/audio-file-1.mp3");
        assertThat(done.durationMs()).isNull();
    }

    @Test
    void failedStatusPreservesVendorErrorCode() {
        wireMock.stubFor(get(urlEqualTo("/v1/query/t2a_v2_async?task_id=tts-task-2")).willReturn(okJson(
                "{\"status\":\"fail\",\"base_resp\":{\"status_code\":1004,\"status_msg\":\"quota\"}}")));
        TtsProvider.TtsResult failed = provider.poll("tts-task-2").block();
        assertThat(failed.state()).isEqualTo(TtsProvider.TtsResult.State.FAILED);
        assertThat(failed.errorCode()).isEqualTo("1004");
        assertThat(failed.errorMessage()).isEqualTo("quota");
    }

    @Test
    void submitWithoutTaskIdFailsClosed() {
        wireMock.stubFor(post(urlEqualTo("/v1/t2a_v2_async")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider
                .submit(new TtsProvider.TtsCommand(UUID.randomUUID(), "speech-02-hd", "x", null)).block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 task_id");
        wireMock.verify(0, getRequestedFor(urlEqualTo("/v1/files/retrieve")));
    }
}
