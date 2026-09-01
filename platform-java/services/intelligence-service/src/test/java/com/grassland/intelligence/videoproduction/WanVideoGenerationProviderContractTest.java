package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务书 #64 卡6：万相（DashScope 兼容）异步视频契约——X-DashScope-Async 提交 + tasks 轮询。 */
@DisplayName("Wan video adapter contract")
class WanVideoGenerationProviderContractTest {

    private static WireMockServer wireMock;
    private static WanVideoGenerationProvider provider;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        provider = new WanVideoGenerationProvider(new VideoProviderEndpoint(wireMock.baseUrl(),
                "wan-test-key", "/api/v1/services/aigc/video-generation/video-synthesis",
                "/api/v1/tasks/{taskId}", "/api/v1/tasks/{taskId}", Duration.ofSeconds(10)));
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
    void submitCarriesAsyncHeaderAndPollMapsVideoUrl() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/services/aigc/video-generation/video-synthesis"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"output\":{\"task_id\":\"wan-task-1\",\"task_status\":\"PENDING\"}}")));
        VideoGenerationProvider.ProviderResult submitted = provider.submit(command()).block();
        assertThat(submitted.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.QUEUED);
        assertThat(submitted.providerTaskId()).isEqualTo("wan-task-1");
        wireMock.verify(postRequestedFor(
                        urlEqualTo("/api/v1/services/aigc/video-generation/video-synthesis"))
                .withHeader("X-DashScope-Async", equalTo("enable"))
                .withHeader("Authorization", equalTo("Bearer wan-test-key"))
                .withRequestBody(containing("\"prompt\":\"生成一段视频\""))
                .withRequestBody(containing("\"img_url\":\"data:image/jpeg;base64,AAAA\""))
                .withRequestBody(containing("\"size\":\"1080P\"")));

        wireMock.stubFor(get(urlEqualTo("/api/v1/tasks/wan-task-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"output\":{\"task_status\":\"RUNNING\"}}")));
        assertThat(provider.poll("wan-task-1", 5).block().state())
                .isEqualTo(VideoGenerationProvider.ProviderResult.State.PROCESSING);

        wireMock.stubFor(get(urlEqualTo("/api/v1/tasks/wan-task-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":"
                                + "\"https://wan.example.test/out.mp4\"}}")));
        VideoGenerationProvider.ProviderResult done = provider.poll("wan-task-1", 5).block();
        assertThat(done.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(done.resultUrl()).isEqualTo("https://wan.example.test/out.mp4");
        // poll-only：不接 webhook（无回调端点）
        wireMock.verify(0, getRequestedFor(urlEqualTo("/callbacks")));
    }

    @Test
    void failedTaskPreservesVendorCode() {
        wireMock.stubFor(get(urlEqualTo("/api/v1/tasks/wan-task-2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"output\":{\"task_status\":\"FAILED\",\"code\":\"InvalidParameter\","
                                + "\"message\":\"duration unsupported\"}}")));
        VideoGenerationProvider.ProviderResult failed = provider.poll("wan-task-2", 5).block();
        assertThat(failed.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.FAILED);
        assertThat(failed.errorCode()).isEqualTo("InvalidParameter");
        assertThat(failed.errorMessage()).isEqualTo("duration unsupported");
    }

    private static VideoGenerationProvider.ProviderCommand command() {
        return new VideoGenerationProvider.ProviderCommand(
                UUID.randomUUID(), "wan2.2-t2v-plus", "生成一段视频", List.of("AAAA"), 5, "9:16");
    }
}
