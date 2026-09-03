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

/** xAI Grok Imagine 异步视频契约——/v1/videos/generations 提交 + /v1/videos/{id} 轮询。 */
@DisplayName("xAI video adapter contract")
class XaiVideoGenerationProviderContractTest {

	private static WireMockServer wireMock;
	private static XaiVideoGenerationProvider provider;

	@BeforeAll
	static void start() {
		wireMock = new WireMockServer(options().dynamicPort());
		wireMock.start();
		provider = new XaiVideoGenerationProvider(
				VideoProviderEndpoint.xai(wireMock.baseUrl(), "xai-test-key", Duration.ofSeconds(10)));
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
	void submitCarriesPromptImageAndDurationThenPollMapsVideoUrl() {
		wireMock.stubFor(post(urlEqualTo("/v1/videos/generations")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"request_id\":\"xai-req-1\"}")));
		VideoGenerationProvider.ProviderResult submitted = provider.submit(command()).block();
		assertThat(submitted.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.QUEUED);
		assertThat(submitted.providerTaskId()).isEqualTo("xai-req-1");
		wireMock.verify(postRequestedFor(urlEqualTo("/v1/videos/generations"))
				.withHeader("Authorization", equalTo("Bearer xai-test-key"))
				.withRequestBody(containing("\"model\":\"grok-imagine-video\""))
				.withRequestBody(containing("\"prompt\":\"生成一段视频\""))
				.withRequestBody(containing("\"image_url\":\"data:image/jpeg;base64,AAAA\""))
				.withRequestBody(containing("\"duration\":5")).withRequestBody(containing("\"aspect_ratio\":\"9:16\""))
				.withRequestBody(containing("\"resolution\":\"720p\"")));

		wireMock.stubFor(get(urlEqualTo("/v1/videos/xai-req-1")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"status\":\"pending\",\"progress\":20}")));
		VideoGenerationProvider.ProviderResult pending = provider.poll("xai-req-1", 5).block();
		assertThat(pending.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.QUEUED);
		assertThat(pending.progress()).isEqualTo(20);

		wireMock.stubFor(get(urlEqualTo("/v1/videos/xai-req-1"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"status\":\"done\",\"progress\":100,\"video\":{\"url\":"
								+ "\"https://vidgen.x.ai/v/xai-req-1.mp4\",\"duration\":5,"
								+ "\"respect_moderation\":true}}")));
		VideoGenerationProvider.ProviderResult done = provider.poll("xai-req-1", 5).block();
		assertThat(done.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.SUCCEEDED);
		assertThat(done.resultUrl()).isEqualTo("https://vidgen.x.ai/v/xai-req-1.mp4");
		assertThat(done.durationSeconds()).isEqualTo(5);
		// poll-only：不接 webhook（无回调端点）
		wireMock.verify(0, getRequestedFor(urlEqualTo("/callbacks")));
	}

	@Test
	void failedStatusPreservesVendorCodeAndMessage() {
		wireMock.stubFor(get(urlEqualTo("/v1/videos/xai-req-2"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"status\":\"failed\",\"error\":{\"code\":\"invalid_argument\","
								+ "\"message\":\"duration out of range\"}}")));
		VideoGenerationProvider.ProviderResult failed = provider.poll("xai-req-2", 5).block();
		assertThat(failed.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.FAILED);
		assertThat(failed.errorCode()).isEqualTo("invalid_argument");
		assertThat(failed.errorMessage()).isEqualTo("duration out of range");
	}

	@Test
	void expiredStatusFailsClosed() {
		wireMock.stubFor(get(urlEqualTo("/v1/videos/xai-req-3")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"status\":\"expired\"}")));
		VideoGenerationProvider.ProviderResult expired = provider.poll("xai-req-3", 5).block();
		assertThat(expired.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.FAILED);
		assertThat(expired.errorCode()).isEqualTo("provider_failed");
	}

	@Test
	void moderationFilteredVideoIsRejectedEvenWithUrl() {
		wireMock.stubFor(get(urlEqualTo("/v1/videos/xai-req-4")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"status\":\"done\",\"video\":{\"url\":"
						+ "\"https://vidgen.x.ai/v/xai-req-4.mp4\",\"respect_moderation\":false}}")));
		VideoGenerationProvider.ProviderResult filtered = provider.poll("xai-req-4", 5).block();
		assertThat(filtered.state()).isEqualTo(VideoGenerationProvider.ProviderResult.State.FAILED);
		assertThat(filtered.errorCode()).isEqualTo("moderation_filtered");
		assertThat(filtered.resultUrl()).isNull();
	}

	@Test
	void durationIsClampedToUpstreamRange() {
		wireMock.stubFor(post(urlEqualTo("/v1/videos/generations")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody("{\"request_id\":\"xai-req-5\"}")));
		provider.submit(new VideoGenerationProvider.ProviderCommand(UUID.randomUUID(), "grok-imagine-video", "生成一段视频",
				List.of(), 30, "16:9")).block();
		wireMock.verify(
				postRequestedFor(urlEqualTo("/v1/videos/generations")).withRequestBody(containing("\"duration\":15")));

		provider.submit(new VideoGenerationProvider.ProviderCommand(UUID.randomUUID(), "grok-imagine-video", "生成一段视频",
				List.of(), 0, "16:9")).block();
		wireMock.verify(
				postRequestedFor(urlEqualTo("/v1/videos/generations")).withRequestBody(containing("\"duration\":1")));
	}

	private static VideoGenerationProvider.ProviderCommand command() {
		return new VideoGenerationProvider.ProviderCommand(UUID.randomUUID(), "grok-imagine-video", "生成一段视频",
				List.of("AAAA"), 5, "9:16");
	}
}
