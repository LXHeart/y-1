package com.grassland.intelligence.ai.controlplane;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.grassland.intelligence.IntelligenceItSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 凭据连通性探测（任务书 #69 卡E）：GET {baseUrl}/models 三态分类（ok/unauthorized/unreachable）、
 * sandbox 直 ok 不出网、受信表外 base_url 失败留痕且零出站。上游用 WireMock 托管； 出站走 http +
 * loopback（受信 origin 表 + allow-insecure-loopback 测试开关放行）。 stub 体均为无嵌套引号的简单
 * JSON，可直接内联（复杂 stub 才需要 json.dumps 生成）。
 */
@DisplayName("PlatformProviderCredential probe (任务书 #69 卡E)")
class PlatformProviderCredentialProbeIT extends IntelligenceItSupport {

	/** 32 字节 KEK（0x00..0x1F）的 Base64，与既有凭据 IT 同款测试常量。 */
	private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
	private static final String ADMIN = "33333333-3333-3333-3333-333333333333";
	private static final String TEST_KEY = "sk-test-probe-1234567890abcdef";
	/** 本机 1 号端口（无监听）：连接拒绝 → unreachable 快速判型。 */
	private static final String DEAD_BASE_URL = "http://localhost:1";

	static final WireMockServer UPSTREAM = new WireMockServer(0);

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
		registry.add("ai.platform-model.allow-insecure-loopback", () -> "true");
	}

	@BeforeAll
	static void startUpstream() {
		UPSTREAM.start();
	}

	@AfterAll
	static void stopUpstream() {
		UPSTREAM.stop();
	}

	@Autowired
	TrustedOriginService trustedOrigins;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM platform_credential_model").then().block();
		db.sql("DELETE FROM platform_provider_credential").then().block();
		// 受信表只清本 IT 的 localhost 行，保留 V56 种子
		db.sql("DELETE FROM platform_trusted_origin WHERE origin LIKE 'http://localhost:%'").then().block();
		trustedOrigins.refresh().block(Duration.ofSeconds(5));
		trustedOrigins.create(upstreamBaseUrl(), "probe-it 上游", ADMIN).block(Duration.ofSeconds(5));
		trustedOrigins.create(DEAD_BASE_URL, "probe-it 不通端口", ADMIN).block(Duration.ofSeconds(5));
		trustedOrigins.refresh().block(Duration.ofSeconds(5));
		UPSTREAM.resetAll();
	}

	private static String upstreamBaseUrl() {
		return "http://localhost:" + UPSTREAM.port();
	}

	@Test
	@DisplayName("上游 200 → ok + 延迟落库；请求带 Bearer 密钥")
	void probeOk() {
		UPSTREAM.stubFor(get(urlEqualTo("/models")).willReturn(okJson("{\"data\":[]}")));

		String id = createCredential("probe-ok", "openai-completions", upstreamBaseUrl());
		client().post().uri("/api/admin/ai/credentials/" + id + "/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.status").isEqualTo("ok").jsonPath("$.checkedAt").isNotEmpty();

		UPSTREAM.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/models"))
				.withHeader("Authorization", new EqualToPattern("Bearer " + TEST_KEY)));

		String row = db
				.sql("SELECT last_probe_status || ':' || COALESCE(last_probe_latency_ms::text, 'null')"
						+ " AS probe FROM platform_provider_credential WHERE id = CAST(:id AS uuid)")
				.bind("id", id).map(r -> r.get("probe", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(row).isNotNull().startsWith("ok:");
	}

	@Test
	@DisplayName("上游 401 → unauthorized 落库")
	void probeUnauthorized() {
		UPSTREAM.stubFor(get(urlEqualTo("/models")).willReturn(status(401)));

		String id = createCredential("probe-401", "openai-completions", upstreamBaseUrl());
		client().post().uri("/api/admin/ai/credentials/" + id + "/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.status").isEqualTo("unauthorized").jsonPath("$.error").isEqualTo("上游 HTTP 401");

		String status = db
				.sql("SELECT last_probe_status AS s FROM platform_provider_credential"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", id).map(r -> r.get("s", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(status).isEqualTo("unauthorized");
	}

	@Test
	@DisplayName("端口不通 → unreachable（连接拒绝快速判型，不抛 5xx）")
	void probeUnreachable() {
		String id = createCredential("probe-dead", "openai-completions", DEAD_BASE_URL);
		client().post().uri("/api/admin/ai/credentials/" + id + "/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.status").isEqualTo("unreachable");
	}

	@Test
	@DisplayName("sandbox provider：不出网直接 ok/0ms，WireMock 零请求")
	void probeSandboxSkipsEgress() {
		String id = createCredential("probe-sandbox", "sandbox", "https://sandbox.invalid", null);
		client().post().uri("/api/admin/ai/credentials/" + id + "/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.status").isEqualTo("ok").jsonPath("$.latencyMs").isEqualTo(0).jsonPath("$.error")
				.isEqualTo("沙箱无上游");

		UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	@DisplayName("受信表外 base_url：失败留痕（status=error + 引导文案），零出站")
	void probeUntrustedOriginNeverEgresses() {
		String id = db.sql("""
				INSERT INTO platform_provider_credential(name, provider, base_url, enabled, version)
				VALUES ('probe-untrusted', 'openai-completions', 'https://probe-untrusted.example', true, 1)
				RETURNING id::text
				""").map(r -> r.get(0, String.class)).one().block(Duration.ofSeconds(5));
		client().post().uri("/api/admin/ai/credentials/" + id + "/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.status").isEqualTo("error");

		String stored = db
				.sql("SELECT last_probe_error AS e FROM platform_provider_credential" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).map(r -> r.get("e", String.class)).one().block(Duration.ofSeconds(5));
		assertThat(stored).contains("不在受信列表");

		UPSTREAM.verify(0, anyRequestedFor(anyUrl()));
	}

	@Test
	@DisplayName("凭据不存在 → 404；未登录 → 401")
	void probeGates() {
		client().post().uri("/api/admin/ai/credentials/" + "99999999-9999-9999-9999-999999999999/probe")
				.header("X-Grassland-Identity", signAdmin(ADMIN)).exchange().expectStatus().isNotFound();

		client().post().uri("/api/admin/ai/credentials/" + UUID.randomUUID() + "/probe").exchange().expectStatus()
				.isUnauthorized();
	}

	private String createCredential(String name, String provider, String baseUrl) {
		return createCredential(name, provider, baseUrl, TEST_KEY);
	}

	private String createCredential(String name, String provider, String baseUrl, String apiKey) {
		// null apiKey 序列化为空串（原样拼接会把 null 变成 "null"，过不了密钥强度校验）
		String key = apiKey == null ? "" : apiKey;
		return client().post().uri("/api/admin/ai/credentials").header("X-Grassland-Identity", signAdmin(ADMIN))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"name":"%s","provider":"%s","baseUrl":"%s","apiKey":"%s"}
						""".formatted(name, provider, baseUrl, key)).exchange().expectStatus().isCreated()
				.expectBody(PlatformProviderCredentialResponse.class).returnResult().getResponseBody().id().toString();
	}
}
