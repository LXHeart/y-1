package com.grassland.intelligence.speech;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.ai.OpenAiCompatibleHttpClientFactory;
import com.grassland.intelligence.ai.ProviderInvocation;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

class MinimaxSpeechRecognitionProviderTest {
	private WireMockServer server;
	private SpeechRecognitionProvider provider;

	@BeforeEach
	void setup() {
		server = new WireMockServer(0);
		server.start();
		var clients = mock(OpenAiCompatibleHttpClientFactory.class);
		when(clients.create(any(), any(), any(), anyInt()))
				.thenReturn(WebClient.builder().baseUrl(server.baseUrl() + "/v1/").build());
		var openai = new OpenAiCompatibleSpeechRecognitionProvider(
				new SpeechProviderProperties("/audio/transcriptions", Duration.ofSeconds(5), 65536), clients);
		provider = new SpeechProviderRegistry(List.of(openai, new MinimaxSpeechRecognitionProvider(openai)))
				.require("minimax");
	}

	@AfterEach
	void cleanup() {
		server.stop();
	}

	@Test
	void nativeRouteHeaderAndWordTimestampsWithVersionedBaseUrl() {
		server.stubFor(post(urlEqualTo("/v1/speech_to_text")).willReturn(okJson("""
				{"text":"草场。","duration":5.184,"n_speakers":1,
				 "segments":[{"id":0,"start":0.08,"end":0.22,"speaker":"S1","text":"草"},
				 {"id":1,"start":0.22,"end":0.36,"speaker":"S1","text":"场。"}]}
				""")));
		var result = provider.transcribe(command("zh-CN")).block();
		assertThat(result.text()).isEqualTo("草场。");
		assertThat(result.billedSeconds()).isEqualTo(6);
		assertThat(result.sandbox()).isFalse();
		assertThat(result.segments()).containsExactly(new SpeechRecognitionProvider.Segment(0.08, 0.22, "草"),
				new SpeechRecognitionProvider.Segment(0.22, 0.36, "场。"));
		server.verify(postRequestedFor(urlEqualTo("/v1/speech_to_text")).withHeader("language", equalTo("zh"))
				.withHeader("Authorization", equalTo("Bearer test-secret")));
		assertThat(server.getAllServeEvents().getFirst().getRequest().getBodyAsString())
				.contains("name=\"model\"", "asr-1.0", "name=\"timestamp_level\"", "word", "name=\"response_format\"",
						"verbose_json", "filename=\"speech.mp3\"")
				.doesNotContain("name=\"language\"");
	}

	@Test
	void autoLanguageDoesNotSendAHint() {
		server.stubFor(
				post(urlEqualTo("/v1/speech_to_text")).willReturn(okJson("{\"text\":\"hello\",\"duration\":1}")));
		provider.transcribe(command("auto")).block();
		server.verify(postRequestedFor(urlEqualTo("/v1/speech_to_text")).withHeader("language", absent()));
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 402, 429, 500})
	void providerRejectionIsSanitizedAndNotRetried(int status) {
		server.stubFor(post(urlEqualTo("/v1/speech_to_text"))
				.willReturn(aResponse().withStatus(status).withBody("private-provider-detail")));
		assertThatThrownBy(() -> provider.transcribe(command("auto")).block()).isInstanceOf(IntelligenceException.class)
				.hasMessageNotContaining("private-provider-detail").hasMessageNotContaining("test-secret");
		server.verify(1, postRequestedFor(urlEqualTo("/v1/speech_to_text")));
	}

	@Test
	void missingTranscriptIsNotSuccess() {
		server.stubFor(post(urlEqualTo("/v1/speech_to_text")).willReturn(okJson("{\"duration\":1}")));
		assertThatThrownBy(() -> provider.transcribe(command("auto")).block()).isInstanceOfSatisfying(
				IntelligenceException.class, e -> assertThat(e.code()).isEqualTo("provider_invalid_response"));
	}

	private SpeechRecognitionProvider.Command command(String language) {
		return new SpeechRecognitionProvider.Command(UUID.randomUUID(), "checksum", language, 5120, new byte[]{1, 2, 3},
				"audio/mpeg",
				new ProviderInvocation("minimax", server.baseUrl() + "/v1", "asr-1.0", "test-secret", false));
	}
}
