package com.grassland.intelligence.verification.official;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link OfficialVerificationGateway} 认证网关契约单测（P1 骨架，ADR-D04）：Bearer 鉴权、
 * 请求体字段、响应解析三态与指标归一；故障/坏响应 → empty（unavailable 语义）。
 */
@DisplayName("OfficialVerificationGateway 网关契约")
class OfficialVerificationGatewayTest {

	static final WireMockServer GATEWAY = new WireMockServer(0);

	@BeforeAll
	static void start() {
		GATEWAY.start();
	}

	@AfterAll
	static void stop() {
		GATEWAY.stop();
	}

	@Test
	void failsFastWhenEnabledWithoutBaseUrlOrToken() {
		assertThatThrownBy(() -> new OfficialVerificationGateway("", "tok", 8000))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new OfficialVerificationGateway("http://gateway.example", " ", 8000))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void postsBearerAuthoredRequestAndParsesContract() {
		GATEWAY.stubFor(post(urlEqualTo("/v1/official-verification"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"accountMatch":true,"published":true,"commentFound":null,
						 "metrics":{"likes":120,"comments":3,"noise":"ignored"}}
						""")));
		OfficialVerificationGateway gateway = new OfficialVerificationGateway(GATEWAY.baseUrl(), "gw-token", 8000);

		var data = gateway.fetch("douyin", "https://www.douyin.com/x", "@seed", "好评").block();

		assertThat(data).isNotNull();
		assertThat(data.accountMatch()).isTrue();
		assertThat(data.published()).isTrue();
		assertThat(data.commentFound()).isNull();
		assertThat(data.metrics()).containsEntry("likes", 120L).containsEntry("comments", 3L).hasSize(2);
		// verify() 要 RequestPatternBuilder（post() 静态方法返回 MappingBuilder，只能用于打桩）
		var request = new com.github.tomakehurst.wiremock.matching.RequestPatternBuilder(
				com.github.tomakehurst.wiremock.http.RequestMethod.POST, urlEqualTo("/v1/official-verification"))
				.withHeader("Authorization", equalTo("Bearer gw-token"));
		GATEWAY.verify(request);
		for (String field : java.util.List.of("\"platform\":\"douyin\"", "\"contentUrl\":\"https://www.douyin.com/x\"",
				"\"platformHandle\":\"@seed\"", "\"commentText\":\"好评\"")) {
			GATEWAY.verify(new com.github.tomakehurst.wiremock.matching.RequestPatternBuilder(
					com.github.tomakehurst.wiremock.http.RequestMethod.POST, urlEqualTo("/v1/official-verification"))
					.withHeader("Authorization", equalTo("Bearer gw-token")).withRequestBody(containing(field)));
		}
	}

	@Test
	void gatewayErrorOrMalformedBodyYieldsEmpty() {
		GATEWAY.stubFor(post(urlEqualTo("/v1/official-verification")).willReturn(aResponse().withStatus(500)));
		OfficialVerificationGateway gateway = new OfficialVerificationGateway(GATEWAY.baseUrl(), "gw-token", 8000);
		assertThat(gateway.fetch("douyin", "https://www.douyin.com/x", null, null).block(Duration.ofSeconds(5)))
				.isNull();

		GATEWAY.stubFor(post(urlEqualTo("/v1/official-verification")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("不是 JSON")));
		assertThat(gateway.fetch("douyin", "https://www.douyin.com/x", null, null).block(Duration.ofSeconds(5)))
				.isNull();
	}
}
