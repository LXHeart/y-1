package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.junit.jupiter.api.*;

class WanPresenterProviderTest {
    static WireMockServer server;
    static WanPresenterProvider provider;
    @BeforeAll static void start() {
        server = new WireMockServer(options().dynamicPort()); server.start();
        provider = new WanPresenterProvider(new VideoProviderEndpoint(server.baseUrl(), "test-key", "/unused", "/unused", "/unused", Duration.ofSeconds(3)));
    }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll(); }
    @Test void submitsExactAudioDrivenProtocolAndReadsNestedResult() {
        server.stubFor(post(urlEqualTo("/api/v1/services/aigc/image2video/video-synthesis"))
                .willReturn(okJson("{\"output\":{\"task_id\":\"presenter-1\"}}")));
        assertThat(provider.submit(new WanPresenterProvider.Command("https://media.example/a.png", "https://media.example/a.mp3", 19000, "720P")).block().providerTaskId()).isEqualTo("presenter-1");
        server.verify(postRequestedFor(urlEqualTo("/api/v1/services/aigc/image2video/video-synthesis"))
                .withHeader("X-DashScope-Async", equalTo("enable"))
                .withRequestBody(equalToJson("{\"model\":\"wan2.2-s2v\",\"input\":{\"image_url\":\"https://media.example/a.png\",\"audio_url\":\"https://media.example/a.mp3\"},\"parameters\":{\"resolution\":\"720P\"}}")));
        server.stubFor(get(urlEqualTo("/api/v1/tasks/presenter-1"))
                .willReturn(okJson("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":{\"video_url\":\"https://media.example/result.mp4\"}}}")));
        var result = provider.poll("presenter-1").block();
        assertThat(result.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
        assertThat(result.resultUrl()).isEqualTo("https://media.example/result.mp4");
        assertThat(result.durationSeconds()).isNull();
    }
    @Test void rejectsLongAudioAndLocalMediaBeforeBillingCall() {
        for (int duration : new int[]{0, 20000, 30000})
            assertThatThrownBy(() -> provider.submit(new WanPresenterProvider.Command("https://media.example/a.png", "https://media.example/a.mp3", duration, "720P")).block()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.submit(new WanPresenterProvider.Command("http://localhost/a.png", "https://media.example/a.mp3", 1000, "720P")).block()).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }
    @Test void unknownIsNotSuccessAndSuccessWithoutMediaFails() {
        for (String status : new String[]{"UNKNOWN", "NEW_VENDOR_STATE", "SUCCEEDED"}) {
            server.stubFor(get(urlEqualTo("/api/v1/tasks/presenter-1"))
                    .willReturn(okJson("{\"output\":{\"task_status\":\"" + status + "\"}}")));
            assertThat(provider.poll("presenter-1").block().state()).isEqualTo(status.equals("SUCCEEDED")
                    ? VideoGenerationProvider.ProviderResult.State.FAILED : VideoGenerationProvider.ProviderResult.State.UNKNOWN);
        }
    }
}
