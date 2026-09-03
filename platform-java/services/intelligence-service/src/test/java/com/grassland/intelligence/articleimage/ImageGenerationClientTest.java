package com.grassland.intelligence.articleimage;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImageGenerationClientTest {

	private static WireMockServer wireMock;
	private static ImageGenerationClient client;
	/** 任务书 #58 决策 G：端点必传（静态 env 端点已删），测试里指向 WireMock。 */
	private static ImageGenerationClient.Endpoint endpoint;

	@BeforeAll
	static void startServer() {
		wireMock = new WireMockServer(options().dynamicPort());
		wireMock.start();
		client = new ImageGenerationClient(new ImageGenerationConfig("image-config-v1", 80, 5_000L, 1_000L));
		endpoint = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "image-key", "wanx-v1", "qwen");
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
	@DisplayName("MiniMax 方言：POST /image_generation，data[].image_url 下载转 b64")
	void generatesImageUsingMinimaxDialect() {
		wireMock.stubFor(post(urlEqualTo("/image_generation"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"base_resp":{"status_code":0,"status_msg":""},
						 "data":{"image_url":"%s/files/abc.png"}}
						""".formatted(wireMock.baseUrl()))));
		wireMock.stubFor(get(urlEqualTo("/files/abc.png")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "image/png").withBody(new byte[]{1, 2, 3})));

		var minimax = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "mm-key", "image-01", "minimax");
		GeneratedImage result = client.generate("一只猫", "1024x1024", minimax).block(Duration.ofSeconds(5));

		assertThat(result).isNotNull();
		assertThat(result.base64()).isEqualTo(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
		wireMock.verify(postRequestedFor(urlEqualTo("/image_generation"))
				.withHeader("Authorization", equalTo("Bearer mm-key"))
				.withRequestBody(containing("\"model\":\"image-01\"")).withRequestBody(containing("\"prompt\":\"一只猫\""))
				.withRequestBody(containing("\"response_format\":\"base64\"")));
		// base64 直取路径（image_base64 对象形态）
		wireMock.resetMappings();
		wireMock.stubFor(post(urlEqualTo("/image_generation"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"base_resp":{"status_code":0,"status_msg":""},
						 "data":{"image_base64":"iVBORw0KGgo="}}
						""")));
		GeneratedImage direct = client.generate("再来一张", "1024x1024", minimax).block(Duration.ofSeconds(5));
		assertThat(direct.base64()).isEqualTo("iVBORw0KGgo=");
	}

	@Test
	@DisplayName("MiniMax 方言：base_resp 非 0 → 502 带上游信息")
	void minimaxUpstreamErrorSurfaced() {
		wireMock.stubFor(post(urlEqualTo("/image_generation"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"base_resp":{"status_code":2049,"status_msg":"invalid api key"}}
						""")));
		var minimax = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "bad", "image-01", "minimax");
		assertThatThrownBy(() -> client.generate("一只猫", "1024x1024", minimax).block(Duration.ofSeconds(5)))
				.isInstanceOf(IntelligenceException.class).hasMessageContaining("invalid api key")
				.hasMessageContaining("API 密钥无效");
	}

	@Test
	@DisplayName("MiniMax 方言：区间内 8 倍数尺寸直传 width/height；区间外回落白名单 aspect_ratio")
	void minimaxSizePassthroughFallsBackToAspectRatio() {
		stubMinimaxBase64Ok();
		var minimax = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "mm-key", "image-01", "minimax");
		client.generate("一只猫", "1024x1536", minimax).block(Duration.ofSeconds(5));
		wireMock.verify(postRequestedFor(urlEqualTo("/image_generation")).withRequestBody(containing("\"width\":1024"))
				.withRequestBody(containing("\"height\":1536"))
				// 官方建议开启的自动优化提示词（2026-09-02 拍板，默认 false）
				.withRequestBody(containing("\"prompt_optimizer\":true")));

		// 384x512 超下限：约分 3:4 在白名单 → aspect_ratio
		client.generate("竖图", "384x512", minimax).block(Duration.ofSeconds(5));
		wireMock.verify(postRequestedFor(urlEqualTo("/image_generation"))
				.withRequestBody(containing("\"aspect_ratio\":\"3:4\"")));

		// 比例不落白名单（1000:777）→ 尺寸完全不传，维持上游默认
		client.generate("怪比例", "1000x777", minimax).block(Duration.ofSeconds(5));
		wireMock.verify(postRequestedFor(urlEqualTo("/image_generation"))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("\"width\""))
				.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notContaining("\"aspect_ratio\"")));
	}

	@Test
	@DisplayName("MiniMax 方言：参考图直传 subject_reference（data URI、取首个合规图）；不合规跳过")
	void minimaxReferenceImageSentAsSubjectReference() {
		stubMinimaxBase64Ok();
		var minimax = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "mm-key", "image-01", "minimax");
		byte[] pngBytes = {9, 9, 9};
		String expectedDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);

		client.generate("人像", "1024x1024", minimax, java.util.List.of(new ReferenceImage("image/webp", new byte[]{1}), // MIME
																														// 不受支持
																														// →
																														// 跳过
				new ReferenceImage("image/png", pngBytes))) // 首个合规 → 采信
				.block(Duration.ofSeconds(5));

		wireMock.verify(postRequestedFor(urlEqualTo("/image_generation"))
				.withRequestBody(containing("\"subject_reference\":[{\"type\":\"character\","))
				.withRequestBody(containing("\"image_file\":\"" + expectedDataUri + "\"")));
	}

	@Test
	@DisplayName("MiniMax 方言：prompt 超 1500 字符截断，避免上游 2013 拒绝")
	void minimaxPromptTruncatedToContractLimit() {
		stubMinimaxBase64Ok();
		var minimax = new ImageGenerationClient.Endpoint(wireMock.baseUrl(), "mm-key", "image-01", "minimax");
		String longPrompt = "长".repeat(1510);

		client.generate(longPrompt, "1024x1024", minimax).block(Duration.ofSeconds(5));

		var requests = wireMock.findAll(
				com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/image_generation")));
		String sentBody = requests.get(requests.size() - 1).getBodyAsString();
		int promptLength = sentBody.split("\"prompt\":\"")[1].split("\"")[0].length();
		assertThat(promptLength).isEqualTo(1500);
	}

	private void stubMinimaxBase64Ok() {
		wireMock.stubFor(post(urlEqualTo("/image_generation"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"base_resp":{"status_code":0,"status_msg":""},
						 "data":{"image_base64":"iVBORw0KGgo="}}
						""")));
	}

	@Test
	@DisplayName("任务书 #58：endpoint 为 null 一律 503，不再回落静态 env 端点")
	void rejectsNullEndpointFailClosed() {
		assertThatThrownBy(() -> client.generate("提示词", "1024x1024", null).block(Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(503));
	}

	@Test
	@DisplayName("images/generations requests b64_json so every result can enter managed storage")
	void generatesImageUsingOpenAiImagesContract() {
		wireMock.stubFor(post(urlEqualTo("/images/generations"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"data":[{"b64_json":"iVBORw0KGgo=","revised_prompt":"优化后"}]}
						""")));

		GeneratedImage result = client.generate("原始提示词", "1024x1024", endpoint).block(Duration.ofSeconds(2));

		assertThat(result).isEqualTo(new GeneratedImage(null, "iVBORw0KGgo=", "优化后"));
		wireMock.verify(postRequestedFor(urlEqualTo("/images/generations"))
				.withHeader("Authorization", equalTo("Bearer image-key"))
				.withRequestBody(containing("\"model\":\"wanx-v1\""))
				.withRequestBody(containing("\"prompt\":\"原始提示词\"")).withRequestBody(containing("\"n\":1"))
				.withRequestBody(containing("\"size\":\"1024x1024\""))
				.withRequestBody(containing("\"response_format\":\"b64_json\"")));
	}

	@Test
	@DisplayName("URL-only provider response is rejected instead of bypassing managed media storage")
	void rejectsUrlOnlyImageResult() {
		wireMock.stubFor(post(urlEqualTo("/images/generations"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"data":[{"url":"https://images.example/generated.png"}]}
						""")));

		assertThatThrownBy(() -> client.generate("提示词", "1024x1024", endpoint).block(Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(502));
	}

	@Test
	@DisplayName("b64_json response is returned for local persistence")
	void acceptsBase64ImageResult() {
		wireMock.stubFor(post(urlEqualTo("/images/generations"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"data":[{"b64_json":"iVBORw0KGgo=","revised_prompt":"优化后"}]}
						""")));

		GeneratedImage result = client.generate("提示词", "1024x1792", endpoint).block(Duration.ofSeconds(2));

		assertThat(result).isEqualTo(new GeneratedImage(null, "iVBORw0KGgo=", "优化后"));
	}

	@Test
	@DisplayName("b64_json responses larger than WebClient defaults remain supported")
	void acceptsLargeBase64ImageResult() {
		String base64 = "A".repeat(300 * 1024);
		wireMock.stubFor(post(urlEqualTo("/images/generations"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"data\":[{\"b64_json\":\"" + base64 + "\"}]}")));

		GeneratedImage result = client.generate("提示词", "1024x1024", endpoint).block(Duration.ofSeconds(3));

		assertThat(result.base64()).hasSize(300 * 1024);
	}

	@Test
	@DisplayName("invalid provider base64 maps to 502")
	void rejectsInvalidBase64Result() {
		wireMock.stubFor(post(urlEqualTo("/images/generations"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"data\":[{\"b64_json\":\"not-base64!\"}]}")));

		assertThatThrownBy(() -> client.generate("提示词", "1024x1024", endpoint).block(Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(502));
	}

	@Test
	@DisplayName("provider 402 keeps legacy quota message and maps to 400")
	void mapsQuotaFailure() {
		wireMock.stubFor(
				post(urlEqualTo("/images/generations")).willReturn(aResponse().withStatus(402).withBody("quota")));

		assertThatThrownBy(() -> client.generate("提示词", "1024x1024", endpoint).block(Duration.ofSeconds(2)))
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

		assertThatThrownBy(() -> client.generate("提示词", "1792x1024", endpoint).block(Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> {
					assertThat(error.status()).isEqualTo(502);
					assertThat(error.getMessage()).doesNotContain("secret upstream detail");
				});
	}

	@Test
	@DisplayName("empty provider data maps to 502")
	void rejectsEmptyProviderResult() {
		wireMock.stubFor(post(urlEqualTo("/images/generations")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"data\":[]}")));

		assertThatThrownBy(() -> client.generate("提示词", "1024x1024", endpoint).block(Duration.ofSeconds(2)))
				.isInstanceOfSatisfying(IntelligenceException.class,
						error -> assertThat(error.status()).isEqualTo(502));
	}
}
