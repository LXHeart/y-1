package com.grassland.intelligence.hypit.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Runtime 动作/路径面（任务书 #107-fix-1 C107F-04 / W15 / TC-F04-06、07、09）： doctor
 * 四段聚合、up/down 幂等编排（RULE-F05 down 保护三态）、init/use/unset 显式 409
 * hypit_runtime_managed、paths 逻辑键清单。
 *
 * <p>
 * sidecar 以第二个 WireMock 托管（/healthz + /internal/v1/commands 按 kind 分派）——J
 * 侧只验聚合与 门禁语义，程序编排的执行真相属 B 侧 deployment 契约测试层。活跃 Build 直插 DB 构造（activityHash
 * 来源=activeBuildSnapshot 真实计算）。
 */
@TestPropertySource(properties = "hypit.operator-account-ids=ffffffff-0000-4000-8000-00000000000f")
class HypitRuntimeActionIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";
	private static final String OPERATOR = "ffffffff-0000-4000-8000-00000000000f";

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

	@org.springframework.beans.factory.annotation.Autowired
	DatabaseClient db;

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		SIDECAR.resetAll();
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/healthz"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"ok\":true,\"enginePort\":9231}")));
		stubCommand("programs.status", "{\"phase\":\"up\",\"identity\":{\"version\":\"0.2.13\"}}");
		stubCommand("programs.up", "{\"phase\":\"up\"}");
		stubCommand("programs.down", "{\"phase\":\"down\"}");
		stubCommand("build.activity", "{\"localRender\":{\"active\":0,\"max\":1}}");
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'runtime-it', 'clone', 'ready', 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_build WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId == null ? UUID.randomUUID().toString() : projectId.toString()).then()
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	private static void stubCommand(String kind, String resultJson) {
		SIDECAR.stubFor(
				post(urlEqualTo("/internal/v1/commands")).withRequestBody(matchingJsonPath("$.kind", equalTo(kind)))
						.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
								"{\"commandId\":\"stubbed\",\"kind\":\"" + kind + "\",\"state\":\"succeeded\","
										+ "\"result\":" + resultJson + ",\"error\":null}")));
	}

	private UUID insertActiveBuild() {
		UUID buildId = UUID.randomUUID();
		// command_id 生产路径恒非空（BuildRepository.map 直走 UUID.fromString），直插须补齐
		db.sql("INSERT INTO hypit_build(id, command_id, project_id, revision, run_file, lifecycle)"
				+ " VALUES (CAST(:id AS uuid), CAST(:c AS uuid), CAST(:p AS uuid), 1, 'run.ts', 'submitting')")
				.bind("id", buildId.toString()).bind("c", UUID.randomUUID().toString()).bind("p", projectId.toString())
				.then().block(Duration.ofSeconds(10));
		return buildId;
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postAction(String account,
			Map<String, Object> body) {
		return client().post().uri("/api/hypit/runtime/actions").header("X-Grassland-Identity", sign(account, null))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}

	// ------------------------------------------------------------------
	// TC-F04-06：runtime doctor（含 403 / 程序 down 不粉饰）
	// ------------------------------------------------------------------

	@Test
	void doctorAggregatesFourSectionsForOperatorOnly() {
		// C107F2-09：engine.version 唯一来源是 broker /internal/v1/readiness（发行版
		// manifest），不再取 programs.status 的 identity.version——健康路径补 readiness 桩。
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/internal/v1/readiness"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"version\":\"0.2.13\",\"dependencies\":[{\"name\":\"engine\",\"ready\":true}]}")));
		Map<String, Object> body = Map.of("requestId", "44444444-4444-4444-8444-000000000001", "action", "doctor");

		postAction(OPERATOR, body).expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.engine.ready").isEqualTo(true).jsonPath("$.data.engine.version").isEqualTo("0.2.13")
				.jsonPath("$.data.programs['whisperx.local'].state").isEqualTo("up")
				.jsonPath("$.data.programs['whisperx.local'].health").isEqualTo("ok")
				.jsonPath("$.data.programs['image.opencv.local'].state").isEqualTo("up")
				.jsonPath("$.data.activity.activeBuilds").isEqualTo(0).jsonPath("$.data.activity.activityHash")
				.isNotEmpty();

		postAction(OWNER, body).expectStatus().isForbidden().expectBody().jsonPath("$.code")
				.isEqualTo("hypit_operator_required");
	}

	@Test
	void doctorReportsUnhealthyProgramsHonestly() {
		// 覆盖 status stub：一个程序 down + 引擎 /healthz 失败——报告如实，不粉饰 ready
		stubCommand("programs.status", "{\"phase\":\"down\"}");
		SIDECAR.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/healthz"))
				.willReturn(aResponse().withStatus(503)));
		postAction(OPERATOR, Map.of("requestId", "44444444-4444-4444-8444-000000000002", "action", "doctor"))
				.expectStatus().isOk().expectBody().jsonPath("$.data.engine.ready").isEqualTo(false)
				.jsonPath("$.data.engine.version").isEqualTo(null).jsonPath("$.data.programs['whisperx.local'].state")
				.isEqualTo("down").jsonPath("$.data.programs['whisperx.local'].health").isEqualTo(null);
	}

	// ------------------------------------------------------------------
	// TC-F04-07：down 三态（RULE-F05）+ up 幂等受理
	// ------------------------------------------------------------------

	@Test
	void downProtectsActiveBuildsByActivityHash() {
		Map<String, Object> body = Map.of("requestId", "44444444-4444-4444-8444-000000000003", "action", "down");

		// 无活跃 build → 直接 down（同步幂等结果：每程序终态）
		postAction(OPERATOR, body).expectStatus().isOk().expectBody().jsonPath("$.data.action").isEqualTo("down")
				.jsonPath("$.data.endpoints['whisperx.local'].phase").isEqualTo("down")
				.jsonPath("$.data.endpoints['image.opencv.local'].phase").isEqualTo("down");

		// 活跃 build：无 hash → 409 影响清单（buildIds + activityHash 双字段）
		UUID buildId = insertActiveBuild();
		byte[] conflictRaw = client().post().uri("/api/hypit/runtime/actions")
				.header("X-Grassland-Identity", sign(OPERATOR, null))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange()
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("hypit_activity_conflict")
				.returnResult().getResponseBody();
		String conflict = new String(conflictRaw, StandardCharsets.UTF_8);
		assertThat(conflict).as("409 响应含 buildIds 与 activityHash 双字段（防假阳性）").contains(buildId.toString())
				.contains("activityHash");

		// activityHash 以 doctor 报告为准（Java 端字符串格式无法用 SQL 精确复刻）
		byte[] doctorRaw = client().post().uri("/api/hypit/runtime/actions")
				.header("X-Grassland-Identity", sign(OPERATOR, null))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "44444444-4444-4444-8444-0000000000aa", "action", "doctor")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.activity.activeBuilds").isEqualTo(1).returnResult()
				.getResponseBody();
		Map<String, Object> doctor = com.grassland.intelligence.hypit.project.HypitJson
				.read(new String(doctorRaw, StandardCharsets.UTF_8));
		@SuppressWarnings("unchecked")
		Map<String, Object> activity = (Map<String, Object>) ((Map<String, Object>) doctor.get("data")).get("activity");
		String activityHash = String.valueOf(activity.get("activityHash"));

		// 错 hash → 409；匹配 hash → 程序 down
		postAction(OPERATOR,
				Map.of("requestId", "44444444-4444-4444-8444-000000000004", "action", "down", "expectedActivityHash",
						"dead" + activityHash.substring(4)))
				.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("hypit_activity_conflict");
		postAction(OPERATOR,
				Map.of("requestId", "44444444-4444-4444-8444-000000000005", "action", "down", "expectedActivityHash",
						activityHash))
				.expectStatus().isOk().expectBody().jsonPath("$.data.action").isEqualTo("down")
				.jsonPath("$.data.endpoints['whisperx.local'].phase").isEqualTo("down");
	}

	@Test
	void upAndInvalidActionsAreHandled() {
		postAction(OPERATOR, Map.of("requestId", "44444444-4444-4444-8444-000000000006", "action", "up")).expectStatus()
				.isOk().expectBody().jsonPath("$.data.action").isEqualTo("up")
				.jsonPath("$.data.endpoints['whisperx.local'].phase").isEqualTo("up");

		// init/use/unset：显式 409 hypit_runtime_managed（D-03）
		for (String managed : List.of("init", "use", "unset")) {
			postAction(OPERATOR, Map.of("requestId", "44444444-4444-4444-8444-000000000007", "action", managed))
					.expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("hypit_runtime_managed");
		}

		// 未知 action / endpointIds 越界 → 400
		postAction(OPERATOR, Map.of("requestId", "44444444-4444-4444-8444-000000000008", "action", "reboot"))
				.expectStatus().isBadRequest();
		postAction(OPERATOR, Map.of("requestId", "44444444-4444-4444-8444-000000000009", "action", "up", "endpointIds",
				List.of("rogue.endpoint"))).expectStatus().isBadRequest();
	}

	// ------------------------------------------------------------------
	// TC-F04-09：runtime paths 逻辑键
	// ------------------------------------------------------------------

	@Test
	void pathsListLogicalKeysOnlyForOperator() {
		byte[] raw = client().get().uri("/api/hypit/runtime/paths").header("X-Grassland-Identity", sign(OPERATOR, null))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.hostPathsRevealed").isEqualTo(false)
				.jsonPath("$.data.logical.projectsRoot").isEqualTo("<dataRoot>/projects")
				.jsonPath("$.data.logical.artifactsRoot").isEqualTo("<dataRoot>/artifacts")
				.jsonPath("$.data.logical.importStagingRoot").isEqualTo("<dataRoot>/import-staging")
				.jsonPath("$.data.logical.stateRoot").isEqualTo("<hostStateRoot>")
				.jsonPath("$.data.logical.programsHome").isEqualTo("<dataRoot>/programs")
				.jsonPath("$.data.logical.runnerSlots").isEqualTo("<dataRoot>/runner-slots").returnResult()
				.getResponseBody();
		String body = new String(raw, StandardCharsets.UTF_8);
		assertThat(body).as("响应不含宿主绝对路径痕迹（E-i 字符串断言）").doesNotContain("/Users/").doesNotContain("/home/")
				.doesNotContain(":\\");

		client().get().uri("/api/hypit/runtime/paths").header("X-Grassland-Identity", sign(OWNER, null)).exchange()
				.expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("hypit_operator_required");
	}
}
