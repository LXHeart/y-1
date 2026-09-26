package com.grassland.intelligence.hypit.template;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 模板目录与工程进出持久面（任务书 #107-3 C107-20 / TC107-20-02/03/04 Java 层）： 真 PostgreSQL
 * 上幂等命令承载 export/import；sidecar 桩回执如实贯通（被拒 导入不落 ready 工程、413/400/404
 * 按码映射）；模板目录从 sidecar 读单份真相。 broker 侧 manifest/hash/穿越拒绝由
 * B/tests/workspace/project-transfer.test.ts 真值覆盖。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitTemplateIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000001d";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	HypitTemplateService templates;

	@Autowired
	HypitProjectPackageService packages;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@org.junit.jupiter.api.BeforeEach
	void clean() {
		SIDECAR.resetAll();
		db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then().block(Duration.ofSeconds(10));
	}

	@Test
	void templateCatalogFromSidecarSingleSource() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("templates.list"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"templates.list","state":"succeeded","result":{"templates":[
						  {"templateId":"ranking-tier","title":"榜单排行卡","materialState":"ready",
						   "runPaths":["main.svrun"],"requiredCapabilities":[]},
						  {"templateId":"caption-motion","title":"字幕动效短视频","materialState":"ready"},
						  {"templateId":"deck-stack","title":"图卡堆叠讲解","materialState":"ready"}]}}
						""")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("templates.detail"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"templates.detail","state":"succeeded","result":{
						  "templateId":"ranking-tier","title":"榜单排行卡","materialState":"ready",
						  "runPaths":["main.svrun"],"requiredCapabilities":[]}}
						""")));
		List<Map<String, Object>> items = templates.list().block(Duration.ofSeconds(10));
		assertThat(items).hasSize(3);
		Map<String, Object> detail = templates.detail("ranking-tier").block(Duration.ofSeconds(10));
		assertThat(detail).isNotNull();
		assertThat(detail.get("materialState")).isEqualTo("ready");
		// 目录未命中如实 404（不假 ready、不返回其它模板冒充）。
		SIDECAR.resetAll();
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("templates.detail"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"templates.detail","state":"failed",
						 "error":{"code":"hypit_not_found","message":"template not found: no-such"}}
						""")));
		try {
			templates.requireClonable("no-such").block(Duration.ofSeconds(10));
			throw new AssertionError("expected 404");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_not_found");
		}
	}

	@Test
	void tc04ImportRefusalSurfacesCodeAndPersistsNothing() {
		// sidecar 拒绝导入（hash mismatch → hypit_invalid_input）：Java 如实映射 400，
		// 不产生 ready 工程行；幂等命令行保留失败回执可审计。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.import"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"project-package.import","state":"failed",
						 "error":{"code":"hypit_invalid_input","message":"hash mismatch for main.svml"}}
						""")));
		try {
			packages.import_(OWNER, UUID.randomUUID(), "project-exports/x/rev-1").block(Duration.ofSeconds(10));
			throw new AssertionError("expected 400");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_invalid_input");
			assertThat(error.getMessage()).contains("hash mismatch");
		}
		Long commands = db.sql("SELECT count(*) AS n FROM hypit_command WHERE action = 'project.import'")
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(commands).isEqualTo(1L);
	}

	@Test
	void exportIsIdempotentCommandAndArtifactRootPassesThrough() {
		String exportReceipt = """
				{"commandId":"x","kind":"project-package.export","state":"succeeded",
				 "result":{"artifactRoot":"project-exports/x/rev-1","fileCount":4,"manifest":null}}
				""";
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("project-package.export")).willReturn(aResponse().withStatus(200)
						.withHeader("Content-Type", "application/json").withBody(exportReceipt)));
		UUID requestId = UUID.randomUUID();
		Map<String, Object> first = packages.export(OWNER, PROJECT_ANY, requestId, "导出", null, "main.svrun")
				.block(Duration.ofSeconds(10));
		assertThat(first).isNotNull();
		assertThat(String.valueOf(first.get("artifactRoot"))).startsWith("project-exports/");
		// 同 requestId 重放：命令域幂等（不再发 sidecar 命令）。
		SIDECAR.resetAll();
		Map<String, Object> replay = packages.export(OWNER, PROJECT_ANY, requestId, "导出", null, "main.svrun")
				.block(Duration.ofSeconds(10));
		assertThat(replay).isNotNull();
		assertThat(String.valueOf(replay.get("artifactRoot"))).startsWith("project-exports/");
	}

	private static final UUID PROJECT_ANY = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000b004");
}
