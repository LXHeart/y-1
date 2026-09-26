package com.grassland.intelligence.hypit.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Vocabulary surface/visual 透传（任务书 #107-fix-1 C107F-05 / W21 / TC-F05-02 J 侧）：
 * surface/visual query 参数进 sidecar 载荷、B 侧 invalid_input 映射 400、无参载荷零变化。
 *
 * <p>
 * B 侧词法真相由 backend 单测对拍上游（tests/engine/vocabulary-surface.test.ts）；本 IT 只锁
 * J↔B 的参数/错误接线（sidecar 以 WireMock 托管，按载荷字段分派桩）。
 */
class HypitVocabularyIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";

	static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@DynamicPropertySource
	static void sidecarProps(DynamicPropertyRegistry r) {
		r.add("hypit.enabled", () -> "true");
		r.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		r.add("hypit.internal-token", () -> "it-sidecar-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void stubSidecar() {
		SIDECAR.resetAll();
		// surface 场景：surfaces 附加上游形状
		SIDECAR.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.kind", equalTo("vocabulary")))
				.withRequestBody(matchingJsonPath("$.payload.surface", containing("seedance")))
				.willReturn(commandBody(
						"{\"providers\":[],\"programs\":[],\"alignmentLanguages\":[],\"surfaces\":["
								+ "{\"package\":\"@hypit/seedance\",\"surface\":\"SeedanceVideo\",\"tag\":\"seedance/video\","
								+ "\"outputs\":[\"video\"]}]}")));
		// visual=具体形状
		SIDECAR.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.kind", equalTo("vocabulary")))
				.withRequestBody(matchingJsonPath("$.payload.visual", equalTo("text-typography")))
				.willReturn(commandBody(
						"{\"providers\":[],\"programs\":[],\"alignmentLanguages\":[],\"visual\":{"
								+ "\"shapes\":[{\"shape\":\"text-typography\",\"describes\":\"a text shape\"}],"
								+ "\"animatableLocalStyles\":[],\"styleNames\":[],\"styleEnumValues\":{},\"rules\":[]}}")));
		// visual 空串=形状清单
		SIDECAR.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.kind", equalTo("vocabulary")))
				.withRequestBody(matchingJsonPath("$.payload.visual", equalTo("")))
				.willReturn(commandBody(
						"{\"providers\":[],\"programs\":[],\"alignmentLanguages\":[],\"visual\":{"
								+ "\"shapes\":[{\"shape\":\"box\"},{\"shape\":\"text\"}]}}")));
		// 无参（载荷不含 surface/visual）：三字段基线
		SIDECAR.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.kind", equalTo("vocabulary")))
				.withRequestBody(notMatching(".*\"(surface|visual)\".*"))
				.willReturn(commandBody("{\"providers\":[],\"programs\":[],\"alignmentLanguages\":[\"en\"]}")));
		// 未知包：B 失败命令（200 + failed 行 + invalid_input）
		SIDECAR.stubFor(post(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.kind", equalTo("vocabulary")))
				.withRequestBody(matchingJsonPath("$.payload.surface", containing("no-such-package")))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"stubbed\",\"kind\":\"vocabulary\",\"state\":\"failed\","
								+ "\"error\":{\"code\":\"invalid_input\",\"message\":\"surface 包不可用\"}}")));
	}

	private static ResponseDefinitionBuilder commandBody(String resultJson) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
				.withBody("{\"commandId\":\"stubbed\",\"kind\":\"vocabulary\",\"state\":\"succeeded\","
						+ "\"result\":" + resultJson + ",\"error\":null}");
	}

	@Test
	void surfaceParameterIsPassedThroughAndSurfacesReturned() {
		client().get().uri("/api/hypit/vocabulary?surface=@hypit/seedance")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.success").isEqualTo(true).jsonPath("$.data.surfaces[0].package")
				.isEqualTo("@hypit/seedance").jsonPath("$.data.providers").isArray();
		SIDECAR.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(matchingJsonPath("$.payload.surface", equalTo("@hypit/seedance"))));
	}

	@Test
	void visualShapeAndEmptyVisualArePassedThrough() {
		client().get().uri("/api/hypit/vocabulary?visual=text-typography")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.visual.shapes[0].shape").isEqualTo("text-typography");
		client().get().uri("/api/hypit/vocabulary?visual")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.visual.shapes.length()").isEqualTo(2);
	}

	@Test
	void noArgumentStaysBaselineAndUnknownPackageMapsTo400() {
		// 无参：三字段基线，surfaces/visual 键不出现（零变化兼容）
		client().get().uri("/api/hypit/vocabulary").header("X-Grassland-Identity", sign(OWNER, null)).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.providers").isArray()
				.jsonPath("$.data.programs").isArray().jsonPath("$.data.alignmentLanguages[0]").isEqualTo("en");
		SIDECAR.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/internal/v1/commands"))
				.withRequestBody(notMatching(".*\"(surface|visual)\".*")));

		// 未知包：B invalid_input → 400 hypit_invalid_input
		client().get().uri("/api/hypit/vocabulary?surface=@hypit/no-such-package")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.code").isEqualTo("hypit_invalid_input").jsonPath("$.error")
				.value(error -> org.assertj.core.api.Assertions
						.assertThat(String.valueOf(error)).contains("surface"));
	}
}
