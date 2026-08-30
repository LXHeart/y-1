package com.grassland.intelligence.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 平台路径出站 DNS 钉扎专属回归（GL-P3-AI-001 尾巴，2026-08-21 三处接线）：
 * {@code OpenAiCompatibleHttpClientFactory.pinnedPlatformClient}（静态）、
 * {@code TextCompletionClient}
 * 平台分支、{@code OpenAiCompatibleHttpClientFactory.create} 平台分支（embedding/speech
 * 工厂同款）+ {@code QwenClient}。
 *
 * <p>
 * 判定法：基址使用系统 DNS 无法解析的 {@code .invalid} 域名（RFC 2606 保留），仅当客户端 连接期走
 * {@link DnsPinningResolver} 的固定地址（pin 到 127.0.0.1 的 WireMock）时请求才能 成功——系统 DNS
 * 路径会 UnknownHostException 失败，即「校验-连接间无系统 DNS 参与」的行为证明。
 */
@DisplayName("平台路径出站 DNS 钉扎（假域名 + 固定地址连通性证明）")
class PinnedPlatformClientsTest {

	private static final com.github.tomakehurst.wiremock.WireMockServer WIRE_MOCK = new com.github.tomakehurst.wiremock.WireMockServer(
			0);
	/** RFC 2606 保留的不可解析域名：系统 DNS 恒失败，唯一通路是钉扎地址。 */
	private static final String FAKE_HOST = "pinned-platform.invalid";
	private static final String COMPLETIONS_BODY = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
			+ "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}";

	private static DnsPinningResolver resolver;
	private static String pinnedBaseUrl;

	@BeforeAll
	static void start() {
		WIRE_MOCK.start();
		WIRE_MOCK.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong")));
		WIRE_MOCK.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(COMPLETIONS_BODY)));
		// TextCompletionClient 以相对路径 chat/completions 拼接 baseUrl（不带 /v1 前缀）
		WIRE_MOCK.stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(COMPLETIONS_BODY)));
		resolver = DnsPinningResolver.create();
		resolver.pinDomain(FAKE_HOST, Set.of("127.0.0.1"));
		pinnedBaseUrl = "http://" + FAKE_HOST + ":" + WIRE_MOCK.port();
	}

	@AfterAll
	static void stop() {
		WIRE_MOCK.stop();
	}

	@Test
	@DisplayName("静态 pinnedPlatformClient：连接走钉扎地址而非系统 DNS")
	void staticPinnedPlatformClientConnectsViaPinnedAddress() {
		org.springframework.web.reactive.function.client.WebClient client = OpenAiCompatibleHttpClientFactory
				.pinnedPlatformClient(PinnedPlatformClientsTest.class, pinnedBaseUrl, resolver, Duration.ofSeconds(5),
						1024 * 1024);
		String body = client.get().uri("/ping").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
		assertThat(body).isEqualTo("pong");
	}

	@Test
	@DisplayName("TextCompletionClient 平台分支：completeMessages 经钉扎地址解析 usage")
	void textCompletionPlatformPathIsPinned() {
		PlatformProviderPolicy policy = org.mockito.Mockito.mock(PlatformProviderPolicy.class);
		lenient().when(policy.validateBaseUrl(pinnedBaseUrl)).thenReturn(java.net.URI.create(pinnedBaseUrl));
		TextCompletionClient client = new TextCompletionClient(java.time.Duration.ofMillis(5_000), resolver, policy);

		var result = client
				.completeMessages(pinnedBaseUrl, "sk-test-platform-key-123456", "qwen-plus",
						java.util.List.of(com.grassland.intelligence.ai.ChatMessage.user("hi")), 64, false)
				.block(Duration.ofSeconds(10));

		assertThat(result).isNotNull();
		assertThat(result.content()).isEqualTo("ok");
		assertThat(result.inputTokens()).isEqualTo(3);
		assertThat(result.outputTokens()).isEqualTo(2);
	}

	@Test
	@DisplayName("工厂 create 平台分支（embedding/speech 同款）：钉扎地址连通")
	void factoryCreatePlatformBranchIsPinned() {
		OpenAiCompatibleHttpClientFactory factory = new OpenAiCompatibleHttpClientFactory(resolver,
				org.mockito.Mockito.mock(PlatformProviderPolicy.class));
		org.springframework.web.reactive.function.client.WebClient client = factory.create(
				PinnedPlatformClientsTest.class,
				new ProviderInvocation("qwen", pinnedBaseUrl, "qwen-plus", "sk-test-platform-key-123456", false),
				Duration.ofSeconds(5), 1024 * 1024);
		String body = client.get().uri("/ping").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
		assertThat(body).isEqualTo("pong");
	}

}
