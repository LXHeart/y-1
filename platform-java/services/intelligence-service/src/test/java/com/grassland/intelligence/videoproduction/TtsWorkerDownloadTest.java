package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.controlplane.TrustedOriginService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class TtsWorkerDownloadTest {
	private WireMockServer server;
	private TtsWorker worker;

	@BeforeEach
	void setup() {
		server = new WireMockServer(0);
		server.start();
		var origins = mock(TrustedOriginService.class);
		when(origins.enabledOrigins()).thenReturn(Set.of());
		worker = new TtsWorker(null, null, null, null, null, null, null, null, null, null, null, null,
				new VideoGenerationProperties(), null, origins);
	}

	@AfterEach
	void cleanup() {
		server.stop();
	}

	@Test
	void downloadsSignedUrlWithoutReencodingAndUnpacksBeforeProbing() {
		byte[] audio = {73, 68, 51, 1, 2, 3};
		server.stubFor(get(urlEqualTo("/speech?sig=a%2Bb"))
				.willReturn(ok().withBody(MinimaxTtsAudioTest.tar("audio/content.mp3", audio))));
		assertThat(fetch("minimax", server.baseUrl() + "/speech?sig=a%2Bb").block()).isEqualTo(audio);
	}

	@Test
	void otherProvidersKeepTheirPayloadAndMinimaxKeepsRawAudio() {
		byte[] audio = {73, 68, 51, 1, 2, 3};
		server.stubFor(get(urlEqualTo("/raw")).willReturn(ok().withBody(audio)));
		assertThat(fetch("minimax", server.baseUrl() + "/raw").block()).isEqualTo(audio);
		byte[] tar = MinimaxTtsAudioTest.tar("a.mp3", audio);
		server.stubFor(get(urlEqualTo("/bundle")).willReturn(ok().withBody(tar)));
		assertThat(fetch("another-provider", server.baseUrl() + "/bundle").block()).isEqualTo(tar);
	}

	@Test
	void invalidBundleAndUntrustedOriginFailClosed() {
		server.stubFor(
				get(urlEqualTo("/bad")).willReturn(ok().withBody(MinimaxTtsAudioTest.tar("a.titles", new byte[]{1}))));
		assertThatThrownBy(() -> fetch("minimax", server.baseUrl() + "/bad").block())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> fetch("minimax", "https://untrusted.invalid/audio"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("origin");
	}

	@Test
	void preservesFlacMimeForExtractedAudio() {
		String mime = ReflectionTestUtils.invokeMethod(worker, "sniffAudioMime", new byte[]{'f', 'L', 'a', 'C'});
		assertThat(mime).isEqualTo("audio/flac");
	}

	private Mono<byte[]> fetch(String provider, String url) {
		var resolution = VideoGenerationProviderResolver.TtsProviderResolution.available(provider, "speech-02-hd",
				"unused", server.baseUrl(), 1, 1, UUID.randomUUID(), null);
		var result = new TtsProvider.TtsResult(TtsProvider.TtsResult.State.SUCCEEDED, "task", url, null, null, null);
		return ReflectionTestUtils.invokeMethod(worker, "fetchAudio", null, resolution, result);
	}
}
