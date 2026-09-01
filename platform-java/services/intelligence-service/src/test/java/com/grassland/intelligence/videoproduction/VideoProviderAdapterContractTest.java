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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoProviderAdapterContractTest {
    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    /** 任务书 #64 卡2：adapter 按控制面解析的 endpoint 构造，不再读全局 properties。 */
    private static VideoProviderEndpoint endpoint(String pollPath) {
        return new VideoProviderEndpoint(wireMock.baseUrl(), "video-test-key",
                "/create", pollPath, "/retrieve", java.time.Duration.ofSeconds(10));
    }

    @Test
    void seedanceSubmitAndPollMapsNestedVideoUrl() {
        SeedanceVideoGenerationProvider provider = new SeedanceVideoGenerationProvider(endpoint("/poll/{taskId}"));
        wireMock.stubFor(post(urlEqualTo("/create")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"task-seed-1\"}")));
        VideoGenerationProvider.ProviderResult submitted = provider.submit(command()).block();
        assertThat(submitted.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.QUEUED);
        assertThat(submitted.providerTaskId()).isEqualTo("task-seed-1");
        wireMock.verify(postRequestedFor(urlEqualTo("/create"))
                .withHeader("Authorization", equalTo("Bearer video-test-key"))
                .withRequestBody(containing("\"duration\":6"))
                .withRequestBody(containing("\"ratio\":\"9:16\"")));

        wireMock.stubFor(get(urlEqualTo("/poll/task-seed-1")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\":\"succeeded\",\"content\":{\"video_url\":\"https://vendor/video.mp4\"},\"progress\":100}")));
        VideoGenerationProvider.ProviderResult result = provider.poll("task-seed-1", 6).block();
        assertThat(result.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(result.resultUrl()).isEqualTo("https://vendor/video.mp4");
    }

    @Test
    void minimaxPollRetrievesFileIdWhenSuccessHasNoDirectUrl() {
        MinimaxVideoGenerationProvider provider = new MinimaxVideoGenerationProvider(endpoint("/poll"));
        wireMock.stubFor(get(urlEqualTo("/poll?task_id=task-mini-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"success\",\"file_id\":\"file-1\"}")));
        wireMock.stubFor(get(urlEqualTo("/retrieve?file_id=file-1"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"file\":{\"download_url\":\"https://vendor/minimax.mp4\"}}")));

        VideoGenerationProvider.ProviderResult result = provider.poll("task-mini-1", 6).block();
        assertThat(result.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(result.resultUrl()).isEqualTo("https://vendor/minimax.mp4");
        wireMock.verify(getRequestedFor(urlEqualTo("/retrieve?file_id=file-1"))
                .withHeader("Authorization", equalTo("Bearer video-test-key")));
    }

    @Test
    void failedStatusPreservesVendorErrorCodeAndMessage() {
        MinimaxVideoGenerationProvider provider = new MinimaxVideoGenerationProvider(endpoint("/poll"));
        wireMock.stubFor(get(urlEqualTo("/poll?task_id=task-mini-2"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        "{\"status\":\"failed\",\"base_resp\":{\"status_code\":1008,\"status_msg\":\"quota\"}}")));
        VideoGenerationProvider.ProviderResult result = provider.poll("task-mini-2", 6).block();
        assertThat(result.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.FAILED);
        assertThat(result.errorCode()).isEqualTo("1008");
        assertThat(result.errorMessage()).isEqualTo("quota");
    }

    @Test
    void malformedVendorJsonFailsClosed() {
        MinimaxVideoGenerationProvider provider = new MinimaxVideoGenerationProvider(endpoint("/poll"));
        wireMock.stubFor(get(urlEqualTo("/poll?task_id=task-malformed"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("not-json")));
        assertThatThrownBy(() -> provider.poll("task-malformed", 6).block())
                .hasMessageContaining("响应 JSON 无效");
    }

    @Test
    void sandboxProviderSucceedsImmediatelyWithOpaqueNonRoutableReference() {
        SandboxVideoGenerationProvider provider = new SandboxVideoGenerationProvider();
        VideoGenerationProvider.ProviderCommand command = command();
        VideoGenerationProvider.ProviderResult submitted = provider.submit(command).block();
        assertThat(submitted.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(submitted.providerTaskId()).isEqualTo("sandbox:" + command.jobId());
        assertThat(submitted.progress()).isEqualTo(100);
        assertThat(submitted.durationSeconds()).isEqualTo(command.durationSeconds());
        // 占位符仅由 VideoAssetArchiveService 内部消费，不得形如可路由 API 路径
        assertThat(submitted.resultUrl()).isEqualTo("sandbox://video/" + command.jobId());
        assertThat(submitted.resultUrl()).doesNotStartWith("/").doesNotStartWith("http");

        VideoGenerationProvider.ProviderResult polled = provider.poll("sandbox:task-sb-1", 6).block();
        assertThat(polled.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(polled.resultUrl()).isEqualTo("sandbox://video/task-sb-1");
    }

    private static VideoGenerationProvider.ProviderCommand command() {
        return new VideoGenerationProvider.ProviderCommand(
                UUID.randomUUID(), "video-01", "生成一段视频", List.of("AAAA"), 6, "9:16");
    }
}
